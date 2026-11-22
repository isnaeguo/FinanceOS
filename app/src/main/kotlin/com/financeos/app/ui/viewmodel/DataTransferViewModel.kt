package com.financeos.app.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeos.app.data.AndroidDocumentStore
import com.financeos.shared.data.local.repository.LocalFinanceDataRepository
import com.financeos.shared.data.transfer.table.PreparedStatement
import com.financeos.shared.data.transfer.table.StatementColumnMapping
import com.financeos.shared.data.transfer.table.StatementColumns
import com.financeos.shared.data.transfer.table.TableTransactionImporter
import com.financeos.shared.data.transfer.DataTransferException
import com.financeos.shared.data.transfer.FinanceDataTransferService
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.FinanceDataImportResult
import com.financeos.shared.domain.model.FinanceDataSnapshot
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import java.time.ZoneId
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 待用户确认的完整恢复摘要，不把大体积快照塞进 Compose 状态。 */
data class PendingRestoreUiState(
    val transactionCount: Int,
    val categoryCount: Int,
    val budgetCount: Int,
)

/** 设置页数据导入导出的状态。 */
data class DataTransferUiState(
    val isBusy: Boolean = false,
    val pendingRestore: PendingRestoreUiState? = null,
    val pendingImport: PendingImportUiState? = null,
    val statementColumns: StatementColumns? = null,
    val importError: String? = null,
)

data class PendingImportUiState(
    val format: String,
    val transactionCount: Int,
    val categoryCount: Int = 0,
    val budgetCount: Int = 0,
    val skippedRows: Int = 0,
    val duplicateRows: Int = 0,
    val invalidRows: Int = 0,
    val samples: List<String> = emptyList(),
    val issues: List<String> = emptyList(),
    val canMapColumns: Boolean = false,
    val needsColumnMapping: Boolean = false,
    val sourceCurrencyCode: String? = null,
    val requiresCurrencyConversion: Boolean = false,
) {
    val canImport: Boolean
        get() = !requiresCurrencyConversion &&
            (transactionCount > 0 || categoryCount > 0 || budgetCount > 0)
}

sealed interface DataTransferEvent {
    data class ShowMessage(val message: String) : DataTransferEvent
}

