package com.financeos.shared.domain.usecase

import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.model.MonthPeriod
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class MonthlyUseCasesTest {
    @Test
    fun monthlyTransactionsUseHalfOpenMonthBoundaries() = runTest {
        val period = period(2026, 8)
        val start = transaction("start", "2026-08-01T00:00:00Z")
        val endOfMonth = transaction("end-of-month", "2026-08-31T23:59:59.999Z")
        val nextMonth = transaction("next-month", "2026-09-01T00:00:00Z")
        val useCase = GetMonthlyTransactionsUseCase(
            FakeTransactionRepository(listOf(start, endOfMonth, nextMonth)),
        )

        assertEquals(listOf(start, endOfMonth), useCase(period))
    }

    @Test
    fun monthlySummaryCalculatesIncomeExpenseNetAndCategoryExpenses() = runTest {
        val summary = monthlySummaryUseCase(
            listOf(
                transaction("income", amount = 20_000L, type = TransactionType.INCOME, categoryId = "income"),
                transaction("food-1", amount = 3_000L, categoryId = "food"),
                transaction("food-2", amount = 2_000L, categoryId = "food"),
                transaction("transport", amount = 4_000L, categoryId = "transport"),
            ),
        )(period(2026, 8))

        assertEquals(20_000L, summary.totalIncome)
        assertEquals(9_000L, summary.totalExpense)
        assertEquals(11_000L, summary.netChange)
        assertEquals(mapOf("food" to 5_000L, "transport" to 4_000L), summary.expensesByCategory)
    }

    @Test
    fun monthlySummaryReturnsZerosWhenThereAreNoTransactions() = runTest {
        val summary = monthlySummaryUseCase(emptyList())(period(2026, 8))

        assertEquals(0L, summary.totalIncome)
        assertEquals(0L, summary.totalExpense)
        assertEquals(0L, summary.netChange)
        assertEquals(emptyMap(), summary.expensesByCategory)
    }

    @Test
    fun monthlySummaryRejectsMoneyOverflow() = runTest {
        val useCase = monthlySummaryUseCase(
            listOf(
                transaction("expense-1", amount = Long.MAX_VALUE),
                transaction("expense-2", amount = 1L),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            useCase(period(2026, 8))
        }
    }

    @Test
    fun dailyExpenseUsesHalfOpenLocalDayBoundariesAndIgnoresIncome() {
        val useCase = CalculateDailyExpenseUseCase()
        val result = useCase(
            transactions = listOf(
                transaction("before", "2026-08-20T23:59:59.999Z", amount = 9_000L),
                transaction("start", "2026-08-21T00:00:00Z", amount = 1_000L),
                transaction("income", "2026-08-21T12:00:00Z", amount = 8_000L, type = TransactionType.INCOME),
                transaction("end", "2026-08-21T23:59:59.999Z", amount = 2_000L),
                transaction("next-day", "2026-08-22T00:00:00Z", amount = 7_000L),
            ),
            startOfDayInclusive = Instant.parse("2026-08-21T00:00:00Z"),
            startOfNextDayExclusive = Instant.parse("2026-08-22T00:00:00Z"),
        )

        assertEquals(3_000L, result)
    }

    @Test
    fun dailyExpenseRejectsMoneyOverflow() {
        val useCase = CalculateDailyExpenseUseCase()

        assertFailsWith<IllegalArgumentException> {
            useCase(
                transactions = listOf(
                    transaction("max", amount = Long.MAX_VALUE),
                    transaction("overflow", amount = 1L),
                ),
                startOfDayInclusive = Instant.parse("2026-08-10T00:00:00Z"),
                startOfNextDayExclusive = Instant.parse("2026-08-11T00:00:00Z"),
            )
        }
    }

    @Test
    fun budgetStatusCalculatesTotalAndConfiguredCategoryBudgets() = runTest {
        val month = BudgetMonth(2026, 8)
        val useCase = budgetStatusUseCase(
            transactions = listOf(
                transaction("food", amount = 20_000L, categoryId = "food"),
                transaction("transport", amount = 10_000L, categoryId = "transport"),
                transaction("income", amount = 50_000L, type = TransactionType.INCOME, categoryId = "income"),
            ),
            budgets = listOf(
                budget("total", month, 100_000L),
                budget("food-budget", month, 30_000L, "food"),
                budget("entertainment-budget", month, 10_000L, "entertainment"),
            ),
        )

        val status = useCase(period(2026, 8))

        assertEquals(30_000L, status.total.amountUsed)
        assertEquals(70_000L, status.total.amountRemaining)
        assertEquals(20_000L, status.categories.getValue("food").amountUsed)
        assertEquals(10_000L, status.categories.getValue("food").amountRemaining)
        assertEquals(0L, status.categories.getValue("entertainment").amountUsed)
        assertEquals(setOf("food", "entertainment"), status.categories.keys)
    }

    @Test
    fun monthStartWithNoSpendingUsesEveryDayOfLeapFebruary() = runTest {
        val month = BudgetMonth(2028, 2)
        val useCase = dailyBudgetUseCase(
            transactions = emptyList(),
            budgets = listOf(budget("total", month, 29_000L)),
        )

        val result = useCase(
            period = period(2028, 2),
            currentDayOfMonth = 1,
            startOfToday = Instant.parse("2028-02-01T00:00:00Z"),
        )

        assertEquals(1_000L, result?.dailyAmount)
        assertEquals(29_000L, result?.amountRemaining)
        assertEquals(29, result?.remainingDays)
        assertFalse(result!!.isOverBudget)
    }

    @Test
    fun lastDayOfMonthHasOneRemainingDay() = runTest {
        val month = BudgetMonth(2026, 4)
        val useCase = dailyBudgetUseCase(
            transactions = listOf(transaction("expense", amount = 7_000L, dateTime = "2026-04-10T00:00:00Z")),
            budgets = listOf(budget("total", month, 10_000L)),
        )

        val result = useCase(
            period = period(2026, 4),
            currentDayOfMonth = 30,
            startOfToday = Instant.parse("2026-04-30T00:00:00Z"),
        )

        assertEquals(3_000L, result?.dailyAmount)
        assertEquals(3_000L, result?.amountRemaining)
        assertEquals(1, result?.remainingDays)
    }

    @Test
    fun overspentBudgetHasNoFurtherDailyAmount() = runTest {
        val month = BudgetMonth(2026, 8)
        val useCase = dailyBudgetUseCase(
            transactions = listOf(transaction("expense", amount = 12_000L)),
            budgets = listOf(budget("total", month, 10_000L)),
        )

        val result = useCase(
            period = period(2026, 8),
            currentDayOfMonth = 15,
            startOfToday = Instant.parse("2026-08-15T00:00:00Z"),
        )

        assertEquals(0L, result?.dailyAmount)
        assertEquals(-2_000L, result?.amountRemaining)
        assertEquals(17, result?.remainingDays)
        assertTrue(result!!.isOverBudget)
    }

    @Test
    fun missingTotalBudgetReturnsNoDailyAvailableBudget() = runTest {
        val useCase = dailyBudgetUseCase(
            transactions = listOf(transaction("expense", amount = 1_000L)),
            budgets = emptyList(),
        )

        assertNull(
            useCase(
                period = period(2026, 8),
                currentDayOfMonth = 10,
                startOfToday = Instant.parse("2026-08-10T00:00:00Z"),
            ),
        )
    }

    @Test
    fun dailyBudgetRejectsDayOutsideSelectedMonth() = runTest {
        val month = BudgetMonth(2026, 2)
        val useCase = dailyBudgetUseCase(
            transactions = emptyList(),
            budgets = listOf(budget("total", month, 28_000L)),
        )

        assertFailsWith<IllegalArgumentException> {
            useCase(
                period = period(2026, 2),
                currentDayOfMonth = 29,
                startOfToday = Instant.parse("2026-02-28T00:00:00Z"),
            )
        }
    }

    @Test
    fun transactionsCreatedTodayDoNotChangeTodaysAllocation() = runTest {
        val month = BudgetMonth(2026, 8)
        val beforeToday = transaction(
            id = "before-today",
            amount = 14_000L,
            dateTime = "2026-08-14T23:59:59Z",
        )
        val transactionRepository = FakeTransactionRepository(listOf(beforeToday))
        val useCase = CalculateDailyAvailableBudgetUseCase(
            budgetRepository = FakeBudgetRepository(
                listOf(budget("total", month, 31_000L)),
            ),
            getMonthlyTransactions = GetMonthlyTransactionsUseCase(transactionRepository),
        )
        val startOfToday = Instant.parse("2026-08-15T00:00:00Z")

        val initialAllocation = useCase(period(2026, 8), 15, startOfToday)
        transactionRepository.add(
            transaction("today-start", "2026-08-15T00:00:00Z", amount = 10_000L),
        )
        transactionRepository.add(
            transaction("today-later", "2026-08-15T18:00:00Z", amount = 15_000L),
        )
        val allocationAfterSpending = useCase(period(2026, 8), 15, startOfToday)

        assertEquals(1_000L, initialAllocation?.dailyAmount)
        assertEquals(initialAllocation, allocationAfterSpending)
    }

    @Test
    fun nextDayRecalculatesUsingPreviousDaysFinalSpending() = runTest {
        val month = BudgetMonth(2026, 8)
        val useCase = dailyBudgetUseCase(
            transactions = listOf(
                transaction("earlier", "2026-08-14T23:59:59Z", amount = 14_000L),
                transaction("yesterday", "2026-08-15T18:00:00Z", amount = 5_000L),
            ),
            budgets = listOf(budget("total", month, 31_000L)),
        )

        val result = useCase(
            period = period(2026, 8),
            currentDayOfMonth = 16,
            startOfToday = Instant.parse("2026-08-16T00:00:00Z"),
        )

        assertEquals(750L, result?.dailyAmount)
        assertEquals(12_000L, result?.amountRemaining)
        assertEquals(16, result?.remainingDays)
    }

    private fun monthlySummaryUseCase(
        transactions: List<Transaction>,
    ): GetMonthlySummaryUseCase = GetMonthlySummaryUseCase(
        GetMonthlyTransactionsUseCase(FakeTransactionRepository(transactions)),
    )

    private fun budgetStatusUseCase(
        transactions: List<Transaction>,
        budgets: List<Budget>,
    ): GetBudgetStatusUseCase = GetBudgetStatusUseCase(
        getMonthlySummary = monthlySummaryUseCase(transactions),
        budgetRepository = FakeBudgetRepository(budgets),
    )

    private fun dailyBudgetUseCase(
        transactions: List<Transaction>,
        budgets: List<Budget>,
    ): CalculateDailyAvailableBudgetUseCase = CalculateDailyAvailableBudgetUseCase(
        budgetRepository = FakeBudgetRepository(budgets),
        getMonthlyTransactions = GetMonthlyTransactionsUseCase(
            FakeTransactionRepository(transactions),
        ),
    )

    private fun period(
        year: Int,
        month: Int,
    ): MonthPeriod {
        val nextYear = if (month == 12) year + 1 else year
        val nextMonth = if (month == 12) 1 else month + 1
        return MonthPeriod(
            month = BudgetMonth(year, month),
            startInclusive = Instant.parse(monthStart(year, month)),
            endExclusive = Instant.parse(monthStart(nextYear, nextMonth)),
        )
    }

    private fun monthStart(year: Int, month: Int): String =
        "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-01T00:00:00Z"

    private fun transaction(
        id: String,
        dateTime: String = "2026-08-10T00:00:00Z",
        amount: Long = 1_000L,
        type: TransactionType = TransactionType.EXPENSE,
        categoryId: String = "food",
    ) = Transaction(
        id = id,
        amount = amount,
        type = type,
        categoryId = categoryId,
        dateTime = Instant.parse(dateTime),
    )

    private fun budget(
        id: String,
        month: BudgetMonth,
        amountLimit: Long,
        categoryId: String? = null,
    ) = Budget(
        id = id,
        month = month,
        amountLimit = amountLimit,
        categoryId = categoryId,
    )
}
