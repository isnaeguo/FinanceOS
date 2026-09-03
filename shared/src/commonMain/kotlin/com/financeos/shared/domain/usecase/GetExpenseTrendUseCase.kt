package com.financeos.shared.domain.usecase

import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.repository.TransactionRepository
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** 一个用于趋势聚合的半开时间桶。 */
data class ExpenseTrendPeriod(
    val key: String,
    val startInclusive: Instant,
    val endExclusive: Instant,
) {
    init {
        require(key.isNotBlank()) { "Expense trend key must not be blank." }
        require(startInclusive < endExclusive) {
            "Expense trend period must have a positive duration."
        }
    }
}

/** 一个时间桶内的支出总额，金额继续使用最小货币单位。 */
data class ExpenseTrendPoint(
    val key: String,
    val amount: Long,
)

/** 一次读取完整时间范围，再按时间桶计算支出趋势。 */
class GetExpenseTrendUseCase(
    private val transactionRepository: TransactionRepository,
) {
    suspend operator fun invoke(periods: List<ExpenseTrendPeriod>): List<ExpenseTrendPoint> {
        if (periods.isEmpty()) return emptyList()
        val transactions = transactionRepository.getByPeriod(
            startInclusive = periods.minOf { it.startInclusive },
            endExclusive = periods.maxOf { it.endExclusive },
        )
        return calculate(periods, transactions)
    }

    /**
     * 用一次跨月观察同时计算多组趋势。
     *
     * 例如 6 个月趋势和 30 天趋势存在重叠区间，合并观察可避免每次流水变化都重复读取数据库。
     */
    fun observeGroups(
        periodGroups: List<List<ExpenseTrendPeriod>>,
    ): Flow<List<List<ExpenseTrendPoint>>> {
        val periods = periodGroups.flatten()
        require(periods.isNotEmpty()) { "Expense trend groups must not be empty." }
        return transactionRepository.observeByPeriod(
            startInclusive = periods.minOf { it.startInclusive },
            endExclusive = periods.maxOf { it.endExclusive },
        ).map { transactions ->
            periodGroups.map { group -> calculate(group, transactions) }
        }.flowOn(Dispatchers.Default)
    }

    /** 在已读取的流水快照上聚合，不进行数据库访问。 */
    fun calculate(
        periods: List<ExpenseTrendPeriod>,
        transactions: List<Transaction>,
    ): List<ExpenseTrendPoint> = periods.map { period ->
        // 趋势与首页「支出」/月总预算保持一致：按净支出（支出 − 收入）聚合，收入≥支出时为负（有结余）。
        var expense = 0L
        var income = 0L
        for (transaction in transactions) {
            if (transaction.dateTime < period.startInclusive ||
                transaction.dateTime >= period.endExclusive
            ) {
                continue
            }
            when (transaction.type) {
                TransactionType.EXPENSE -> {
                    require(transaction.amount <= Long.MAX_VALUE - expense) {
                        "Expense trend total exceeds Long range."
                    }
                    expense += transaction.amount
                }

                TransactionType.INCOME -> {
                    require(transaction.amount <= Long.MAX_VALUE - income) {
                        "Expense trend total exceeds Long range."
                    }
                    income += transaction.amount
                }
            }
        }
        ExpenseTrendPoint(key = period.key, amount = expense - income)
    }
}