/** 管理文件读写、导入合并和恢复确认；Composable 不接触 Repository。 */
internal class DataTransferViewModel(
    private val service: FinanceDataTransferService,
    private val repository: LocalFinanceDataRepository,
    private val documentStore: AndroidDocumentStore,
    private val loadCategories: suspend () -> List<Category>,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DataTransferUiState())
    val uiState: StateFlow<DataTransferUiState> = _uiState.asStateFlow()

    private val _events = Channel<DataTransferEvent>(capacity = Channel.BUFFERED)
    val events: Flow<DataTransferEvent> = _events.receiveAsFlow()

    private var pendingRestoreSnapshot: FinanceDataSnapshot? = null
    private var pendingImportSnapshot: FinanceDataSnapshot? = null
    private var pendingStatement: TableTransactionImporter.DecodeResult? = null
    private var preparedStatement: PreparedStatement? = null
    private var appliedColumnMapping: StatementColumnMapping? = null
    private var pendingCategoryNames: Map<String, String> = emptyMap()

    fun exportJson(uri: Uri) = runFileOperation("JSON 已导出") {
        documentStore.writeText(uri, service.exportJson())
    }

    fun exportCsv(uri: Uri) = runFileOperation("CSV 已导出") {
        documentStore.writeText(uri, service.exportTransactionsCsv())
    }

    fun createBackup(uri: Uri) = runFileOperation("本地备份已创建") {
        documentStore.writeText(uri, service.exportJson())
    }

    fun importData(uri: Uri) = startImport {
        val bytes = documentStore.readBytes(uri)
        val isJson = withContext(Dispatchers.Default) { looksLikeJson(bytes) }
        if (isJson) {
            val snapshot = withContext(Dispatchers.Default) {
                service.parseJson(String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF"))
            }
            pendingImportSnapshot = snapshot
            _uiState.update {
                it.copy(
                    pendingImport = PendingImportUiState(
                        format = "FinanceOS JSON",
                        transactionCount = snapshot.transactions.size,
                        categoryCount = snapshot.categories.size,
                        budgetCount = snapshot.budgets.size,
                    ),
                )
            }
        } else {
            val prepared = withContext(Dispatchers.Default) { TableTransactionImporter.prepare(bytes) }
            showAutomaticPreview(prepared)
        }
    }

    fun importPastedStatement(text: String) = startImport {
        require(text.isNotBlank()) { "请粘贴包含表头和流水的表格文本。" }
        require(text.length <= MAX_PASTE_CHARACTERS) { "粘贴内容过长，请改用文件导入。" }
        val prepared = withContext(Dispatchers.Default) { TableTransactionImporter.prepareText(text) }
        showAutomaticPreview(prepared)
    }

    fun editStatementColumns() {
        if (_uiState.value.isBusy) return
        val columns = preparedStatement?.columns ?: return
        val current = appliedColumnMapping?.let { columns.copy(suggestedMapping = it) } ?: columns
        _uiState.update { it.copy(statementColumns = current, importError = null) }
    }

    fun closeStatementColumns() {
        val state = _uiState.value
        if (state.isBusy) return
        if (state.pendingImport?.needsColumnMapping == true) {
            clearImport()
        } else {
            _uiState.update { it.copy(statementColumns = null, importError = null) }
        }
    }

    fun applyStatementColumns(mapping: StatementColumnMapping) {
        val prepared = preparedStatement ?: return
        runDataOperation("无法按所选列读取这份账单", showInImport = true) {
            val categories = loadCategories()
            val result = withContext(Dispatchers.Default) {
                prepared.preview(categories, System.currentTimeMillis(), mapping)
            }
            appliedColumnMapping = mapping
            showStatement(result, categories)
            _uiState.update { it.copy(statementColumns = null) }
        }
    }

    fun applyStatementExchangeRate(exchangeRateText: String) {
        val statement = pendingStatement ?: return
        if (!statement.requiresCurrencyConversion) return
        runDataOperation("无法换算这份账单", showInImport = true) {
            showStatement(
                withContext(Dispatchers.Default) {
                    TableTransactionImporter.convertToCny(statement, exchangeRateText)
                },
            )
        }
    }

    fun dismissImport() {
        if (_uiState.value.isBusy) return
        clearImport()
    }

    fun confirmImport() {
        val statement = pendingStatement
        val snapshot = pendingImportSnapshot
        if (_uiState.value.isBusy || _uiState.value.pendingImport?.canImport != true) return
        runDataOperation("导入失败，请稍后重试", showInImport = true) {
            val imported = withContext(Dispatchers.Default) {
                when {
                    statement != null -> repository.importTransactions(statement.transactions)
                    snapshot != null -> repository.merge(snapshot)
                    else -> return@withContext null
                }
            } ?: return@runDataOperation
            clearImport()
            val message = if (statement != null) {
                val existing = statement.transactions.size - imported.transactionCount
                val existingMessage = if (existing > 0) "，另有 $existing 笔已存在" else ""
                "已新增 ${imported.transactionCount} 笔流水$existingMessage"
            } else {
                "已合并 ${imported.transactionCount} 笔流水、${imported.categoryCount} 个分类和 ${imported.budgetCount} 条预算"
            }
            _events.send(DataTransferEvent.ShowMessage(message))
        }
    }

    private fun startImport(operation: suspend () -> Unit) {
        if (_uiState.value.isBusy) return
        clearImport()
        pendingRestoreSnapshot = null
        _uiState.update { it.copy(pendingRestore = null) }
        runDataOperation("导入失败，请检查文件", operation = operation)
    }

    private suspend fun showAutomaticPreview(prepared: PreparedStatement) {
        val categories = loadCategories()
        val columns = prepared.columns
        preparedStatement = prepared.takeIf { columns.format != "OFX" }
        try {
            val result = withContext(Dispatchers.Default) {
                prepared.preview(categories, System.currentTimeMillis())
            }
            showStatement(result, categories)
        } catch (error: DataTransferException) {
            if (columns.format == "OFX") throw error
            _uiState.update {
                it.copy(
                    pendingImport = PendingImportUiState(
                        format = columns.format,
                        transactionCount = 0,
                        issues = listOfNotNull(error.message),
                        canMapColumns = true,
                        needsColumnMapping = true,
                    ),
                    statementColumns = columns,
                )
            }
        }
    }

    private fun showStatement(
        result: TableTransactionImporter.DecodeResult,
        categories: List<Category>? = null,
    ) {
        pendingStatement = result
        if (categories != null) pendingCategoryNames = categories.associate { it.id to it.name }
        val sourceCurrency = result.sourceCurrencyCode.takeIf { result.requiresCurrencyConversion }
        _uiState.update {
            it.copy(
                pendingImport = PendingImportUiState(
                    format = result.format,
                    transactionCount = result.transactions.size,
                    skippedRows = result.skippedRows,
                    duplicateRows = result.duplicateRows,
                    invalidRows = result.invalidRows,
                    samples = result.transactions.take(5).map { transaction ->
                        statementSample(transaction, sourceCurrency, pendingCategoryNames[transaction.categoryId])
                    },
                    issues = result.issueMessages.take(6),
                    canMapColumns = result.format != "OFX",
                    sourceCurrencyCode = result.sourceCurrencyCode,
                    requiresCurrencyConversion = result.requiresCurrencyConversion,
                ),
            )
        }
    }

    private fun clearImport() {
        pendingStatement = null
        preparedStatement = null
        appliedColumnMapping = null
        pendingCategoryNames = emptyMap()
        pendingImportSnapshot = null
        _uiState.update { it.copy(pendingImport = null, statementColumns = null, importError = null) }
    }

    fun prepareRestore(uri: Uri) {
        if (_uiState.value.isBusy) return
        clearImport()
        pendingRestoreSnapshot = null
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, pendingRestore = null) }
            try {
                val snapshot = withContext(Dispatchers.Default) {
                    val content = documentStore.readText(uri).removePrefix("\uFEFF").trimStart()
                    if (!content.startsWith("{")) {
                        throw DataTransferException("所选文件不是 FinanceOS JSON 备份。")
                    }
                    service.parseJson(content)
                }
                pendingRestoreSnapshot = snapshot
                _uiState.value = DataTransferUiState(
                    pendingRestore = PendingRestoreUiState(
                        transactionCount = snapshot.transactions.size,
                        categoryCount = snapshot.categories.size,
                        budgetCount = snapshot.budgets.size,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.value = DataTransferUiState()
                _events.send(DataTransferEvent.ShowMessage(error.userMessage("无法读取备份文件")))
            }
        }
    }

    fun dismissRestore() {
        if (_uiState.value.isBusy) return
        pendingRestoreSnapshot = null
        _uiState.update { it.copy(pendingRestore = null) }
    }

    fun confirmRestore() {
        val snapshot = pendingRestoreSnapshot ?: return
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val result = withContext(Dispatchers.Default) { service.restore(snapshot) }
                pendingRestoreSnapshot = null
                _uiState.value = DataTransferUiState()
                _events.send(DataTransferEvent.ShowMessage(result.restoreSuccessMessage()))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(isBusy = false) }
                _events.send(DataTransferEvent.ShowMessage(error.userMessage("恢复失败，请稍后重试")))
            }
        }
    }

    private fun runFileOperation(successMessage: String, operation: suspend () -> Unit) {
        runDataOperation("文件操作失败，请稍后重试") {
            withContext(Dispatchers.Default) { operation() }
            _events.send(DataTransferEvent.ShowMessage(successMessage))
        }
    }

    private fun runDataOperation(
        fallback: String,
        showInImport: Boolean = false,
        operation: suspend () -> Unit,
    ) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isBusy = true, importError = null) }
        viewModelScope.launch {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.userMessage(fallback)
                if (showInImport) {
                    _uiState.update { it.copy(importError = message) }
                } else {
                    _events.send(DataTransferEvent.ShowMessage(message))
                }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }
}

