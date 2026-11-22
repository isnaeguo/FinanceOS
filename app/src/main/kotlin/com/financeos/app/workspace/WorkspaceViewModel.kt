package com.financeos.app.workspace

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeos.app.ai.AiConnectionStore
import com.financeos.app.ai.AndroidAiTransport
import com.financeos.shared.ai.FinanceAi
import com.financeos.shared.ai.FinanceAuditAnswer
import com.financeos.shared.ai.FinanceAuditNarrative
import com.financeos.shared.ai.FinanceChat
import com.financeos.shared.ai.FinanceChatPeriods
import com.financeos.shared.ai.FinanceConversationMessage
import com.financeos.shared.ai.FinanceConversationRole
import com.financeos.shared.data.local.repository.LocalTransactionRepository
import com.financeos.shared.data.transfer.FinanceDataJsonCodec
import com.financeos.shared.domain.repository.FinanceDataRepository
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.repository.CategoryRepository
import com.financeos.shared.workspace.FinanceWorkspace
import com.financeos.shared.workspace.FinanceWorkspaceAnalytics
import com.financeos.shared.workspace.FinanceWorkspaceCodec
import com.financeos.shared.workspace.FinanceWorkspaceState
import com.financeos.shared.workspace.WorkspaceAccount
import com.financeos.shared.workspace.WorkspaceAssignment
import com.financeos.shared.workspace.WorkspaceAutomation
import com.financeos.shared.workspace.WorkspaceBook
import com.financeos.shared.workspace.WorkspaceCheckpoint
import com.financeos.shared.workspace.WorkspaceForecast
import com.financeos.shared.workspace.WorkspaceInboxItem
import com.financeos.shared.workspace.WorkspaceLink
import com.financeos.shared.workspace.WorkspacePlan
import com.financeos.shared.workspace.WorkspaceRule
import com.financeos.shared.workspace.WorkspaceRuleSuggestion
import com.financeos.shared.workspace.WorkspaceSummary
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class WorkspaceSelection(
    val bookId: String? = null,
    val forecastDays: Int = 30,
    val scenario: String? = null,
)

internal data class WorkspaceProjection(
    val state: FinanceWorkspaceState,
    val selection: WorkspaceSelection,
    val transactions: List<Transaction>,
    val scopedTransactions: List<Transaction>,
    val categories: List<Category>,
    val unmanagedAccountAliases: List<String>,
    val summary: WorkspaceSummary,
    val forecast: WorkspaceForecast,
    val inbox: List<WorkspaceInboxItem>,
    val ruleSuggestions: List<WorkspaceRuleSuggestion>,
)

