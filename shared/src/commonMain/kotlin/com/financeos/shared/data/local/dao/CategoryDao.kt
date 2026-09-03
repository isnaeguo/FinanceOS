package com.financeos.shared.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.financeos.shared.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

/** 分类表的数据库访问接口；写入能力仅用于导入和恢复。 */
@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CategoryEntity>)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()

    /** 按 ID 获取未删除的分类，已删除或不存在时返回 `null`。 */
    @Query("SELECT * FROM categories WHERE id = :id AND deleted_at IS NULL LIMIT 1")
    suspend fun getById(id: String): CategoryEntity?

    /** 获取全部未删除的分类。 */
    @Query("SELECT * FROM categories WHERE deleted_at IS NULL ORDER BY id")
    suspend fun getAll(): List<CategoryEntity>

    /** 获取全部分类（含软删墓碑），仅用于导出、合并与引用校验。 */
    @Query("SELECT * FROM categories ORDER BY id")
    suspend fun getAllIncludingDeleted(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE deleted_at IS NULL ORDER BY id")
    fun observeAll(): Flow<List<CategoryEntity>>
}
