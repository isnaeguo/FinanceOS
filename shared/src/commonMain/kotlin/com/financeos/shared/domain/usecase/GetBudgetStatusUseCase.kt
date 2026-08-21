package com.financeos.shared.domain.usecase

import com.financeos.shared.domain.calculation.BudgetCalculator
import com.financeos.shared.domain.calculation.BudgetUsage
import com.financeos.shared.domain.model.MonthPeriod
import com.financeos.shared.domain.repository.BudgetRepository

/** 月总预算以及已设置分类预算的使用情况。 */
data class MonthlyBudgetStatus(
    val total: BudgetUsage,
    val categories: Map<String, BudgetUsage>,
)

/** 将当月支出汇总与总预算、分类预算进行比较。 */
class GetBudgetStatusUseCase(
    private val getMonthlySummary: GetMonthlySummaryUseCase,
    private val budgetRepository: BudgetRepository,
) {
    suspend operator fun invoke(period: MonthPeriod): MonthlyBudgetStatus {
        val summary = getMonthlySummary(period)
        val budgets = budgetRepository.getByMonth(period.month)
        val totalBudget = budgets.firstOrNull { it.categoryId == null }
        val categoryUsages = budgets
            .mapNotNull { budget ->
                val categoryId = budget.categoryId ?: return@mapNotNull null
                categoryId to BudgetCalculator.calculate(
                    budget = budget,
                    amountUsed = summary.expensesByCategory[categoryId] ?: 0L,
                )
            }
            .toMap()

        return MonthlyBudgetStatus(
            total = BudgetCalculator.calculate(
                budget = totalBudget,
                amountUsed = summary.totalExpense,
            ),
            categories = categoryUsages,
        )
    }
}
