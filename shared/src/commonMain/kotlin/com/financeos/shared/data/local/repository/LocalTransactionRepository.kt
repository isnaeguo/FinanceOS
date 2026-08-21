package com.financeos.shared.data.local.repository

import com.financeos.shared.data.local.dao.TransactionDao
import com.financeos.shared.data.local.mapper.toDomain
import com.financeos.shared.data.local.mapper.toEntity
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlin.time.Instant

/** 使用 Room DAO 实现流水业务存取。 */
class LocalTransactionRepository(
    private val dao: TransactionDao,
) : TransactionRepository {
    override suspend fun add(transaction: Transaction) {
        dao.insert(transaction.toEntity())
    }

    override suspend fun delete(id: String): Boolean = dao.deleteById(id) > 0

    override suspend fun get(id: String): Transaction? = dao.getById(id)?.toDomain()

    override suspend fun getAll(): List<Transaction> = dao.getAll().map { it.toDomain() }

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
