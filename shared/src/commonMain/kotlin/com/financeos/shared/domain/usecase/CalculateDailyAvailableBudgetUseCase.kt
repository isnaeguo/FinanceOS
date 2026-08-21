package com.financeos.shared.domain.usecase

import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.model.MonthPeriod
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.repository.BudgetRepository
import kotlin.time.Instant

/** 当天零点确定、当天内保持不变的建议日预算。 */
data class DailyAvailableBudget(
    val dailyAmount: Long,
    /** 当天零点时尚未分配的月预算，不包含当天发生的支出。 */
    val amountRemaining: Long,
    val remainingDays: Int,
    val isOverBudget: Boolean,
)

/** 根据当天零点前的累计支出，计算包含当天在内的建议日预算。 */
class CalculateDailyAvailableBudgetUseCase(
    private val budgetRepository: BudgetRepository,
    private val getMonthlyTransactions: GetMonthlyTransactionsUseCase,
) {
    suspend operator fun invoke(
        period: MonthPeriod,
        currentDayOfMonth: Int,
        startOfToday: Instant,
    ): DailyAvailableBudget? {
        val daysInMonth = period.month.daysInMonth()
        require(currentDayOfMonth in 1..daysInMonth) {
            "Current day must be valid for the budget month."
        }
        require(startOfToday >= period.startInclusive && startOfToday < period.endExclusive) {
            "Start of today must be inside the budget month."
        }

        val totalBudget = budgetRepository.get(period.month, categoryId = null) ?: return null
        val amountUsedBeforeToday = getMonthlyTransactions(period)
            .asSequence()
            .filter { transaction ->
                transaction.type == TransactionType.EXPENSE &&
                    transaction.dateTime < startOfToday
            }
            .fold(0L) { total, transaction ->
                require(transaction.amount <= Long.MAX_VALUE - total) {
                    "Daily budget expense total exceeds Long range."
                }
                total + transaction.amount
            }

        val amountRemaining = totalBudget.amountLimit - amountUsedBeforeToday
        // 当天仍可消费，因此剩余天数包含当天；月末当天固定为 1 天。
        val remainingDays = daysInMonth - currentDayOfMonth + 1
        // 只统计今日零点前的支出，所以当天新增或删除流水不会反复改变这次分配。
        // 超支后没有正的建议金额；整除向下舍弃不足一个最小货币单位的余数。
        val dailyAmount = amountRemaining.coerceAtLeast(0L) / remainingDays

        return DailyAvailableBudget(
            dailyAmount = dailyAmount,
            amountRemaining = amountRemaining,
            remainingDays = remainingDays,
            isOverBudget = amountRemaining < 0L,
        )
    }
}

private fun BudgetMonth.daysInMonth(): Int = when (month) {
    2 -> if (isLeapYear(year)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

private fun isLeapYear(year: Int): Boolean =
    year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
