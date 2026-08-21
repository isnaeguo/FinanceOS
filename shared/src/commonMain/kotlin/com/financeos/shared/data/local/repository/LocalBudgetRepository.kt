package com.financeos.shared.data.local.repository

import com.financeos.shared.data.local.dao.BudgetDao
import com.financeos.shared.data.local.mapper.categoryKey
import com.financeos.shared.data.local.mapper.toDomain
import com.financeos.shared.data.local.mapper.toEntity
import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.repository.BudgetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 使用 Room DAO 实现月总预算和分类月预算存取。 */
class LocalBudgetRepository(
    private val dao: BudgetDao,
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

    override fun observeByMonth(month: BudgetMonth): Flow<List<Budget>> = dao
        .observeByMonth(year = month.year, month = month.month)
        .map { entities -> entities.map { it.toDomain() } }

    override suspend fun save(budget: Budget) {
        dao.save(budget.toEntity())
    }
}
