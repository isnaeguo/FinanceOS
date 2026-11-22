package com.financeos.shared.domain.usecase

import com.financeos.shared.domain.calculation.BudgetCalculator
import com.financeos.shared.domain.calculation.BudgetUsage
import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.MonthPeriod
import com.financeos.shared.domain.model.Transaction
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
    /** 使用同一批流水与预算快照计算，供响应式页面避免再次查询。 */
    fun calculate(
        transactions: List<Transaction>,
        budgets: List<Budget>,
    ): MonthlyBudgetStatus = calculate(getMonthlySummary.calculate(transactions), budgets)

    suspend operator fun invoke(period: MonthPeriod): MonthlyBudgetStatus {
        val summary = getMonthlySummary(period)
        val budgets = budgetRepository.getByMonth(period.month)
        return calculate(summary, budgets)
    }

    /** 使用同一批流水汇总与预算快照计算，避免页面看到跨查询时点的不一致结果。 */
    fun calculate(
        summary: MonthlySummary,
        budgets: List<Budget>,
    ): MonthlyBudgetStatus {
        val totalBudget = budgets.firstOrNull { it.categoryId == null && it.deletedAt == null }
        val categoryUsages = buildMap {
            for (budget in budgets) {
                val categoryId = budget.categoryId ?: continue
                if (budget.deletedAt == null) {
                    put(
                        categoryId,
                        BudgetCalculator.calculate(budget, summary.expensesByCategory[categoryId] ?: 0L),
                    )
                }
            }
        }

        return MonthlyBudgetStatus(
            total = BudgetCalculator.calculate(
                budget = totalBudget,
                // 月总预算按净支出（支出 − 收入）统计：收入进账会抵扣预算消耗，结余即未用完。
                amountUsed = summary.totalExpense - summary.totalIncome,
            ),
            categories = categoryUsages,
        )
    }
}