internal const val MAX_PASTE_CHARACTERS = 1_000_000

private fun looksLikeJson(bytes: ByteArray): Boolean {
    var start = 0
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        start = 3
    }
    for (index in start until bytes.size) {
        when (bytes[index].toInt()) {
            9, 10, 13, 32 -> continue
            else -> return bytes[index] == '{'.code.toByte()
        }
    }
    return false
}

private fun statementSample(transaction: Transaction, currencyCode: String?, categoryName: String?): String {
    val date = java.time.Instant.ofEpochMilli(transaction.dateTime.toEpochMilliseconds())
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    val prefix = if (transaction.type == TransactionType.INCOME) "+" else "−"
    val amount = if (currencyCode == null) {
        formatMoney(transaction.amount)
    } else {
        "$currencyCode ${amountInputFromMinorUnits(transaction.amount)}"
    }
    return listOfNotNull(
        date.toString(),
        "$prefix$amount",
        categoryName,
        transaction.accountId?.take(24),
        transaction.note?.take(50),
    ).joinToString(" · ")
}

private fun FinanceDataImportResult.restoreSuccessMessage(): String =
    "恢复完成：$transactionCount 笔流水、$categoryCount 个分类、$budgetCount 条预算"

private fun Exception.userMessage(fallback: String): String = when (this) {
    is DataTransferException, is IllegalArgumentException -> message ?: fallback
    else -> fallback
}
