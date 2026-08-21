package com.financeos.shared.domain.usecase

import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.model.MonthPeriod

/** 当前日期起到月末的日均可用预算。 */
data class DailyAvailableBudget(
    val dailyAmount: Long,
    val amountRemaining: Long,
    val remainingDays: Int,
    val isOverBudget: Boolean,
)

/** 根据月总预算状态计算包含当天在内的日均可用金额。 */
class CalculateDailyAvailableBudgetUseCase(
    private val getBudgetStatus: GetBudgetStatusUseCase,
) {
    suspend operator fun invoke(
        period: MonthPeriod,
        currentDayOfMonth: Int,
    ): DailyAvailableBudget? {
        val daysInMonth = period.month.daysInMonth()
        require(currentDayOfMonth in 1..daysInMonth) {
            "Current day must be valid for the budget month."
        }

        val totalUsage = getBudgetStatus(period).total
        if (!totalUsage.hasBudget) return null

        val amountRemaining = checkNotNull(totalUsage.amountRemaining)
        // 当天仍可消费，因此剩余天数包含当天；月末当天固定为 1 天。
        val remainingDays = daysInMonth - currentDayOfMonth + 1
        // 超支后没有正的“可用”金额；整除向下舍弃不足一个最小货币单位的余数。
        val dailyAmount = amountRemaining.coerceAtLeast(0L) / remainingDays

        return DailyAvailableBudget(
            dailyAmount = dailyAmount,
            amountRemaining = amountRemaining,
            remainingDays = remainingDays,
            isOverBudget = totalUsage.isOverBudget,
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
