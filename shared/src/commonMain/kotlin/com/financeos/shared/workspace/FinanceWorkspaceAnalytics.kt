package com.financeos.shared.workspace

import com.financeos.shared.data.transfer.StableRowId
import com.financeos.shared.domain.model.FinanceDataSnapshot
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType

class FinanceWorkspaceAnalytics {
    /** Book portions remain in the ledger's CNY units for use with existing reports and AI. */
    @Throws(IllegalArgumentException::class)
    fun transactionsForBook(
        state: FinanceWorkspaceState,
        transactions: List<Transaction>,
        bookId: String?,
    ): List<Transaction> {
        require(bookId == null || state.books.any { it.id == bookId }) { "子账本不存在。" }
        return WorkspaceLedger(state, transactions).externalTransactions(bookId).toList()
    }

    /** Historical totals include registered accounts in one denomination, with missing original values disclosed. */
    @Throws(IllegalArgumentException::class)
    fun netWorth(
        state: FinanceWorkspaceState,
        transactions: List<Transaction>,
        currency: String,
        cutoffs: List<Long>,
    ): List<WorkspaceNetWorthPoint> {
        require(cutoffs.size in 1..90 && cutoffs.zipWithNext().all { (first, next) -> first < next }) {
            "净资产时间点必须按顺序提供，最多 90 个。"
        }
        val accounts = state.accounts.filter { it.currency == currency }
        val accountIds = accounts.mapTo(hashSetOf()) { it.id }
        val total = MinorAccumulator()
        accounts.forEach { total.add(it.openingBalanceMinor) }
        val changes = Array(cutoffs.size) { MinorAccumulator() }
        val missing = IntArray(cutoffs.size)
        if (accounts.isNotEmpty()) {
            val ledger = WorkspaceLedger(state, transactions)
            for (entry in ledger.accountTransactions) {
                val account = ledger.assignment(entry.id)?.accountId ?: entry.accountId
                if (account == null || account !in accountIds) continue
                val time = entry.dateTime.toEpochMilliseconds()
                val result = cutoffs.binarySearch(time)
                val index = if (result >= 0) result else -result - 1
                if (index == cutoffs.size) continue
                val amount = ledger.amountInCurrency(entry, currency)
                if (amount == null) missing[index]++ else changes[index].add(entry.signed(amount))
            }
        }
        var missingCount = 0
        return cutoffs.mapIndexed { index, cutoff ->
            total.add(changes[index])
            missingCount += missing[index]
            WorkspaceNetWorthPoint(cutoff, total.toLong(), missingCount)
        }
    }

    /**
     * Export an independently importable, CNY-only ledger. Account IDs, private notes, unrelated
     * categories, and global budgets are excluded. Scoped IDs never reuse original transaction IDs.
     */
    @Throws(IllegalArgumentException::class)
    fun scopedSnapshot(
        state: FinanceWorkspaceState,
        snapshot: FinanceDataSnapshot,
        bookId: String,
    ): FinanceDataSnapshot {
        val scoped = transactionsForBook(state, snapshot.transactions, bookId).map { entry ->
            val scopedId = StableRowId.generateStatementId(listOf("workspace-book", bookId, entry.id))
            entry.copy(id = "book-" + scopedId.removePrefix("statement-"), accountId = null, note = null)
        }
        val referenced = scoped.mapTo(hashSetOf()) { it.categoryId }
        val categories = snapshot.categories.filter { it.id in referenced }
        require(categories.mapTo(hashSetOf()) { it.id } == referenced) { "子账本流水引用了不存在的分类。" }
        return FinanceDataSnapshot(scoped, categories, emptyList())
    }

