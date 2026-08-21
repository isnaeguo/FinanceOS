package com.financeos.shared.domain.usecase

import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import kotlin.time.Instant

/** 按调用方提供的本地日期边界，汇总当天的支出金额。 */
class CalculateDailyExpenseUseCase {
    operator fun invoke(
        transactions: List<Transaction>,
        startOfDayInclusive: Instant,
        startOfNextDayExclusive: Instant,
    ): Long {
        require(startOfDayInclusive < startOfNextDayExclusive) {
            "Daily expense period must have a positive duration."
        }

        return transactions
            .asSequence()
            .filter { transaction ->
                transaction.type == TransactionType.EXPENSE &&
                    transaction.dateTime >= startOfDayInclusive &&
                    transaction.dateTime < startOfNextDayExclusive
            }
            .fold(0L) { total, transaction ->
                // 金额继续使用 Long 累加，并显式阻止极端数据导致结果溢出。
                require(transaction.amount <= Long.MAX_VALUE - total) {
                    "Daily expense total exceeds Long range."
                }
                total + transaction.amount
            }
    }
}
