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

        val result = useCase(period(2028, 2), currentDayOfMonth = 1)

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

        val result = useCase(period(2026, 4), currentDayOfMonth = 30)

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

        val result = useCase(period(2026, 8), currentDayOfMonth = 15)

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

        assertNull(useCase(period(2026, 8), currentDayOfMonth = 10))
    }

    @Test
    fun dailyBudgetRejectsDayOutsideSelectedMonth() = runTest {
        val month = BudgetMonth(2026, 2)
        val useCase = dailyBudgetUseCase(
            transactions = emptyList(),
            budgets = listOf(budget("total", month, 28_000L)),
        )

        assertFailsWith<IllegalArgumentException> {
            useCase(period(2026, 2), currentDayOfMonth = 29)
        }
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
        getBudgetStatus = budgetStatusUseCase(transactions, budgets),
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
