package com.financeos.shared.workspace

import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType

/** Immutable changes; clients persist the returned state before showing a successful write. */
class FinanceWorkspace {
    @Throws(IllegalArgumentException::class)
    fun saveAccount(state: FinanceWorkspaceState, account: WorkspaceAccount): FinanceWorkspaceState {
        val normalized = account.copy(name = account.name.trim(), currency = account.currency.trim().uppercase())
        validateLabel(normalized.id, normalized.name, normalized.currency)
        val previous = state.accounts.firstOrNull { it.id == normalized.id }
        if (previous != null && previous.currency != normalized.currency) {
            require(state.assignments.none { it.accountId == normalized.id } && state.checkpoints.none { it.accountId == normalized.id }) {
                "已有流水或对账记录的账户不能直接更改币种。"
            }
        }
        return state.copy(accounts = state.accounts.replacing(normalized, WorkspaceAccount::id))
    }

    @Throws(IllegalArgumentException::class)
    fun saveBook(state: FinanceWorkspaceState, book: WorkspaceBook): FinanceWorkspaceState {
        val normalized = book.copy(name = book.name.trim(), currency = book.currency.trim().uppercase())
        validateLabel(normalized.id, normalized.name, normalized.currency)
        val previous = state.books.firstOrNull { it.id == normalized.id }
        if (previous != null && previous.currency != normalized.currency) {
            require(state.assignments.none { it.bookId == normalized.id || it.splits.any { split -> split.bookId == normalized.id } }) {
                "已有流水分配的子账本不能直接更改币种。"
            }
        }
        return state.copy(books = state.books.replacing(normalized, WorkspaceBook::id))
    }

    @Throws(IllegalArgumentException::class)
    fun saveAssignment(
        state: FinanceWorkspaceState,
        assignment: WorkspaceAssignment,
        transactions: List<Transaction>,
    ): FinanceWorkspaceState {
        val transaction = transactions.firstOrNull { it.id == assignment.transactionId && it.deletedAt == null }
            ?: throw IllegalArgumentException("原流水已删除或不存在。")
        val normalized = assignment.copy(originalCurrency = assignment.originalCurrency?.trim()?.uppercase())
        validateAssignment(normalized, state.accounts.associateBy { it.id }, state.books.associateBy { it.id })
        if (normalized.splits.isNotEmpty()) {
            require(normalized.splits.sumMinor(WorkspaceSplit::amountMinor) == transaction.amount) {
                "拆分合计必须等于流水的人民币记账金额。"
            }
        }
        if (normalized.originalCurrency == BASE_CURRENCY) {
            require(normalized.originalAmountMinor == transaction.amount) { "人民币原金额必须等于记账金额。" }
        }
        return state.copy(assignments = state.assignments.replacing(normalized, WorkspaceAssignment::transactionId))
    }

    @Throws(IllegalArgumentException::class)
    fun saveLink(
        state: FinanceWorkspaceState,
        link: WorkspaceLink,
        transactions: List<Transaction>,
    ): FinanceWorkspaceState {
        val source = transactions.firstOrNull { it.id == link.sourceId && it.deletedAt == null }
        val target = transactions.firstOrNull { it.id == link.targetId && it.deletedAt == null }
        require(source != null && target != null) { "关联的两笔流水必须仍存在。" }
        require(linkMatches(link, source, target)) {
            when (link.kind) {
                WorkspaceLinkKind.DUPLICATE -> "重复流水必须同方向且记账金额一致。"
                WorkspaceLinkKind.TRANSFER -> "转账须关联人民币记账金额一致的支出和收入，手续费请单独记账。"
                WorkspaceLinkKind.REFUND -> "退款必须从收入流水指向原支出流水。"
            }
        }
        if (link.kind == WorkspaceLinkKind.TRANSFER) {
            val assignments = state.assignments.associateBy { it.transactionId }
            require(transferAccountsMatch(
                assignments[source.id]?.accountId ?: source.accountId,
                assignments[target.id]?.accountId ?: target.accountId,
            )) { "转账的两笔流水须来自不同账户。" }
        }
        val links = state.links.replacing(link, WorkspaceLink::sourceId)
        validateLinks(links)
        return state.copy(links = links)
    }

