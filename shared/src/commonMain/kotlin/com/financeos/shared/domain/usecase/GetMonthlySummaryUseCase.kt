package com.financeos.shared.domain.usecase

import com.financeos.shared.domain.model.MonthPeriod
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType

/** 指定月份的收支汇总，所有金额均为最小货币单位。 */
data class MonthlySummary(
    val totalIncome: Long,
    val totalExpense: Long,
    val netChange: Long,
    val expensesByCategory: Map<String, Long>,
)

/** 根据月流水计算收入、支出、净变化及分类支出。 */
class GetMonthlySummaryUseCase(
    private val getMonthlyTransactions: GetMonthlyTransactionsUseCase,
) {
    suspend operator fun invoke(period: MonthPeriod): MonthlySummary {
        return calculate(getMonthlyTransactions(period))
    }

    /** 使用调用方已经取得的同月快照计算，避免响应式页面再次访问数据库。 */
    fun calculate(transactions: List<Transaction>): MonthlySummary {
        var totalIncome = 0L
        var totalExpense = 0L
        val expensesByCategory = linkedMapOf<String, Long>()

        transactions.forEach { transaction ->
            when (transaction.type) {
                TransactionType.INCOME -> {
                    totalIncome = addMoney(totalIncome, transaction.amount)
                }

                TransactionType.EXPENSE -> {
                    totalExpense = addMoney(totalExpense, transaction.amount)
                    expensesByCategory[transaction.categoryId] = addMoney(
                        expensesByCategory[transaction.categoryId] ?: 0L,
                        transaction.amount,
                    )
                }
            }
        }

        return MonthlySummary(
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            netChange = totalIncome - totalExpense,
            expensesByCategory = expensesByCategory,
        )
    }
}

private fun addMoney(current: Long, amount: Long): Long {
    // 金额使用 Long 累加时显式阻止溢出，避免汇总结果悄悄变成负数。
    require(amount <= Long.MAX_VALUE - current) { "Monthly money total exceeds Long range." }
    return current + amount
}
