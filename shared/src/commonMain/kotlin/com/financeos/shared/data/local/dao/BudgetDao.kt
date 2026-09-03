package com.financeos.shared.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.financeos.shared.data.local.entity.BudgetEntity
import kotlinx.coroutines.flow.Flow

/** 预算表的最小数据库访问接口。 */
@Dao
interface BudgetDao {
    /** 同一月份和分类范围只保留一条预算。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: BudgetEntity)

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