    fun removeLink(state: FinanceWorkspaceState, sourceId: String): FinanceWorkspaceState =
        state.copy(links = state.links.filterNot { it.sourceId == sourceId })

    @Throws(IllegalArgumentException::class)
    fun saveCheckpoint(state: FinanceWorkspaceState, checkpoint: WorkspaceCheckpoint): FinanceWorkspaceState {
        validateId(checkpoint.id)
        require(state.accounts.any { it.id == checkpoint.accountId }) { "请选择要对账的账户。" }
        return state.copy(checkpoints = state.checkpoints.replacing(checkpoint, WorkspaceCheckpoint::id))
    }

    @Throws(IllegalArgumentException::class)
    fun savePlan(state: FinanceWorkspaceState, plan: WorkspacePlan): FinanceWorkspaceState {
        val normalized = plan.copy(name = plan.name.trim(), scenario = plan.scenario?.trim()?.ifEmpty { null })
        validatePlan(normalized, state.accounts.mapTo(hashSetOf()) { it.id }, state.books.mapTo(hashSetOf()) { it.id })
        return state.copy(plans = state.plans.replacing(normalized, WorkspacePlan::id))
    }

    fun removePlan(state: FinanceWorkspaceState, id: String): FinanceWorkspaceState =
        state.copy(plans = state.plans.filterNot { it.id == id })

    fun saveAutomation(state: FinanceWorkspaceState, automation: WorkspaceAutomationState): FinanceWorkspaceState =
        state.copy(automation = automation)
}

internal const val BASE_CURRENCY = "CNY"
internal const val FIRST_EPOCH_DAY = -719_162L
internal const val LAST_EPOCH_DAY = 2_932_896L

internal fun validateWorkspaceState(state: FinanceWorkspaceState) {
    val accounts = state.accounts.associateBy { it.id }
    val books = state.books.associateBy { it.id }
    require(accounts.size == state.accounts.size && books.size == state.books.size) { "工作区包含重复账户或子账本。" }
    state.accounts.forEach { validateLabel(it.id, it.name, it.currency) }
    state.books.forEach { validateLabel(it.id, it.name, it.currency) }
    state.assignments.requireUnique(WorkspaceAssignment::transactionId, "流水归属")
    state.assignments.forEach { validateAssignment(it, accounts, books) }
    validateLinks(state.links)
    state.checkpoints.requireUnique(WorkspaceCheckpoint::id, "对账")
    state.checkpoints.forEach {
        validateId(it.id)
        require(it.accountId in accounts) { "对账记录引用了不存在的账户。" }
    }
    state.plans.requireUnique(WorkspacePlan::id, "计划")
    state.plans.forEach { validatePlan(it, accounts.keys, books.keys) }
}

private fun validateLabel(id: String, name: String, currency: String) {
    validateId(id)
    validateName(name)
    validateCurrency(currency)
}

private fun validateId(id: String) {
    require(id.isNotBlank() && id.length <= 256 && id.none(Char::isISOControl)) { "记录标识无效。" }
}

private fun validateName(name: String) {
    require(name.isNotBlank() && name.length <= 100 && name.none(Char::isISOControl)) { "请输入 1 至 100 个字符的名称。" }
}

private fun validateCurrency(currency: String) {
    require(currency.length == 3 && currency.all { it in 'A'..'Z' }) { "币种应为三位字母代码，例如 CNY、USD 或 JPY。" }
}

private fun validateAssignment(
    assignment: WorkspaceAssignment,
    accounts: Map<String, WorkspaceAccount>,
    books: Map<String, WorkspaceBook>,
) {
    validateId(assignment.transactionId)
    require(assignment.accountId == null || assignment.accountId in accounts) { "请选择有效账户。" }
    require(assignment.bookId == null || assignment.bookId in books) { "请选择有效子账本。" }
    val currency = assignment.originalCurrency
    val originalAmount = assignment.originalAmountMinor
    require((currency == null) == (originalAmount == null)) { "请同时填写原币种和原金额。" }
    if (currency != null) {
        validateCurrency(currency)
        require(originalAmount != null && originalAmount > 0) { "原金额必须大于零。" }
    }
    val accountCurrency = assignment.accountId?.let(accounts::get)?.currency
    val bookCurrency = assignment.bookId?.let(books::get)?.currency
    require(accountCurrency == null || accountCurrency == BASE_CURRENCY || accountCurrency == currency) {
        "原币种应与账户币种一致。"
    }
    require(bookCurrency == null || bookCurrency == BASE_CURRENCY || bookCurrency == currency) {
        "原币种应与子账本币种一致。"
    }
    if (assignment.splits.isNotEmpty()) {
        require(assignment.bookId == null) { "整笔归属和拆分不能同时设置。" }
        assignment.splits.requireUnique(WorkspaceSplit::bookId, "拆分")
        require(assignment.splits.all { it.amountMinor > 0 && books[it.bookId]?.currency == BASE_CURRENCY }) {
            "拆分金额应大于零，且拆分目标必须是人民币子账本。"
        }
    }
}

