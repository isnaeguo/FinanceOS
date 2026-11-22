package com.financeos.shared.workspace

import com.financeos.shared.data.transfer.StableRowId
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.CategoryType
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

@Serializable
enum class WorkspaceInboxKind {
    RECEIPT,
    REFUND,
    REIMBURSEMENT,
    WARRANTY,
    RENEWAL,
    UNCATEGORIZED,
    DUPLICATE,
    PRICE_INCREASE,
}

@Serializable
data class WorkspaceInboxItem @Throws(IllegalArgumentException::class) constructor(
    val id: String,
    val bookId: String?,
    val kind: WorkspaceInboxKind,
    val title: String,
    val note: String = "",
    val dueAtEpochMillis: Long = 0,
    val amountMinor: Long = 0,
    val transactionIds: List<String> = emptyList(),
    val attachmentName: String? = null,
    val attachmentReference: String? = null,
    val completed: Boolean = false,
) {
    init {
        require(id.isNotBlank() && id.length <= 256) { "待办编号无效。" }
        require(title.isNotBlank() && title.length <= 160) { "标题需为 1–160 个字符。" }
        require(note.length <= 32_000) { "识别文本或备注过长。" }
        require(dueAtEpochMillis >= 0 && amountMinor >= 0) { "到期时间或金额无效。" }
        require(transactionIds.size <= 50 && transactionIds.distinct().size == transactionIds.size &&
            transactionIds.none(String::isBlank)) { "关联流水无效。" }
        require(attachmentName == null || attachmentName.length <= 255) { "附件名称太长。" }
        require(attachmentReference == null ||
            (attachmentReference.isNotBlank() && attachmentReference.length <= 256 &&
                attachmentReference.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_." } &&
                attachmentReference != "." && attachmentReference != "..")) { "附件引用无效。" }
    }
}

@Serializable
data class WorkspaceRule @Throws(IllegalArgumentException::class) constructor(
    val id: String,
    val bookId: String?,
    val name: String,
    val noteContains: String,
    val categoryId: String,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank() && id.length <= 256 && categoryId.isNotBlank()) { "规则编号或分类无效。" }
        require(name.isNotBlank() && name.length <= 80) { "规则名称需为 1–80 个字符。" }
        require(noteContains.trim().length in 1..128) { "匹配关键词需为 1–128 个字符。" }
    }
}

@Serializable
data class WorkspaceAutomationState @Throws(IllegalArgumentException::class) constructor(
    val rules: List<WorkspaceRule> = emptyList(),
    val items: List<WorkspaceInboxItem> = emptyList(),
    val dismissedSignals: List<String> = emptyList(),
) {
    init {
        require(rules.size <= 500 && rules.map(WorkspaceRule::id).distinct().size == rules.size) {
            "分类规则太多或规则编号重复。"
        }
        require(items.size <= 10_000 && items.map(WorkspaceInboxItem::id).distinct().size == items.size &&
            items.none { it.id.startsWith(SIGNAL_PREFIX) || it.kind in signalKinds }) {
            "收件箱条目过多或包含无效条目。"
        }
        require(dismissedSignals.size <= MAX_DISMISSED_SIGNALS &&
            dismissedSignals.all(::isSignalId) && dismissedSignals.distinct().size == dismissedSignals.size) {
            "已处理提醒记录无效。"
        }
    }
}

data class WorkspaceRuleSuggestion(
    val transaction: Transaction,
    val categoryId: String,
    val ruleId: String,
)

/** Inbox signals and categorization rules are previews. Only the caller may write confirmed changes. */
class WorkspaceAutomation {
    fun emptyState(): WorkspaceAutomationState = WorkspaceAutomationState()