    @Throws(IllegalArgumentException::class)
    fun summary(
        state: FinanceWorkspaceState,
        transactions: List<Transaction>,
        nowEpochMillis: Long,
    ): WorkspaceSummary {
        val ledger = WorkspaceLedger(state, transactions)
        val checkpoints = state.checkpoints.groupBy { it.accountId }
        val accounts = state.accounts.associate { it.id to AccountTimeline(it, checkpoints[it.id].orEmpty()) }
        var unassigned = 0
        for (entry in ledger.accountTransactions) {
            val time = entry.dateTime.toEpochMilliseconds()
            val accountId = ledger.assignment(entry.id)?.accountId ?: entry.accountId
            val account = accountId?.let(accounts::get)
            if (account == null) {
                if (time <= nowEpochMillis) unassigned++
                continue
            }
            val amount = ledger.amountInCurrency(entry, account.account.currency)
            account.record(time, amount?.let(entry::signed), nowEpochMillis)
        }

        val bookTotals = state.books.associate { it.id to BookTotals(it) }
        if (bookTotals.isNotEmpty()) {
            for (entry in ledger.externalTransactions(null)) {
                if (entry.dateTime.toEpochMilliseconds() > nowEpochMillis) continue
                val book = ledger.bookIdFor(entry)?.let(bookTotals::get)
                if (book != null) {
                    book.record(entry.type, ledger.amountInCurrency(entry, book.book.currency))
                } else {
                    for (split in ledger.validSplits(entry)) bookTotals[split.bookId]?.record(entry.type, split.amountMinor)
                }
            }
        }
        return WorkspaceSummary(
            accounts.values.map(AccountTimeline::balance), bookTotals.values.map(BookTotals::result),
            accounts.values.flatMap(AccountTimeline::reconcile).sortedByDescending { it.asOfEpochMillis },
            unassigned, ledger.unresolvedLinkCount,
        )
    }

    @Throws(IllegalArgumentException::class)
    fun forecast(
        state: FinanceWorkspaceState,
        transactions: List<Transaction>,
        nowEpochMillis: Long,
        startEpochDay: Long,
        days: Int,
        scenario: String? = null,
    ): WorkspaceForecast = forecastForBook(state, transactions, nowEpochMillis, startEpochDay, days, scenario, null)

    @Throws(IllegalArgumentException::class)
    fun forecastForBook(
        state: FinanceWorkspaceState,
        transactions: List<Transaction>,
        nowEpochMillis: Long,
        startEpochDay: Long,
        days: Int,
        scenario: String?,
        bookId: String?,
    ): WorkspaceForecast {
        require(days in 1..90 && startEpochDay in FIRST_EPOCH_DAY..(LAST_EPOCH_DAY - days + 1)) {
            "预测须为有效起始日期及 1 至 90 天。"
        }
        require(bookId == null || state.books.any { it.id == bookId }) { "子账本不存在。" }
        val initial = MinorAccumulator()
        if (bookId == null) {
            state.accounts.filter { it.currency == BASE_CURRENCY }.forEach { initial.add(it.openingBalanceMinor) }
        }
        WorkspaceLedger(state, transactions).externalTransactions(bookId).forEach { entry ->
            if (entry.dateTime.toEpochMilliseconds() <= nowEpochMillis) initial.add(entry.signed(entry.amount))
        }
        val scenarioName = scenario?.trim()?.ifEmpty { null }
        val schedules = state.plans.asSequence().filter { plan ->
            (plan.scenario == null || plan.scenario == scenarioName) && (bookId == null || plan.bookId == bookId)
        }.map(::WorkspaceSchedule).toList()
        val opening = initial.toLong()
        var running = opening
        var low = opening
        var income = 0L
        var expense = 0L
        val points = List(days) { offset ->
            val day = startEpochDay + offset
            val date = CivilDate.fromEpochDay(day)
            var dayIn = 0L
            var dayOut = 0L
            for (schedule in schedules) {
                if (!schedule.occursOn(day, date)) continue
                val plan = schedule.plan
                when (plan.type) {
                    TransactionType.INCOME -> dayIn = addMinor(dayIn, plan.amountMinor)
                    TransactionType.EXPENSE -> dayOut = addMinor(dayOut, plan.amountMinor)
                }
            }
            income = addMinor(income, dayIn)
            expense = addMinor(expense, dayOut)
            running = addMinor(running, dayIn - dayOut)
            low = minOf(low, running)
            WorkspaceForecastPoint(day, dayIn, dayOut, running)
        }
        return WorkspaceForecast(opening, income, expense, running, low, points)
    }
}

private class WorkspaceLedger(state: FinanceWorkspaceState, transactions: List<Transaction>) {
    private val live = buildMap(transactions.size) {
        for (entry in transactions) if (entry.deletedAt == null) put(entry.id, entry)
    }
    private val assignments = state.assignments.associateBy { it.transactionId }
    private val duplicates = hashSetOf<String>()
    private val transfers = hashSetOf<String>()
    private val refunds = mutableMapOf<String, Transaction>()
    val unresolvedLinkCount: Int