private fun validatePlan(plan: WorkspacePlan, accounts: Set<String>, books: Set<String>) {
    validateId(plan.id)
    validateName(plan.name)
    require(plan.amountMinor > 0) { "计划金额必须大于零。" }
    require(plan.firstEpochDay in FIRST_EPOCH_DAY..LAST_EPOCH_DAY) { "计划日期超出支持范围。" }
    require(plan.occurrences >= 0) { "计划次数不能为负数。" }
    require(plan.accountId == null || plan.accountId in accounts) { "计划引用了不存在的账户。" }
    require(plan.bookId == null || plan.bookId in books) { "计划引用了不存在的子账本。" }
    plan.scenario?.let(::validateName)
}

private fun validateLinks(links: List<WorkspaceLink>) {
    links.requireUnique(WorkspaceLink::sourceId, "流水关联")
    val duplicates = links.filter { it.kind == WorkspaceLinkKind.DUPLICATE }.mapTo(hashSetOf()) { it.sourceId }
    val transferEndpoints = hashSetOf<String>()
    for (link in links) {
        validateId(link.sourceId)
        validateId(link.targetId)
        require(link.sourceId != link.targetId) { "流水不能关联到自身。" }
        require(link.targetId !in duplicates) { "请直接选择要保留的原流水，避免重复关联链。" }
        if (link.kind == WorkspaceLinkKind.TRANSFER) {
            require(transferEndpoints.add(link.sourceId) && transferEndpoints.add(link.targetId)) {
                "同一笔流水只能参与一组转账。"
            }
        }
    }
    require(links.none {
        it.kind == WorkspaceLinkKind.REFUND && (it.sourceId in transferEndpoints || it.targetId in transferEndpoints)
    }) { "转账流水不能同时标记为退款或原支出。" }
}

internal fun linkMatches(link: WorkspaceLink, source: Transaction, target: Transaction): Boolean =
    when (link.kind) {
        WorkspaceLinkKind.DUPLICATE -> source.type == target.type && source.amount == target.amount
        WorkspaceLinkKind.TRANSFER -> source.type == TransactionType.EXPENSE && target.type == TransactionType.INCOME &&
            source.amount == target.amount
        WorkspaceLinkKind.REFUND -> source.type == TransactionType.INCOME && target.type == TransactionType.EXPENSE
    }

internal fun transferAccountsMatch(source: String?, target: String?): Boolean =
    source == null || target == null || source != target

private fun <T> List<T>.replacing(value: T, key: (T) -> String): List<T> {
    val id = key(value)
    val index = indexOfFirst { key(it) == id }
    if (index < 0) return this + value
    return toMutableList().also { it[index] = value }
}

private inline fun <T> List<T>.requireUnique(key: (T) -> String, label: String) {
    val seen = hashSetOf<String>()
    require(all { seen.add(key(it)) }) { "工作区包含重复$label。" }
}

internal inline fun <T> Iterable<T>.sumMinor(amount: (T) -> Long): Long = fold(0L) { total, entry ->
    addMinor(total, amount(entry))
}

internal fun addMinor(left: Long, right: Long): Long {
    val total = left + right
    require(((left xor total) and (right xor total)) >= 0L) { "金额合计超出可表示的范围。" }
    return total
}

internal fun subtractMinor(left: Long, right: Long): Long {
    val result = left - right
    require(((left xor right) and (left xor result)) >= 0L) { "金额差额超出可表示的范围。" }
    return result
}
