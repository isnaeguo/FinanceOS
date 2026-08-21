package com.financeos.shared.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.financeos.shared.data.local.entity.BudgetEntity

/** 预算表的最小数据库访问接口。 */
@Dao
interface BudgetDao {
    /** 同一月份和分类范围只保留一条预算。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: BudgetEntity)

    @Query(
        """
        SELECT * FROM budgets
        WHERE year = :year AND month = :month AND category_key = :categoryKey
        LIMIT 1
        """,
    )
    suspend fun get(
        year: Int,
        month: Int,
        categoryKey: String,
    ): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE year = :year AND month = :month ORDER BY category_key")
    suspend fun getByMonth(
        year: Int,
        month: Int,
    ): List<BudgetEntity>
}