    init {
        var unresolved = 0
        for (link in state.links) {
            val source = live[link.sourceId]
            val target = live[link.targetId]
            if (source == null || target == null || !linkMatches(link, source, target)) {
                unresolved++
                continue
            }
            if (link.kind == WorkspaceLinkKind.TRANSFER &&
                !transferAccountsMatch(assignment(source.id)?.accountId ?: source.accountId, assignment(target.id)?.accountId ?: target.accountId)
            ) {
                unresolved++
                continue
            }
            when (link.kind) {
                WorkspaceLinkKind.DUPLICATE -> duplicates += source.id
                WorkspaceLinkKind.TRANSFER -> {
                    transfers += source.id
                    transfers += target.id
                }
                WorkspaceLinkKind.REFUND -> refunds[source.id] = target
            }
        }
        unresolvedLinkCount = unresolved
    }

    val accountTransactions: Sequence<Transaction> get() = live.values.asSequence().filterNot { it.id in duplicates }

    fun assignment(transactionId: String): WorkspaceAssignment? = assignments[transactionId]

    fun bookIdFor(entry: Transaction): String? {
        val own = assignment(entry.id)
        if (!own?.splits.isNullOrEmpty()) return null
        return own?.bookId ?: refunds[entry.id]?.let { assignment(it.id)?.bookId }
    }

    fun validSplits(entry: Transaction): List<WorkspaceSplit> {
        val own = assignment(entry.id)
        if (own?.bookId != null || !own?.splits.isNullOrEmpty()) return storedSplits(entry)
        val original = refunds[entry.id] ?: return emptyList()
        var prefix = 0L
        var allocated = 0L
        return storedSplits(original).sortedBy { it.bookId }.mapNotNull { split ->
            prefix += split.amountMinor
            val total = multiplyDivide(entry.amount, prefix, original.amount)
            val portion = total - allocated
            allocated = total
            if (portion == 0L) null else WorkspaceSplit(split.bookId, portion)
        }
    }

    private fun storedSplits(entry: Transaction): List<WorkspaceSplit> {
        val splits = assignment(entry.id)?.splits.orEmpty()
        var remaining = entry.amount
        for (split in splits) {
            // An amount edited since allocation must be reviewed rather than counted with stale portions.
            if (split.amountMinor <= 0 || split.amountMinor > remaining) return emptyList()
            remaining -= split.amountMinor
        }
        return if (remaining == 0L) splits else emptyList()
    }

    fun amountInCurrency(entry: Transaction, currency: String): Long? =
        if (currency == BASE_CURRENCY) {
            entry.amount
        } else {
            assignment(entry.id)?.takeIf { it.originalCurrency == currency }?.originalAmountMinor
        }

    fun externalTransactions(bookId: String?): Sequence<Transaction> = accountTransactions
        .filterNot { it.id in transfers }
        .mapNotNull { entry ->
            if (bookId == null) return@mapNotNull entry
            if (bookIdFor(entry) == bookId) return@mapNotNull entry
            val allocated = validSplits(entry).firstOrNull { it.bookId == bookId } ?: return@mapNotNull null
            entry.copy(amount = allocated.amountMinor, note = null, accountId = null)
        }
}

/** floor(amount × share / total) without multiplying two 64-bit minor amounts. */
private fun multiplyDivide(amount: Long, share: Long, total: Long): Long {
    if (share == total) return amount
    val divisor = total.toULong()
    var multiplier = share.toULong()
    var quotient = amount.toULong() / divisor
    var remainder = amount.toULong() % divisor
    var allocated = 0UL
    var carried = 0UL
    while (multiplier != 0UL) {
        if ((multiplier and 1UL) == 1UL) {
            val sum = carried + remainder
            allocated += quotient + sum / divisor
            carried = sum % divisor
        }
        multiplier = multiplier shr 1
        if (multiplier != 0UL) {
            val doubled = remainder * 2UL
            quotient = quotient * 2UL + doubled / divisor
            remainder = doubled % divisor
        }
    }
    return allocated.toLong()
}

private fun Transaction.signed(minor: Long): Long = if (type == TransactionType.INCOME) minor else -minor

private data class AccountPosting(val time: Long, val amount: Long?)

private class AccountTimeline(val account: WorkspaceAccount, private val checkpoints: List<WorkspaceCheckpoint>) {
    private val current = AccountRunningBalance(account.openingBalanceMinor)
    private val history = if (checkpoints.isEmpty()) null else mutableListOf<AccountPosting>()

    fun record(time: Long, amount: Long?, now: Long) {
        if (time <= now) current.add(amount)
        history?.add(AccountPosting(time, amount))
    }