internal data class WorkspaceOperation(
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

internal enum class WorkspaceChatMode(val label: String) {
    LEDGER("账本对话"),
    GENERAL("通用对话"),
}

internal data class WorkspaceChatTurn(
    val id: Long,
    val question: String,
    val days: Int,
    val requestedAi: Boolean,
    val resolvedQuestion: String? = null,
    val answer: FinanceAuditAnswer? = null,
    val explanation: FinanceAuditNarrative? = null,
    val response: String? = null,
    val error: String? = null,
)

internal data class WorkspaceChatHistory(
    val ledger: List<WorkspaceChatTurn> = emptyList(),
    val general: List<WorkspaceChatTurn> = emptyList(),
) {
    fun turns(mode: WorkspaceChatMode): List<WorkspaceChatTurn> = when (mode) {
        WorkspaceChatMode.LEDGER -> ledger
        WorkspaceChatMode.GENERAL -> general
    }
}

internal class WorkspaceViewModel(
    context: Context,
    private val transactions: LocalTransactionRepository,
    private val categories: CategoryRepository,
    private val aiConnectionStore: AiConnectionStore,
    private val financeData: FinanceDataRepository,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val store = WorkspaceStore(appContext)
    private val receipts = ReceiptVault(appContext)
    private val engine = FinanceWorkspace()
    private val analytics = FinanceWorkspaceAnalytics()
    private val automation = WorkspaceAutomation()
    private val local = MutableStateFlow<FinanceWorkspaceState?>(null)
    private val selection = MutableStateFlow(WorkspaceSelection())
    private var cachedProjection: WorkspaceProjection? = null
    private var cachedDay = Long.MIN_VALUE
    private val _operation = MutableStateFlow(WorkspaceOperation())
    val operation = _operation.asStateFlow()
    private val _chat = MutableStateFlow(WorkspaceChatHistory())
    val chat = _chat.asStateFlow()
    private var nextChatId = 0L
    private var generalProvider: Pair<String, String>? = null

    val projection = combine(
        local.filterNotNull(),
        transactions.observeAll(),
        categories.observeAll(),
        selection,
    ) { state, rows, categoryList, selected ->
        try {
            project(state, rows, categoryList, selected)
        } catch (error: IllegalArgumentException) {
            _operation.value = _operation.value.copy(error = error.message ?: "工作台暂时无法读取。")
            null
        }
    }.flowOn(Dispatchers.Default).filterNotNull()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3_000), null)

    init {
        reload()
    }

    fun reload() = perform {
        local.value = store.load()
    }

    fun selectBook(bookId: String?) {
        if (selection.value.bookId == bookId) return
        selection.value = selection.value.copy(bookId = bookId, scenario = null)
        _chat.value = _chat.value.copy(ledger = emptyList())
    }

    fun forecastDays(days: Int) {
        selection.value = selection.value.copy(forecastDays = days)
    }

    fun selectScenario(name: String?) {
        selection.value = selection.value.copy(scenario = name)
    }

    fun saveAccount(account: WorkspaceAccount) = update("账户已保存") {
        engine.saveAccount(it, account)
    }

    fun saveBook(book: WorkspaceBook) = update("项目账本已保存") {
        engine.saveBook(it, book)
    }

    fun saveAssignment(assignment: WorkspaceAssignment) = update("流水已关联") {
        engine.saveAssignment(it, assignment, listOfNotNull(transactions.get(assignment.transactionId)))
    }

    fun saveLink(link: WorkspaceLink) = update("关联已保存") {
        engine.saveLink(it, link, listOfNotNull(transactions.get(link.sourceId), transactions.get(link.targetId)))
    }

    fun removeLink(sourceId: String) = update("关联已取消") { engine.removeLink(it, sourceId) }

    fun saveCheckpoint(checkpoint: WorkspaceCheckpoint) = update("对账记录已保存") {
        engine.saveCheckpoint(it, checkpoint)
    }

    fun savePlan(plan: WorkspacePlan) = update("现金流计划已保存") { engine.savePlan(it, plan) }

    fun removePlan(id: String) = update("现金流计划已移除") { engine.removePlan(it, id) }

    fun saveInboxItem(item: WorkspaceInboxItem, attachment: Uri?) = perform("收件箱项目已保存") {
        val initial = local.value ?: error("工作台尚未加载。")
        val file = attachment?.let { receipts.save(item.id, it) }
        try {
            val saved = if (file == null) item else item.copy(
                attachmentName = item.attachmentName ?: "票据图片",
                attachmentReference = item.id,
            )
            persist(engine.saveAutomation(initial, automation.saveItem(initial.automation, saved)))
        } catch (error: Exception) {
            file?.delete()
            throw error
        }
    }

    fun completeItem(id: String) = update("已处理") {
        engine.saveAutomation(it, automation.completeItem(it.automation, id))
    }

    fun reopenItem(id: String) = update("已重新打开") { state ->
        val item = state.automation.items.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("待办已经移除。")
        engine.saveAutomation(state, automation.saveItem(state.automation, item.copy(completed = false)))
    }

    fun removeItem(id: String) = perform("项目已移除") {
        val initial = local.value ?: error("工作台尚未加载。")
        val attachment = initial.automation.items.firstOrNull { it.id == id }?.attachmentReference
        persist(engine.saveAutomation(initial, automation.removeItem(initial.automation, id)))
        if (attachment != null) withContext(Dispatchers.IO) { receipts.file(attachment).delete() }
    }

    fun saveRule(rule: WorkspaceRule) = update("规则已保存") {
        engine.saveAutomation(it, automation.saveRule(it.automation, rule))
    }

    fun removeRule(id: String) = update("规则已移除") {
        engine.saveAutomation(it, automation.removeRule(it.automation, id))
    }

    fun applyRule(suggestion: WorkspaceRuleSuggestion) = perform("分类规则已应用") {
        require(transactions.updateCategory(suggestion.transaction, suggestion.categoryId)) {
            "流水或分类已变化，请重新查看规则建议。"
        }
    }

    fun audit(question: String, useAi: Boolean, days: Int) =
        chatOperation(WorkspaceChatMode.LEDGER, question, days, useAi) { turn ->
            val current = projection.value ?: error("工作台尚未加载。")
            val bookId = selection.value.bookId
            require(current.selection.bookId == bookId) { "正在切换账本，请稍后重试。" }
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            fun epoch(day: LocalDate): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val monthStart = today.withDayOfMonth(1)
            val start = epoch(monthStart)
            val end = epoch(today.plusDays(1))
            val periods = FinanceChatPeriods(start, end, epoch(monthStart.minusMonths(1)), start)
            val previous = _chat.value.ledger.lastOrNull {
                it.id != turn.id && it.days == days && it.resolvedQuestion != null
            }?.resolvedQuestion
            val categoryList = categories.getAll()
            val reply = withContext(Dispatchers.Default) {
                if (days == 0) {
                    FinanceChat().answer(turn.question, previous, current.scopedTransactions, categoryList, periods)
                } else {
                    val rangeStart = epoch(today.minusDays(days.toLong() - 1))
                    FinanceChat().answerInRange(
                        turn.question, previous, current.scopedTransactions, categoryList, periods, rangeStart, end,
                    )
                }
            }
            if (selection.value.bookId != bookId) return@chatOperation
            updateTurn(WorkspaceChatMode.LEDGER, turn.id) {
                it.copy(resolvedQuestion = reply.resolvedQuestion, answer = reply.answer)
            }
            if (useAi) {
                val connection = requireAiConnection()
                val request = withContext(Dispatchers.Default) {
                    FinanceAi(connection.protocol).auditRequest(connection.model, reply.answer)
                }
                val response = AndroidAiTransport().complete(connection, request.body)
                val explanation = withContext(Dispatchers.Default) { request.parse(response) }
                if (selection.value.bookId == bookId) {
                    updateTurn(WorkspaceChatMode.LEDGER, turn.id) { it.copy(explanation = explanation) }
                }
            }
        }

    fun askGeneral(question: String) = chatOperation(WorkspaceChatMode.GENERAL, question, 0, true) { turn ->
        val connection = requireAiConnection()
        val provider = connection.endpoint.toString() to connection.model
        if (generalProvider != null && generalProvider != provider) {
            replaceTurns(WorkspaceChatMode.GENERAL, listOf(turn))
        }
        generalProvider = provider
        val messages = _chat.value.general
            .filter { it.response != null }
            .takeLast(5)
            .flatMap {
                listOf(
                    FinanceConversationMessage(FinanceConversationRole.USER, it.question),
                    FinanceConversationMessage(FinanceConversationRole.ASSISTANT, checkNotNull(it.response)),
                )
            } + FinanceConversationMessage(FinanceConversationRole.USER, turn.question)
        val request = withContext(Dispatchers.Default) {
            FinanceAi(connection.protocol).conversationRequest(connection.model, messages)
        }
        val response = AndroidAiTransport().complete(connection, request.body)
        val reply = withContext(Dispatchers.Default) { request.parse(response) }
        updateTurn(WorkspaceChatMode.GENERAL, turn.id) { it.copy(response = reply) }
    }

    fun clearChat(mode: WorkspaceChatMode) {
        if (!_operation.value.busy) replaceTurns(mode, emptyList())
    }

    private suspend fun requireAiConnection() =
        withContext(Dispatchers.IO) { aiConnectionStore.load() }
            ?: throw IllegalArgumentException("请先填写 AI 接口配置。")

    private fun chatOperation(
        mode: WorkspaceChatMode,
        question: String,
        days: Int,
        useAi: Boolean,
        send: suspend (WorkspaceChatTurn) -> Unit,
    ) = perform {
        val text = question.trim()
        val maxLength = if (mode == WorkspaceChatMode.LEDGER) 400 else 2_000
        require(text.length in 1..maxLength) { "请输入 1–$maxLength 个字符。" }
        val turn = WorkspaceChatTurn(++nextChatId, text, days, useAi)
        replaceTurns(mode, (_chat.value.turns(mode) + turn).takeLast(16))
        try {
            send(turn)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            updateTurn(mode, turn.id) { it.copy(error = error.message ?: "暂时无法回答，请重试。") }
        }
    }

    private fun replaceTurns(mode: WorkspaceChatMode, entries: List<WorkspaceChatTurn>) {
        val history = _chat.value
        _chat.value = when (mode) {
            WorkspaceChatMode.LEDGER -> history.copy(ledger = entries)
            WorkspaceChatMode.GENERAL -> history.copy(general = entries)
        }
    }

    private fun updateTurn(mode: WorkspaceChatMode, id: Long, update: (WorkspaceChatTurn) -> WorkspaceChatTurn) {
        replaceTurns(mode, _chat.value.turns(mode).map { if (it.id == id) update(it) else it })
    }

    fun exportActiveBook(uri: Uri) = perform("项目账本已导出") {
        val current = projection.value ?: error("工作台尚未加载。")
        val bookId = selection.value.bookId ?: error("请先选择一个项目账本。")
        require(current.selection.bookId == bookId) { "正在切换账本，请稍后重试。" }
        withContext(Dispatchers.IO) {
            val json = FinanceDataJsonCodec().encode(analytics.scopedSnapshot(current.state, financeData.snapshot(), bookId))
            writeExport(uri, json)
        }
    }

    fun exportConfiguration(uri: Uri) = perform("工作台配置已导出") {
        val state = local.value ?: error("工作台尚未加载。")
        withContext(Dispatchers.IO) { writeExport(uri, FinanceWorkspaceCodec.encode(state)) }
    }

    private fun writeExport(uri: Uri, json: String) {
        val output = appContext.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选文件。")
        output.writer(Charsets.UTF_8).use { it.write(json) }
    }

    fun createCameraCapture(): File = receipts.createCameraCapture()

    fun cameraUri(file: File): Uri = receipts.receiptUri(appContext, file)

    suspend fun loadReceipt(reference: String): Bitmap? = receipts.preview(reference)

    suspend fun readReceiptText(uri: Uri): String = receipts.readText(uri)

    suspend fun netWorth(current: WorkspaceProjection): List<Long> = withContext(Dispatchers.Default) {
        val now = ZonedDateTime.now()
        val cutoffs = (12 downTo 0).map { now.minusWeeks(it.toLong()).toInstant().toEpochMilli() }
        analytics.netWorth(current.state, current.transactions, "CNY", cutoffs).map { it.balanceMinor }
    }

    fun dismissMessage() {
        _operation.value = _operation.value.copy(message = null, error = null)
    }

    private fun project(
        state: FinanceWorkspaceState,
        rows: List<Transaction>,
        categories: List<Category>,
        selected: WorkspaceSelection,
    ): WorkspaceProjection {
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toEpochDay()
        val previous = cachedProjection
        val sameRows = previous?.transactions === rows
        val sameBook = previous?.selection?.bookId == selected.bookId
        val sameLedger = sameRows && previous.state.accounts === state.accounts &&
            previous.state.books === state.books && previous.state.assignments === state.assignments &&
            previous.state.links === state.links
        val sameScope = sameLedger && sameBook
        val scoped = if (sameScope) previous.scopedTransactions
            else analytics.transactionsForBook(state, rows, selected.bookId)
        val sameAutomation = sameScope && previous.state.automation === state.automation
        val result = WorkspaceProjection(
            state = state,
            selection = selected,
            transactions = rows,
            scopedTransactions = scoped,
            categories = categories,
            unmanagedAccountAliases = if (sameRows && previous.state.accounts === state.accounts) {
                previous.unmanagedAccountAliases
            } else {
                val known = state.accounts.mapTo(hashSetOf()) { it.id }
                rows.asSequence().filter { it.deletedAt == null }.mapNotNull(Transaction::accountId)
                    .filter { it !in known }.distinct().take(120).toList()
            },
            summary = if (sameLedger && today == cachedDay && previous.state.checkpoints === state.checkpoints) {
                previous.summary
            } else analytics.summary(state, rows, now),
            forecast = if (sameScope && today == cachedDay && previous.selection == selected &&
                previous.state.plans === state.plans
            ) previous.forecast else analytics.forecastForBook(state, rows, now, today, selected.forecastDays, selected.scenario, selected.bookId),
            inbox = if (sameAutomation) previous.inbox
                else automation.reviewItems(state.automation, selected.bookId, scoped),
            ruleSuggestions = if (sameAutomation && previous.categories === categories) {
                previous.ruleSuggestions
            } else {
                val included = scoped.mapTo(hashSetOf()) { it.id }
                automation.ruleSuggestions(state.automation, selected.bookId, rows.filter { it.id in included }, categories)
            },
        )
        cachedProjection = result
        cachedDay = today
        return result
    }

    private fun update(message: String, changes: suspend (FinanceWorkspaceState) -> FinanceWorkspaceState) =
        perform(message) {
            val current = local.value ?: error("工作台尚未加载。")
            persist(changes(current))
        }

    private suspend fun persist(state: FinanceWorkspaceState) {
        store.save(state)
        local.value = state
    }

    private fun perform(message: String? = null, block: suspend () -> Unit) {
        if (_operation.value.busy) return
        _operation.value = _operation.value.copy(busy = true, message = null, error = null)
        viewModelScope.launch {
            try {
                block()
                _operation.value = _operation.value.copy(message = message)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _operation.value = _operation.value.copy(error = error.message ?: "操作失败，请重试。")
            } finally {
                _operation.value = _operation.value.copy(busy = false)
            }
        }
    }
}
