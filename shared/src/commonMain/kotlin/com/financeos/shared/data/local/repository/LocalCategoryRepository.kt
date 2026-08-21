package com.financeos.shared.data.local.repository

import com.financeos.shared.data.local.dao.CategoryDao
import com.financeos.shared.data.local.mapper.toDomain
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.repository.CategoryRepository

/** 使用 Room DAO 实现分类读取。 */
class LocalCategoryRepository(
    private val dao: CategoryDao,
) : CategoryRepository {
    override suspend fun get(id: String): Category? = dao.getById(id)?.toDomain()

    override suspend fun getAll(): List<Category> = dao.getAll().map { it.toDomain() }
}
