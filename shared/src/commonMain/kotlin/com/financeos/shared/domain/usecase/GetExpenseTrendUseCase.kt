package com.financeos.shared.domain.usecase

import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionAmount
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.repository.TransactionRepository
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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
        val amounts = transactionRepository.getAmountsByPeriod(
            startInclusive = periods.minOf { it.startInclusive },
            endExclusive = periods.maxOf { it.endExclusive },
        )
        return TrendPeriods(periods).calculateAmounts(amounts)
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
        val groups = periodGroups.map(::TrendPeriods)
        return transactionRepository.observeAmountsByPeriod(
            startInclusive = periods.minOf { it.startInclusive },
            endExclusive = periods.maxOf { it.endExclusive },
        ).map { amounts ->
            groups.map { group -> group.calculateAmounts(amounts) }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    /** 在已读取的流水快照上聚合，不进行数据库访问。 */
    fun calculate(
        periods: List<ExpenseTrendPeriod>,
        transactions: List<Transaction>,
    ): List<ExpenseTrendPoint> = TrendPeriods(periods).aggregate(
        transactions,
        dateTime = { if (it.deletedAt == null) it.dateTime else null },
        type = Transaction::type,
        amount = Transaction::amount,
    )
}

/** 订阅期间只计算一次时间桶顺序，各次更新仅分配整型累计值。 */
private class TrendPeriods(periods: List<ExpenseTrendPeriod>) {
    private val original = periods.toList()
    private val chronological = original.indices.sortedBy { original[it].startInclusive }.toIntArray()
    private val disjoint = (1 until chronological.size).all { index ->
        original[chronological[index - 1]].endExclusive <= original[chronological[index]].startInclusive
    }

    fun calculateAmounts(amounts: List<TransactionAmount>): List<ExpenseTrendPoint> =
        aggregate(amounts, TransactionAmount::dateTime, TransactionAmount::type, TransactionAmount::amount)

    inline fun <T> aggregate(
        entries: List<T>,
        dateTime: (T) -> Instant?,
        type: (T) -> TransactionType,
        amount: (T) -> Long,
    ): List<ExpenseTrendPoint> {
        if (original.isEmpty()) return emptyList()
        val expense = LongArray(original.size)
        val income = LongArray(original.size)
        for (entry in entries) {
            val time = dateTime(entry) ?: continue
            if (disjoint) {
                val position = findBucket(time)
                if (position >= 0) accumulate(expense, income, position, type(entry), amount(entry))
            } else {
                for (position in chronological) {
                    val period = original[position]
                    if (time < period.startInclusive) break
                    if (time < period.endExclusive) {
                        accumulate(expense, income, position, type(entry), amount(entry))
                    }
                }
            }
        }
        return original.mapIndexed { index, period ->
            ExpenseTrendPoint(period.key, expense[index] - income[index])
        }
    }

    private fun findBucket(time: Instant): Int {
        var low = 0
        var high = chronological.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (original[chronological[middle]].startInclusive <= time) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        if (low == 0) return -1
        val position = chronological[low - 1]
        return if (time < original[position].endExclusive) position else -1
    }

    private fun accumulate(
        expense: LongArray, income: LongArray, position: Int, type: TransactionType, amount: Long,
    ) {
        val totals = if (type == TransactionType.EXPENSE) expense else income
        val total = totals[position]
        require(amount <= Long.MAX_VALUE - total) { "Expense trend total exceeds Long range." }
        totals[position] = total + amount
    }
}