    fun reviewItems(
        state: WorkspaceAutomationState,
        bookId: String?,
        transactions: List<Transaction>,
    ): List<WorkspaceInboxItem> {
        val manual = state.items.filter { it.bookId == bookId && !it.completed }
        val dismissed = state.dismissedSignals.toHashSet()
        val automatic = ledgerSignals(bookId, transactions).filter { it.id !in dismissed }.take(MAX_VISIBLE_SIGNALS)
        return (manual + automatic).sortedWith(
            compareBy<WorkspaceInboxItem> { if (it.dueAtEpochMillis > 0) it.dueAtEpochMillis else Long.MAX_VALUE }
                .thenBy { it.kind in signalKinds }
                .thenBy { it.title }
                .thenBy { it.id },
        )
    }

    @Throws(IllegalArgumentException::class)
    fun saveItem(state: WorkspaceAutomationState, item: WorkspaceInboxItem): WorkspaceAutomationState {
        require(item.kind !in signalKinds && !item.id.startsWith(SIGNAL_PREFIX)) { "系统提醒只能标记已处理。" }
        val existing = state.items.indexOfFirst { it.id == item.id }
        val items = if (existing < 0) state.items + item else {
            state.items.toMutableList().apply { set(existing, item) }
        }
        return state.copy(items = items)
    }

    @Throws(IllegalArgumentException::class)
    fun completeItem(state: WorkspaceAutomationState, id: String): WorkspaceAutomationState {
        if (id.startsWith(SIGNAL_PREFIX)) return dismissSignal(state, id)
        val item = state.items.firstOrNull { it.id == id } ?: return state
        return saveItem(state, item.copy(completed = true))
    }

    @Throws(IllegalArgumentException::class)
    fun removeItem(state: WorkspaceAutomationState, id: String): WorkspaceAutomationState =
        if (id.startsWith(SIGNAL_PREFIX)) {
            dismissSignal(state, id)
        } else {
            state.copy(items = state.items.filterNot { it.id == id })
        }

    @Throws(IllegalArgumentException::class)
    fun saveRule(state: WorkspaceAutomationState, rule: WorkspaceRule): WorkspaceAutomationState {
        val normalized = rule.copy(name = rule.name.trim(), noteContains = rule.noteContains.trim())
        val existing = state.rules.indexOfFirst { it.id == rule.id }
        val rules = if (existing < 0) state.rules + normalized else {
            state.rules.toMutableList().apply { set(existing, normalized) }
        }
        return state.copy(rules = rules)
    }

    @Throws(IllegalArgumentException::class)
    fun removeRule(state: WorkspaceAutomationState, id: String): WorkspaceAutomationState =
        state.copy(rules = state.rules.filterNot { it.id == id })

    fun ruleSuggestions(
        state: WorkspaceAutomationState,
        bookId: String?,
        transactions: List<Transaction>,
        categories: List<Category>,
    ): List<WorkspaceRuleSuggestion> {
        val targets = categories.filter { it.deletedAt == null && it.id != "system-other" }.associateBy(Category::id)
        val rules = state.rules.asSequence()
            .filter { it.bookId == bookId && it.enabled }
            .mapNotNull { rule -> targets[rule.categoryId]?.let { rule to it } }
            .toList()
        if (rules.isEmpty()) return emptyList()
        return transactions.asSequence()
            .filter { it.deletedAt == null && it.categoryId == "system-other" && !it.note.isNullOrBlank() }
            .distinctBy(Transaction::id)
            .sortedWith(compareByDescending<Transaction> { it.dateTime }.thenBy { it.id })
            .mapNotNull { transaction ->
                rules.firstOrNull { (rule, category) ->
                    category.accepts(transaction.type) &&
                        transaction.note.orEmpty().contains(rule.noteContains, ignoreCase = true)
                }?.let { (rule, _) -> WorkspaceRuleSuggestion(transaction, rule.categoryId, rule.id) }
            }
            .take(200)
            .toList()
    }
}