    fun balance() = WorkspaceAccountBalance(
        account.id, account.name, account.currency, current.balance(), current.count, current.unvalued,
    )

    fun reconcile(): List<WorkspaceReconciliation> {
        val postings = history ?: return emptyList()
        postings.sortBy { it.time }
        val balance = AccountRunningBalance(account.openingBalanceMinor)
        var position = 0
        return checkpoints.sortedBy { it.asOfEpochMillis }.map { checkpoint ->
            while (position < postings.size && postings[position].time <= checkpoint.asOfEpochMillis) {
                balance.add(postings[position++].amount)
            }
            val ledgerBalance = balance.balance()
            WorkspaceReconciliation(
                checkpoint.id, account.id, checkpoint.asOfEpochMillis, checkpoint.statementBalanceMinor,
                ledgerBalance, subtractMinor(checkpoint.statementBalanceMinor, ledgerBalance), balance.unvalued,
            )
        }
    }
}

private class AccountRunningBalance(initial: Long) {
    private val total = MinorAccumulator().apply { add(initial) }
    var count = 0
        private set
    var unvalued = 0
        private set

    fun add(amount: Long?) {
        count++
        if (amount == null) unvalued++ else total.add(amount)
    }

    fun balance(): Long = total.toLong()
}

/** Signed 128-bit accumulator avoids rejecting amounts whose exact net result fits in Long. */
private class MinorAccumulator {
    private var high = 0L
    private var low = 0UL

    fun add(amount: Long) {
        val previous = low
        low += amount.toULong()
        if (amount < 0) high--
        if (low < previous) high++
    }

    fun add(other: MinorAccumulator) {
        val previous = low
        low += other.low
        high += other.high
        if (low < previous) high++
    }

    fun toLong(): Long {
        val value = low.toLong()
        require(high == if (value < 0) -1L else 0L) { "余额超出可表示的范围。" }
        return value
    }
}

private class BookTotals(val book: WorkspaceBook) {
    private var income = 0L
    private var expense = 0L
    private var unvalued = 0

    fun record(type: TransactionType, amount: Long?) {
        when {
            amount == null -> unvalued++
            type == TransactionType.INCOME -> income = addMinor(income, amount)
            else -> expense = addMinor(expense, amount)
        }
    }

    fun result() = WorkspaceBookBalance(book.id, book.name, book.currency, income, expense, unvalued)
}

private class WorkspaceSchedule(val plan: WorkspacePlan) {
    private val firstDate = CivilDate.fromEpochDay(plan.firstEpochDay)

    fun occursOn(day: Long, date: CivilDate): Boolean {
        val elapsed = day - plan.firstEpochDay
        if (elapsed < 0) return false
        val index = when (plan.repeat) {
            WorkspaceRepeat.ONCE -> return elapsed == 0L
            WorkspaceRepeat.WEEKLY -> if (elapsed % 7L == 0L) elapsed / 7L else return false
            WorkspaceRepeat.MONTHLY -> {
                if (date.day != minOf(firstDate.day, date.daysInMonth())) return false
                (date.year - firstDate.year) * 12L + date.month - firstDate.month
            }
        }
        return plan.occurrences == 0 || index < plan.occurrences
    }
}

/** Gregorian civil dates, independent of timezone or daylight-saving transitions. */
private data class CivilDate(val year: Int, val month: Int, val day: Int) {
    fun daysInMonth(): Int = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    companion object {
        // Normalize to a March-first Gregorian cycle so leap day is always the cycle's last day.
        fun fromEpochDay(epochDay: Long): CivilDate {
            val day = epochDay + 719_468
            val era = day / 146_097
            val withinEra = day % 146_097
            val yearWithinEra = (withinEra - withinEra / 1_460 + withinEra / 36_524 - withinEra / 146_096) / 365
            val year = yearWithinEra + era * 400
            val dayOfYear = withinEra - (365 * yearWithinEra + yearWithinEra / 4 - yearWithinEra / 100)
            val monthFromMarch = (5 * dayOfYear + 2) / 153
            val month = if (monthFromMarch < 10) monthFromMarch + 3 else monthFromMarch - 9
            val dayOfMonth = dayOfYear - (153 * monthFromMarch + 2) / 5 + 1
            return CivilDate((year + if (month <= 2) 1 else 0).toInt(), month.toInt(), dayOfMonth.toInt())
        }
    }
}
