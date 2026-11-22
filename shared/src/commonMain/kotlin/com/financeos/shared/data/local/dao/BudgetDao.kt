package com.financeos.shared.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.financeos.shared.data.local.entity.BudgetEntity
import com.financeos.shared.data.local.nextLocalRevision
import kotlinx.coroutines.flow.Flow

/** 预算表的最小数据库访问接口。 */
@Dao
interface BudgetDao {
    /** 同一月份和分类范围只保留一条预算。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: BudgetEntity)

    @Transaction
    suspend fun saveLocal(entity: BudgetEntity) {
        val existing = getIncludingDeleted(entity.year, entity.month, entity.categoryKey)
        val id = existing?.id ?: entity.id
        if (existing == null) {
            require(getByIdIncludingDeleted(id) == null) {
                "Budget ID already belongs to a different month or category."
            }
        }
        val updatedAt = existing?.let { nextLocalRevision(it.updatedAt, entity.updatedAt) } ?: entity.updatedAt
        save(entity.copy(id = id, updatedAt = updatedAt))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<BudgetEntity>)

    @Query("DELETE FROM budgets")
    suspend fun deleteAll()

    /** 获取全部未删除的预算。 */
    @Query("SELECT * FROM budgets WHERE deleted_at IS NULL ORDER BY year, month, category_key")
    suspend fun getAll(): List<BudgetEntity>

    /** 观察全部未删除的预算，供跨端适配层把响应式查询接到平台状态上。 */
    @Query("SELECT * FROM budgets WHERE deleted_at IS NULL ORDER BY year, month, category_key")
    fun observeAll(): Flow<List<BudgetEntity>>

    /** 获取全部预算（含软删墓碑），仅用于导出与合并。 */
    @Query("SELECT * FROM budgets ORDER BY year, month, category_key")
    suspend fun getAllIncludingDeleted(): List<BudgetEntity>

    @Query("SELECT * FROM budgets WHERE year = :year AND month = :month AND category_key = :categoryKey LIMIT 1")
    suspend fun getIncludingDeleted(year: Int, month: Int, categoryKey: String): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE id = :id LIMIT 1")
    suspend fun getByIdIncludingDeleted(id: String): BudgetEntity?

    @Query(
        """
        UPDATE budgets
        SET deleted_at = :now,
            updated_at = MAX(:now, updated_at + (updated_at < 9223372036854775807))
        WHERE year = :year AND month = :month AND category_key = :categoryKey AND deleted_at IS NULL
        """,
    )
    suspend fun softDelete(year: Int, month: Int, categoryKey: String, now: Long): Int

    /** 获取指定月份和分类的未删除预算。 */
    @Query(
        """
        SELECT * FROM budgets
        WHERE deleted_at IS NULL
          AND year = :year AND month = :month AND category_key = :categoryKey
        LIMIT 1
        """,
    )
    suspend fun get(
        year: Int,
        month: Int,
        categoryKey: String,
    ): BudgetEntity?

    /** 获取指定月份的全部未删除预算。 */
    @Query(
        """
        SELECT * FROM budgets
        WHERE deleted_at IS NULL
          AND year = :year AND month = :month
        ORDER BY category_key
        """,
    )
    suspend fun getByMonth(
        year: Int,
        month: Int,
    ): List<BudgetEntity>

    /** 预算表变化后重新发出指定月份的真实结果。 */
    @Query(
        """
        SELECT * FROM budgets
        WHERE deleted_at IS NULL
          AND year = :year AND month = :month
        ORDER BY category_key
        """,
    )
    fun observeByMonth(
        year: Int,
        month: Int,
    ): Flow<List<BudgetEntity>>
}
