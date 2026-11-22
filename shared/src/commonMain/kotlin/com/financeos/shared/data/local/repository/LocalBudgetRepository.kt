package com.financeos.shared.data.local.repository

import com.financeos.shared.data.local.dao.BudgetDao
import com.financeos.shared.data.local.mapper.categoryKey
import com.financeos.shared.data.local.mapper.toDomain
import com.financeos.shared.data.local.mapper.toEntity
import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.repository.BudgetRepository
import com.financeos.shared.domain.time.EpochClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers

/** 使用 Room DAO 实现月总预算和分类月预算存取。 */
class LocalBudgetRepository(
    private val dao: BudgetDao,
    private val clock: EpochClock = EpochClock.system,
) : BudgetRepository {
    override suspend fun get(
        month: BudgetMonth,
        categoryId: String?,
    ): Budget? {
        require(categoryId == null || categoryId.isNotBlank()) {
            "Budget categoryId must be null or non-blank."
        }
        return dao.get(
            year = month.year,
            month = month.month,
            categoryKey = categoryKey(categoryId),
        )?.toDomain()
    }

    override suspend fun getByMonth(month: BudgetMonth): List<Budget> = dao
        .getByMonth(year = month.year, month = month.month)
        .map { it.toDomain() }

    override fun observeAll(): Flow<List<Budget>> = dao.observeAll()
        .map { entities -> entities.map { it.toDomain() } }
        .flowOn(Dispatchers.Default)

    override fun observeByMonth(month: BudgetMonth): Flow<List<Budget>> = dao
        .observeByMonth(year = month.year, month = month.month)
        .map { entities -> entities.map { it.toDomain() } }
        .flowOn(Dispatchers.Default)

    /** 按预算范围原子保存，保存后继续使用此范围的已有 ID。 */
    @Throws(Exception::class)
    override suspend fun save(budget: Budget) {
        val now = clock.nowMillis()
        require(budget.deletedAt == null || budget.deletedAt <= now) {
            "Budget deletedAt must not be in the future."
        }
        dao.saveLocal(
            (if (budget.updatedAt == 0L) budget.copy(updatedAt = now) else budget).toEntity(),
        )
    }

    /** 软删指定范围的预算，使删除能随快照同步。 */
    @Throws(Exception::class)
    suspend fun delete(month: BudgetMonth, categoryId: String?): Boolean {
        require(categoryId == null || categoryId.isNotBlank()) {
            "Budget categoryId must be null or non-blank."
        }
        return dao.softDelete(month.year, month.month, categoryKey(categoryId), clock.nowMillis()) > 0
    }
}
