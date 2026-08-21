package com.financeos.app.ui.viewmodel

import com.financeos.shared.domain.calculation.BudgetUsage
import com.financeos.shared.domain.model.DefaultCategories
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.usecase.DailyAvailableBudget
import com.financeos.shared.domain.usecase.MonthlyBudgetStatus
import com.financeos.shared.domain.usecase.MonthlySummary
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class DashboardUiStateTest {
    @Test
    fun aggregatesOverspentMonthIntoClearDashboardState() {
        val state = buildDashboardUiState(
            monthLabel = "2026年8月",
            summary = MonthlySummary(
                totalIncome = 20_000L,
                totalExpense = 10_000L,
                netChange = 10_000L,
                expensesByCategory = mapOf(
                    "system-food" to 7_000L,
                    "system-transport" to 3_000L,
                ),
            ),
            dailyExpense = 3_000L,
            budgetStatus = MonthlyBudgetStatus(
                total = BudgetUsage(
                    amountLimit = 8_000L,
                    amountUsed = 10_000L,
                    amountRemaining = -2_000L,
                    usageRatio = 1.25,
                    isOverBudget = true,
                    hasBudget = true,
                ),
                categories = emptyMap(),
            ),
            dailyAvailable = DailyAvailableBudget(
                dailyAmount = 0L,
                amountRemaining = -2_000L,
                remainingDays = 11,
                isOverBudget = true,
            ),
            transactions = listOf(
                transaction("older", "2026-08-20T08:00:00Z"),
                transaction("newer", "2026-08-21T08:00:00Z"),
            ),
            categoriesById = DefaultCategories.all.associateBy { it.id },
            zoneId = ZoneId.of("UTC"),
        )

        assertEquals("¥100.00", state.monthlyExpenseText)
        assertEquals("¥30.00", state.dailyExpenseText)
        assertEquals("¥200.00", state.monthlyIncomeText)
        assertEquals("超出 ¥20.00", state.remainingBudgetText)
        assertEquals("¥0.00", state.dailyAvailableText)
        assertTrue(state.isOverBudget)
        assertEquals(1f, state.budgetProgress)
        assertEquals("餐饮", state.topCategories.first().categoryName)
        assertEquals("70%", state.topCategories.first().shareText)
        assertEquals("newer", state.recentTransactions.first().id)
        assertTrue(state.dailyAvailableExplanation.contains("不是硬限制"))
    }

    @Test
    fun missingBudgetAndTransactionsProduceGuidedEmptyState() {
        val state = buildDashboardUiState(
            monthLabel = "2026年8月",
            summary = MonthlySummary(0L, 0L, 0L, emptyMap()),
            dailyExpense = 0L,
            budgetStatus = MonthlyBudgetStatus(
                total = BudgetUsage(
                    amountLimit = null,
                    amountUsed = 0L,
                    amountRemaining = null,
                    usageRatio = null,
                    isOverBudget = false,
                    hasBudget = false,
                ),
                categories = emptyMap(),
            ),
            dailyAvailable = null,
            transactions = emptyList(),
            categoriesById = emptyMap(),
            zoneId = ZoneId.of("UTC"),
        )

        assertFalse(state.hasBudget)
        assertEquals("未设置", state.remainingBudgetText)
        assertEquals("未设置", state.dailyAvailableText)
        assertEquals(emptyList(), state.topCategories)
        assertEquals(emptyList(), state.recentTransactions)
        assertTrue(state.budgetProgressText.contains("尚未设置"))
    }

    private fun transaction(id: String, dateTime: String) = Transaction(
        id = id,
        amount = 1_000L,
        type = TransactionType.EXPENSE,
        categoryId = "system-food",
        dateTime = Instant.parse(dateTime),
    )
}
