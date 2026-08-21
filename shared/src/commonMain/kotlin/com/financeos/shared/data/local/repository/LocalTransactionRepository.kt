package com.financeos.shared.data.local.repository

import com.financeos.shared.data.local.dao.TransactionDao
import com.financeos.shared.data.local.mapper.toDomain
import com.financeos.shared.data.local.mapper.toEntity
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.repository.TransactionRepository
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

    override suspend fun getByMonth(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<Transaction> {
        require(startInclusive < endExclusive) {
            "Transaction month range must have a positive duration."
        }
        return dao.getByPeriod(
            startEpochMillis = startInclusive.toEpochMilliseconds(),
            endEpochMillis = endExclusive.toEpochMilliseconds(),
        ).map { it.toDomain() }
    }
}