internal fun ledgerSignals(bookId: String?, transactions: List<Transaction>): Sequence<WorkspaceInboxItem> {
    val active = transactions.asSequence().filter { it.deletedAt == null }
        .distinctBy(Transaction::id)
        .sortedWith(compareByDescending<Transaction> { it.dateTime }.thenBy { it.id })
        .toList()
    return sequence {
        val merchantGroups = active.mapNotNull { transaction ->
            transaction.note?.trim()?.lowercase()?.takeIf { it.length in 2..160 }?.let { merchant ->
                MerchantKey(transaction.accountId, transaction.type, merchant) to transaction
            }
        }.groupBy({ it.first }, { it.second })

        // Require the same account, cleaned note, direction and amount within ten minutes.
        // Mark a pair for review without changing or merging either entry.
        for (group in merchantGroups.values) {
            val lastByAmount = mutableMapOf<Long, Transaction>()
            for (row in group) {
                val newer = lastByAmount.put(row.amount, row) ?: continue
                if (newer.dateTime - row.dateTime <= DUPLICATE_WINDOW) {
                    yield(signal(bookId, WorkspaceInboxKind.DUPLICATE, "疑似重复扣款或入账", listOf(newer, row)))
                }
            }
        }

        // Require two equal prior expenses with comparable intervals before showing a price change.
        for ((key, group) in merchantGroups) {
            if (key.type != TransactionType.EXPENSE) continue
            val current = group.getOrNull(0) ?: continue
            val previous = group.getOrNull(1) ?: continue
            val earlier = group.getOrNull(2) ?: continue
            val currentInterval = current.dateTime - previous.dateTime
            val previousInterval = previous.dateTime - earlier.dateTime
            if (current.amount > previous.amount && previous.amount == earlier.amount &&
                currentInterval in MIN_RECURRING_INTERVAL..MAX_RECURRING_INTERVAL &&
                previousInterval in MIN_RECURRING_INTERVAL..MAX_RECURRING_INTERVAL &&
                (currentInterval - previousInterval).absoluteValue <= RECURRING_INTERVAL_TOLERANCE) {
                yield(signal(
                    bookId,
                    WorkspaceInboxKind.PRICE_INCREASE,
                    "周期支出疑似涨价",
                    listOf(current, previous, earlier),
                ))
            }
        }

        for (transaction in active) {
            if (transaction.categoryId == "system-other") {
                yield(signal(bookId, WorkspaceInboxKind.UNCATEGORIZED, "待分类流水", listOf(transaction)))
            }
        }
    }
}

private fun signal(
    bookId: String?,
    kind: WorkspaceInboxKind,
    title: String,
    transactions: List<Transaction>,
): WorkspaceInboxItem {
    val ids = transactions.map(Transaction::id)
    val id = SIGNAL_PREFIX + StableRowId.generateStatementId(listOf(bookId.orEmpty(), kind.name) + ids.sorted())
    return WorkspaceInboxItem(
        id = id,
        bookId = bookId,
        kind = kind,
        title = title,
        note = transactions.first().note.orEmpty().take(160),
        amountMinor = transactions.first().amount,
        transactionIds = ids,
    )
}

private fun dismissSignal(state: WorkspaceAutomationState, id: String): WorkspaceAutomationState {
    require(isSignalId(id)) { "提醒编号无效。" }
    if (id in state.dismissedSignals) return state
    return state.copy(dismissedSignals = (state.dismissedSignals + id).takeLast(MAX_DISMISSED_SIGNALS))
}

private fun isSignalId(id: String): Boolean = id.startsWith(SIGNAL_PREFIX) && id.length <= 128

private fun Category.accepts(type: TransactionType): Boolean =
    this.type == CategoryType.COMMON || this.type.name == type.name

private data class MerchantKey(val accountId: String?, val type: TransactionType, val note: String)

private const val SIGNAL_PREFIX = "signal-"
private const val MAX_DISMISSED_SIGNALS = 10_000
private const val MAX_VISIBLE_SIGNALS = 200
private val signalKinds = setOf(WorkspaceInboxKind.UNCATEGORIZED, WorkspaceInboxKind.DUPLICATE, WorkspaceInboxKind.PRICE_INCREASE)

private val DUPLICATE_WINDOW = 10.minutes
private val MIN_RECURRING_INTERVAL = 6.days
private val MAX_RECURRING_INTERVAL = 40.days
private val RECURRING_INTERVAL_TOLERANCE = 4.days
