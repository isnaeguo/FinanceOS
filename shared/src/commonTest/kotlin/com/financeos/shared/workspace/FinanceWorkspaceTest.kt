package com.financeos.shared.workspace

import com.financeos.shared.data.transfer.FinanceDataJsonCodec
import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.model.DefaultCategories
import com.financeos.shared.domain.model.FinanceDataSnapshot
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class FinanceWorkspaceTest {
    private val workspace = FinanceWorkspace()
    private val analytics = FinanceWorkspaceAnalytics()
    private val afterLedger = Instant.parse("2026-08-02T00:00:00Z").toEpochMilliseconds()

    @Test
    fun linkedLedgerReconcilesAccountsAndAllocatesRefundsToTheOriginalBook() {
        val outgoing = entry("transfer-out", 2_500, "cash")
        val incoming = entry("transfer-in", 2_500, "card", TransactionType.INCOME)
        val purchase = entry("purchase", 4_000, "cash")
        val duplicate = purchase.copy(id = "repeat")
        val refund = entry("refund", 1_001, "cash", TransactionType.INCOME, hour = 12)
        val ledger = listOf(outgoing, incoming, purchase, duplicate, refund)
        var state = FinanceWorkspaceState(
            accounts = listOf(account("cash", 10_000), account("card", 0)),
            books = listOf(WorkspaceBook("family", "家庭", "CNY"), WorkspaceBook("work", "工作", "CNY")),
        )
        state = workspace.saveAssignment(state, WorkspaceAssignment(
            transactionId = purchase.id,
            splits = listOf(WorkspaceSplit("work", 2_500), WorkspaceSplit("family", 1_500)),
        ), ledger)
        state = workspace.saveLink(state, WorkspaceLink(outgoing.id, incoming.id, WorkspaceLinkKind.TRANSFER), ledger)
        state = workspace.saveLink(state, WorkspaceLink(duplicate.id, purchase.id, WorkspaceLinkKind.DUPLICATE), ledger)
        state = workspace.saveLink(state, WorkspaceLink(refund.id, purchase.id, WorkspaceLinkKind.REFUND), ledger)
        state = workspace.saveCheckpoint(
            state,
            WorkspaceCheckpoint("statement", "cash", purchase.dateTime.toEpochMilliseconds(), 3_505),
        )
        val summary = analytics.summary(state, ledger, afterLedger)
        assertEquals(listOf(4_501L, 2_500L), summary.accountBalances.map { it.balanceMinor })
        assertEquals(listOf(3, 1), summary.accountBalances.map { it.transactionCount })
        assertEquals(3_500L, summary.reconciliations.single().ledgerBalanceMinor)
        assertEquals(5L, summary.reconciliations.single().differenceMinor)
        assertEquals(0, summary.unresolvedLinkCount)
        assertEquals(listOf(375L, 626L), summary.bookBalances.map { it.incomeMinor })
        assertEquals(listOf(1_500L, 2_500L), summary.bookBalances.map { it.expenseMinor })
        assertEquals(listOf("purchase", "refund"), analytics.transactionsForBook(state, ledger, null).map { it.id })
        assertEquals(listOf(2_500L, 626L), analytics.transactionsForBook(state, ledger, "work").map { it.amount })

        val snapshot = FinanceDataSnapshot(
            ledger, DefaultCategories.all, listOf(Budget("private-budget", BudgetMonth(2026, 8), 9_999L)),
        )
        val scoped = analytics.scopedSnapshot(state, snapshot, "work")
        assertEquals(scoped, analytics.scopedSnapshot(state, snapshot, "work"))
        assertEquals(scoped.transactions.size, scoped.transactions.map { it.id }.toSet().size)
        assertFalse(scoped.transactions.any { row -> ledger.any { it.id == row.id } })
        assertNull(scoped.transactions.first().note)
        assertNull(scoped.transactions.first().accountId)
        assertEquals(2, scoped.categories.size)
        assertEquals(emptyList(), scoped.budgets)
        val codec = FinanceDataJsonCodec()
        assertEquals(scoped.transactions.sortedBy { it.id }, codec.decode(codec.encode(scoped)).transactions)
    }

    @Test
    fun aTransferMustConserveTheBookedAmountAndUseDifferentKnownAccounts() {
        val out = entry("out", 1_000, "wallet")
        val incoming = entry("in", 1_000, "card", TransactionType.INCOME)
        val state = FinanceWorkspaceState(accounts = listOf(account("wallet", 0), account("card", 0)))
        val transfer = WorkspaceLink(out.id, incoming.id, WorkspaceLinkKind.TRANSFER)
        assertFailsWith<IllegalArgumentException> {
            workspace.saveLink(state, transfer, listOf(out, incoming.copy(amount = 999)))
        }
        assertFailsWith<IllegalArgumentException> {
            workspace.saveLink(state, transfer, listOf(out, incoming.copy(accountId = out.accountId)))
        }
        val saved = workspace.saveLink(state, transfer, listOf(out, incoming))
        val edited = listOf(out, incoming.copy(amount = 990))
        assertEquals(edited, analytics.transactionsForBook(saved, edited, null))
        assertEquals(1, analytics.summary(saved, edited, afterLedger).unresolvedLinkCount)
        assertEquals(-10L, analytics.forecast(saved, edited, afterLedger, 20_667, 30).openingBalanceMinor)
    }

    @Test
    fun foreignValueRemainsExactAndDoesNotMixAccountDenominations() {
        val purchase = entry("usd-spend", 7_001, "usd")
        val state = FinanceWorkspaceState(
            accounts = listOf(WorkspaceAccount("usd", "US account", "USD", 12_000)),
            books = listOf(WorkspaceBook("us", "US travel", "USD")),
        )
        val assignment = WorkspaceAssignment(purchase.id, "usd", "us", "USD", 1_001)
        val stored = workspace.saveAssignment(state, assignment, listOf(purchase))
        val summary = analytics.summary(stored, listOf(purchase), afterLedger)
        assertEquals(10_999L, summary.accountBalances.single().balanceMinor)
        assertEquals(1_001L, summary.bookBalances.single().expenseMinor)
        assertEquals(7_001L, analytics.transactionsForBook(stored, listOf(purchase), "us").single().amount)
        assertEquals(-7_001L, analytics.forecast(stored, listOf(purchase), afterLedger, 20_667, 30).openingBalanceMinor)
        assertFailsWith<IllegalArgumentException> {
            workspace.saveAssignment(stored, assignment.copy(originalCurrency = "EUR"), listOf(purchase))
        }
        assertEquals(stored, FinanceWorkspaceCodec.decode(FinanceWorkspaceCodec.encode(stored)))
        assertFailsWith<IllegalArgumentException> { FinanceWorkspaceCodec.decode("{}") }
        assertFailsWith<IllegalArgumentException> {
            FinanceWorkspaceCodec.decode(FinanceWorkspaceCodec.encode(stored).replace("\"schema_version\":1", "\"schema_version\":99"))
        }
    }

    @Test
    fun monthlyRecurrenceClampsLeapDaysWithoutShiftingItsOriginalAnchor() {
        val jan31 = 19_753L
        val feb29 = 19_782L
        val mar31 = 19_813L
        val state = FinanceWorkspaceState(
            accounts = listOf(account("cash", 10_000)),
            plans = listOf(
                WorkspacePlan("monthly", "每月房租", 2_000L, TransactionType.EXPENSE, jan31, WorkspaceRepeat.MONTHLY, 3),
                WorkspacePlan("weekly", "每周收入", 500L, TransactionType.INCOME, jan31, WorkspaceRepeat.WEEKLY, 2),
                WorkspacePlan("what-if", "可选收入", 700L, TransactionType.INCOME, feb29, WorkspaceRepeat.ONCE, 1, scenario = "报销"),
            ),
        )
        val baseline = analytics.forecast(state, emptyList(), 0, jan31, 90)
        assertEquals(listOf(jan31, feb29, mar31), baseline.points.filter { it.expenseMinor > 0 }.map { it.epochDay })
        assertEquals(listOf(jan31, jan31 + 7), baseline.points.filter { it.incomeMinor > 0 }.map { it.epochDay })
        assertEquals(5_000L, baseline.closingBalanceMinor)
        assertEquals(5_000L, baseline.lowBalanceMinor)
        val scenario = analytics.forecast(state, emptyList(), 0, jan31, 90, "报销")
        assertEquals(5_700L, scenario.closingBalanceMinor)
        assertNotEquals(baseline, scenario)
    }

    @Test
    fun netBalanceSupportsRepresentableResultsAfterOversizedIntermediateSums() {
        val state = FinanceWorkspaceState(accounts = listOf(account("cash", Long.MAX_VALUE)))
        val records = listOf(entry("credit", 5, "cash", TransactionType.INCOME), entry("debit", 5, "cash"))
        assertEquals(Long.MAX_VALUE, analytics.summary(state, records, afterLedger).accountBalances.single().balanceMinor)
        assertEquals(Long.MAX_VALUE, analytics.forecast(state, records, afterLedger, 20_667, 1).closingBalanceMinor)
    }

    @Test
    fun currentBalancesAndStatementCutoffsStayIndependentOfInputOrder() {
        val noon = entry("noon", 200, "cash", hour = 12)
        val evening = entry("evening", 1_000, "cash", TransactionType.INCOME, hour = 20)
        val morning = entry("morning", 100, "card", hour = 8)
        val futureCard = entry("future-card", 300, "card", hour = 23)
        val state = FinanceWorkspaceState(
            accounts = listOf(account("cash", 0), account("card", 0)),
            checkpoints = listOf(
                WorkspaceCheckpoint("evening", "cash", evening.dateTime.toEpochMilliseconds(), 800),
                WorkspaceCheckpoint("noon", "cash", noon.dateTime.toEpochMilliseconds(), -200),
            ),
        )
        val result = analytics.summary(state, listOf(evening, futureCard, noon, morning), noon.dateTime.toEpochMilliseconds())
        assertEquals(listOf(-200L, -100L), result.accountBalances.map { it.balanceMinor })
        assertEquals(listOf(800L, -200L), result.reconciliations.map { it.ledgerBalanceMinor })
        assertEquals(listOf(0L, 0L), result.reconciliations.map { it.differenceMinor })
        val dates = listOf(noon, evening, futureCard).map { it.dateTime.toEpochMilliseconds() }
        assertEquals(listOf(-300L, 700L, 400L), analytics.netWorth(state, listOf(evening, futureCard, noon, morning), "CNY", dates).map { it.balanceMinor })
        assertFailsWith<IllegalArgumentException> { analytics.netWorth(state, emptyList(), "CNY", dates.reversed()) }
    }

    private fun account(id: String, opening: Long) = WorkspaceAccount(id, id, "CNY", opening)

    private fun entry(
        id: String,
        amount: Long,
        account: String,
        type: TransactionType = TransactionType.EXPENSE,
        hour: Int = 10,
    ) = Transaction(
        id = id,
        amount = amount,
        type = type,
        categoryId = if (type == TransactionType.INCOME) "system-income" else "system-food",
        accountId = account,
        dateTime = Instant.parse("2026-08-01T${hour.toString().padStart(2, '0')}:00:00Z"),
        note = "私人备注",
        updatedAt = 10,
    )
}
