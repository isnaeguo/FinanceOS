package com.financeos.shared.data.local.repository

import com.financeos.shared.data.local.dao.TransactionDao
import com.financeos.shared.data.local.mapper.toDomain
import com.financeos.shared.data.local.mapper.toEntity
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.repository.TransactionRepository
import com.financeos.shared.domain.time.EpochClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlin.time.Instant

/** 使用 Room DAO 实现流水业务存取。 */
class LocalTransactionRepository(
    private val dao: TransactionDao,
    private val clock: EpochClock = EpochClock.system,
) : TransactionRepository {
    /**
     * 写入流水。写入即视为一次本地修改，无条件打上当前时间作为最后修改时间；
     * 同 ID 已存在（含软删墓碑）时覆盖写回，编辑流水因此可以先删后加。
     */
    override suspend fun add(transaction: Transaction) {
        val now = clock.nowMillis()
        require(transaction.deletedAt == null || transaction.deletedAt <= now) {
            "Transaction deletedAt must not be in the future."
        }
        dao.insert(transaction.copy(updatedAt = now).toEntity())
    }

    /**
     * 软删流水：写入 `deleted_at = now` 的墓碑并同步刷新 `updated_at`，记录本身保留，
     * 使删除能随快照传播到其他设备；返回 `true` 表示该流水此前存在且未删除。
     */
    override suspend fun delete(id: String): Boolean {
        val now = clock.nowMillis()
        return dao.softDeleteById(id, deletedAt = now, updatedAt = now) > 0
    }

    override suspend fun get(id: String): Transaction? = dao.getById(id)?.toDomain()

    override suspend fun getAll(): List<Transaction> = dao.getAll().map { it.toDomain() }

    override fun observeAll(): Flow<List<Transaction>> = dao.observeAll()
        .map { entities -> entities.map { it.toDomain() } }
        .flowOn(Dispatchers.Default)

    override suspend fun getByPeriod(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<Transaction> {
        require(startInclusive < endExclusive) {
            "Transaction period must have a positive duration."
        }
        return dao.getByPeriod(
            startEpochMillis = startInclusive.toEpochMilliseconds(),
            endEpochMillis = endExclusive.toEpochMilliseconds(),
        ).map { it.toDomain() }
    }

    override fun observeByPeriod(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<Transaction>> {
        require(startInclusive < endExclusive) {
            "Transaction period must have a positive duration."
        }
        return dao.observeByPeriod(
            startEpochMillis = startInclusive.toEpochMilliseconds(),
            endEpochMillis = endExclusive.toEpochMilliseconds(),
        )
            .map { entities -> entities.map { it.toDomain() } }
            // 大批量 Entity 映射不应占用 Android 主线程，避免列表或首页更新时阻塞绘制。
            .flowOn(Dispatchers.Default)
    }

    override suspend fun getByMonth(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<Transaction> {
        require(startInclusive < endExclusive) {
            "Transaction month range must have a positive duration."
        }
        return getByPeriod(startInclusive, endExclusive)
    }

    override fun observeByMonth(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<Transaction>> {
        require(startInclusive < endExclusive) {
            "Transaction month range must have a positive duration."
        }
        return observeByPeriod(startInclusive, endExclusive)
    }
}
