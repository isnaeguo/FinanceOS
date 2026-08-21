package com.financeos.shared.data.local.dao

import androidx.room3.Dao
import androidx.room3.Query
import com.financeos.shared.data.local.entity.CategoryEntity

/** 分类表的只读数据库访问接口。 */
@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CategoryEntity?

    @Query("SELECT * FROM categories ORDER BY id")
    suspend fun getAll(): List<CategoryEntity>
}
