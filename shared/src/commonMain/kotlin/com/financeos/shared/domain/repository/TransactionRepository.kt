package com.financeos.shared.domain.repository

import com.financeos.shared.domain.model.Transaction
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** 定义流水存取所需的最小业务能力，不暴露具体存储技术。 */
interface TransactionRepository {
    /** 新增一笔流水。 */
    suspend fun add(transaction: Transaction)

    /** 按 ID 删除流水；实际删除成功时返回 `true`。 */
    suspend fun delete(id: String): Boolean

    /** 按 ID 获取流水，不存在时返回 `null`。 */
    suspend fun get(id: String): Transaction?

    /** 获取全部流水。 */
    suspend fun getAll(): List<Transaction>

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
