package com.financeos.app.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeos.app.data.AndroidDocumentStore
import com.financeos.shared.data.transfer.DataTransferException
import com.financeos.shared.data.transfer.FinanceDataTransferService
import com.financeos.shared.domain.model.FinanceDataImportResult
import com.financeos.shared.domain.model.FinanceDataSnapshot
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
)

sealed interface DataTransferEvent {
    data class ShowMessage(val message: String) : DataTransferEvent
}

/** 管理文件读写、导入合并和恢复确认；Composable 不接触 Repository。 */
internal class DataTransferViewModel(
    private val service: FinanceDataTransferService,
    private val documentStore: AndroidDocumentStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DataTransferUiState())
    val uiState: StateFlow<DataTransferUiState> = _uiState.asStateFlow()

    private val _events = Channel<DataTransferEvent>(capacity = Channel.BUFFERED)
    val events: Flow<DataTransferEvent> = _events.receiveAsFlow()

    private var pendingRestoreSnapshot: FinanceDataSnapshot? = null

    fun exportJson(uri: Uri) = runFileOperation("JSON 已导出") {
        documentStore.writeText(uri, service.exportJson())
    }

    fun exportCsv(uri: Uri) = runFileOperation("CSV 已导出") {
        documentStore.writeText(uri, service.exportTransactionsCsv())
    }

    fun createBackup(uri: Uri) = runFileOperation("本地备份已创建") {
        documentStore.writeText(uri, service.exportJson())
    }

    fun importJson(uri: Uri) = runImportOperation {
        service.importJson(documentStore.readText(uri))
    }

    fun importCsv(uri: Uri) = runImportOperation {
        service.importTransactionsCsv(documentStore.readText(uri))
    }

    fun prepareRestore(uri: Uri) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, pendingRestore = null) }
            try {
                val snapshot = withContext(Dispatchers.Default) {
                    service.parseJson(documentStore.readText(uri))
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
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                // 编解码和大文件处理离开主线程，避免设置页在导入导出期间掉帧。
                withContext(Dispatchers.Default) { operation() }
                _events.send(DataTransferEvent.ShowMessage(successMessage))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _events.send(DataTransferEvent.ShowMessage(error.userMessage("文件操作失败，请稍后重试")))
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun runImportOperation(operation: suspend () -> FinanceDataImportResult) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val result = withContext(Dispatchers.Default) { operation() }
                _events.send(DataTransferEvent.ShowMessage(result.importSuccessMessage()))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _events.send(DataTransferEvent.ShowMessage(error.userMessage("导入失败，请检查文件")))
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }
}

private fun FinanceDataImportResult.importSuccessMessage(): String =
    "导入完成：$transactionCount 笔流水、$categoryCount 个分类、$budgetCount 条预算"

private fun FinanceDataImportResult.restoreSuccessMessage(): String =
    "恢复完成：$transactionCount 笔流水、$categoryCount 个分类、$budgetCount 条预算"

private fun Exception.userMessage(fallback: String): String = when (this) {
    is DataTransferException, is IllegalArgumentException -> message ?: fallback
    else -> fallback
}
