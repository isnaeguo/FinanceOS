package com.financeos.shared.domain.usecase

import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.MonthPeriod
import com.financeos.shared.domain.model.Transaction
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
        val remainingDays = period.remainingDaysAt(currentDayOfMonth, startOfToday)
        val totalBudget = budgetRepository.get(period.month, categoryId = null)?.takeUnless { it.deletedAt != null }
            ?: return null
        return calculate(
            period = period,
            startOfToday = startOfToday,
            totalBudget = totalBudget,
            transactions = getMonthlyTransactions(period),
            remainingDays = remainingDays,
        )
    }

    /** 使用页面已观察到的同一份预算和流水快照，保证结果一致且不重复查询。 */
    fun calculate(
        period: MonthPeriod,
        currentDayOfMonth: Int,
        startOfToday: Instant,
        totalBudget: Budget?,
        transactions: List<Transaction>,
    ): DailyAvailableBudget? {
        val remainingDays = period.remainingDaysAt(currentDayOfMonth, startOfToday)
        val activeBudget = totalBudget?.takeUnless { it.deletedAt != null } ?: return null
        return calculate(period, startOfToday, activeBudget, transactions, remainingDays)
    }

    private fun calculate(
        period: MonthPeriod,
        startOfToday: Instant,
        totalBudget: Budget,
        transactions: List<Transaction>,
        remainingDays: Int,
    ): DailyAvailableBudget {
        val amountUsedBeforeToday = transactions
            .asSequence()
            .filter { transaction ->
                transaction.deletedAt == null &&
                    transaction.type == TransactionType.EXPENSE &&
                    transaction.dateTime >= period.startInclusive && transaction.dateTime < startOfToday
            }
            .fold(0L) { total, transaction ->
                require(transaction.amount <= Long.MAX_VALUE - total) {
                    "Daily budget expense total exceeds Long range."
                }
                total + transaction.amount
            }

        return dailyBudgetFromExpenses(totalBudget.amountLimit, amountUsedBeforeToday, remainingDays)
    }
}

internal fun dailyBudgetFromExpenses(
    amountLimit: Long,
    amountUsedBeforeToday: Long,
    remainingDays: Int,
): DailyAvailableBudget {
    val remaining = amountLimit - amountUsedBeforeToday
    return DailyAvailableBudget(
        dailyAmount = remaining.coerceAtLeast(0L) / remainingDays,
        amountRemaining = remaining,
        remainingDays = remainingDays,
        isOverBudget = remaining < 0L,
    )
}

/** 剩余天数包含今天，月末最后一天返回 1。 */
internal fun MonthPeriod.remainingDaysAt(day: Int, startOfToday: Instant): Int {
    val daysInMonth = month.daysInMonth()
    require(day in 1..daysInMonth) { "Current day must be valid for the budget month." }
    require(startOfToday >= startInclusive && startOfToday < endExclusive) {
        "Start of today must be inside the budget month."
    }
    return daysInMonth - day + 1
}

private fun BudgetMonth.daysInMonth(): Int = when (month) {
    2 -> if (isLeapYear(year)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

private fun isLeapYear(year: Int): Boolean =
    year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
