package com.financeos.shared.domain.repository

import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionAmount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/** 定义流水存取所需的最小业务能力，不暴露具体存储技术。 */
interface TransactionRepository {
    /** 新增一笔流水。 */
    @Throws(Exception::class)
    suspend fun add(transaction: Transaction)

    /** 按 ID 删除流水；实际删除成功时返回 `true`。 */
    @Throws(Exception::class)
    suspend fun delete(id: String): Boolean

    /** 按 ID 获取流水，不存在时返回 `null`。 */
    suspend fun get(id: String): Transaction?

    /** 获取全部流水。 */
    suspend fun getAll(): List<Transaction>

    /** 持续观察全部流水，使适配层等长期订阅方能与数据库保持一致。 */
    fun observeAll(): Flow<List<Transaction>>

    /** 获取任意半开时间区间内的流水，供趋势等跨月业务使用。 */
    suspend fun getByPeriod(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<Transaction>

    /**
     * 持续观察任意半开时间区间，供跨月趋势在流水变化后自动更新。
     *
     * Repository 仍只暴露 Domain Model，调用方不需要知道 Room 的失效追踪机制。
     */
    fun observeByPeriod(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<Transaction>>

    /** 读取或观察趋势所需字段，存储实现可直接投影数据库列。 */
    suspend fun getAmountsByPeriod(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<TransactionAmount> =
        getByPeriod(startInclusive, endExclusive).visibleAmounts()

    fun observeAmountsByPeriod(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<TransactionAmount>> =
        observeByPeriod(startInclusive, endExclusive).map { it.visibleAmounts() }

    /**
     * 获取指定本地月份对应的流水。
     *
     * 月份边界由调用方按照用户时区转换为 `startInclusive <= time < endExclusive` 的 [Instant] 区间，
     * 避免 Repository 隐式采用错误时区。
     */
    suspend fun getByMonth(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<Transaction>

    /**
     * 持续观察指定本地月份的流水。
     *
     * 新增或删除数据后会重新发出结果，使流水页以及未来 Dashboard 能共享同一响应式数据源。
     */
    fun observeByMonth(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<Transaction>>
}

private fun List<Transaction>.visibleAmounts(): List<TransactionAmount> = mapNotNull { entry ->
    if (entry.deletedAt == null) TransactionAmount(entry.amount, entry.type, entry.dateTime) else null
}
