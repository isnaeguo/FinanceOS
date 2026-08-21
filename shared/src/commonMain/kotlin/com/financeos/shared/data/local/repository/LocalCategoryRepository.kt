package com.financeos.shared.data.local.repository

import com.financeos.shared.data.local.dao.CategoryDao
import com.financeos.shared.data.local.mapper.toDomain
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers

/** 使用 Room DAO 实现分类读取。 */
class LocalCategoryRepository(
    private val dao: CategoryDao,
) : CategoryRepository {
    override suspend fun get(id: String): Category? = dao.getById(id)?.toDomain()

    override suspend fun getAll(): List<Category> = dao.getAll().map { it.toDomain() }

    override fun observeAll(): Flow<List<Category>> = dao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }.flowOn(Dispatchers.Default)
}
