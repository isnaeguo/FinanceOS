package com.financeos.shared.data.local.dao

import androidx.room3.Dao
import androidx.room3.ColumnInfo
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.financeos.shared.data.local.entity.CategoryEntity
import com.financeos.shared.data.local.entity.TransactionEntity
import com.financeos.shared.data.local.nextLocalRevision
import kotlinx.coroutines.flow.Flow

/** 流水表的最小数据库访问接口。 */
@Dao
interface TransactionDao {
    /**
     * 写入流水；同 ID 已存在时整体覆盖。
     *
     * 合并导入统一使用 [upsertAll]，本地编辑通过 [saveLocal] 写入。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TransactionEntity)

    @Transaction
    suspend fun saveLocal(entity: TransactionEntity) {
        val previous = getIncludingDeleted(entity.id)
        val updatedAt = previous?.let { nextLocalRevision(it.updatedAt, entity.updatedAt) } ?: entity.updatedAt
        insert(entity.copy(updatedAt = updatedAt))
    }

    @Transaction
    suspend fun updateLocal(expected: TransactionEntity, replacement: TransactionEntity): Boolean {
        val current = getById(expected.id) ?: return false
        if (!current.matchesExpected(expected) || !hasActiveCategory(replacement.categoryId, replacement.type)) {
            return false
        }
        insert(replacement.copy(updatedAt = nextLocalRevision(current.updatedAt, replacement.updatedAt)))
        return true
    }

    /** 流水版本校验、创建分类和分配分类为同一次写入。 */
    @Transaction
    suspend fun assignNewCategory(expected: TransactionEntity, category: CategoryEntity): Boolean {
        val current = getById(expected.id) ?: return false
        if (!current.matchesExpected(expected)) return false

        require(activeCategoryNames().none { it.trim().equals(category.name, ignoreCase = true) }) {
            "已存在同名分类，请直接选择或使用其他名称。"
        }
        require(insertCategoryForAssignment(category) != -1L) { "分类标识已存在，请重试。" }
        insert(
            current.copy(
                categoryId = category.id,
                updatedAt = nextLocalRevision(current.updatedAt, category.updatedAt),
            ),
        )
        return true
    }

    @Query("SELECT name FROM categories WHERE deleted_at IS NULL")
    suspend fun activeCategoryNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategoryForAssignment(category: CategoryEntity): Long

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getIncludingDeleted(id: String): TransactionEntity?

    /** 导入外部账单时保留已经编辑或软删的记录。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(entities: List<TransactionEntity>): List<Long>

    /** 按 ID 写入或更新，不影响未涉及的流水；墓碑与活跃记录都由调用方裁决后写入。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<TransactionEntity>)

    /**
     * 软删指定 ID 的未删除记录：写入删除墓碑并同步刷新修改时间，返回受影响行数。
     *
     * 已是墓碑的记录不会再被更新，保证重复删除返回 `0`。
     */
    @Query(
        """
        UPDATE transactions
        SET deleted_at = :deletedAt,
            updated_at = MAX(:updatedAt, updated_at + (updated_at < 9223372036854775807))
        WHERE id = :id AND deleted_at IS NULL
        """,
    )
    suspend fun softDeleteById(
        id: String,
        deletedAt: Long,
        updatedAt: Long,
    ): Int

    @Query(
        """
        SELECT EXISTS (
            SELECT 1 FROM categories
            WHERE id = :categoryId AND deleted_at IS NULL AND (type = :type OR type = 'COMMON')
        )
        """,
    )
    suspend fun hasActiveCategory(categoryId: String, type: String): Boolean

    /** 物理清空整表，仅用于备份恢复前重置数据。 */
    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    /** 按 ID 获取未删除的流水，已删除或不存在时返回 `null`。 */
    @Query("SELECT * FROM transactions WHERE id = :id AND deleted_at IS NULL LIMIT 1")
    suspend fun getById(id: String): TransactionEntity?

    /** 获取全部未删除的流水。 */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted_at IS NULL
        ORDER BY date_time_epoch_millis DESC, id DESC
        """,
    )
    suspend fun getAll(): List<TransactionEntity>

    /** 观察全部未删除的流水，供跨端适配层把响应式查询接到平台状态上。 */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted_at IS NULL
        ORDER BY date_time_epoch_millis DESC, id DESC
        """,
    )
    fun observeAll(): Flow<List<TransactionEntity>>

    /** 获取全部流水（含软删墓碑），仅用于导出与合并；删除传播依赖墓碑随快照流动。 */
    @Query("SELECT * FROM transactions ORDER BY date_time_epoch_millis DESC, id DESC")
    suspend fun getAllIncludingDeleted(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id IN (:ids)")
    suspend fun getByIdsIncludingDeleted(ids: List<String>): List<TransactionEntity>

    @Query("SELECT id FROM transactions WHERE id IN (:ids)")
    suspend fun getExistingIds(ids: List<String>): List<String>

    /** 使用半开时间区间查询月份，避免月末边界被重复计入。 */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted_at IS NULL
          AND date_time_epoch_millis >= :startEpochMillis
          AND date_time_epoch_millis < :endEpochMillis
        ORDER BY date_time_epoch_millis DESC, id DESC
        """,
    )
    suspend fun getByPeriod(
        startEpochMillis: Long,
        endEpochMillis: Long,
    ): List<TransactionEntity>

    /** 观察半开时间区间；表发生增删时由 Room 自动重新查询。 */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted_at IS NULL
          AND date_time_epoch_millis >= :startEpochMillis
          AND date_time_epoch_millis < :endEpochMillis
        ORDER BY date_time_epoch_millis DESC, id DESC
        """,
    )
    fun observeByPeriod(
        startEpochMillis: Long,
        endEpochMillis: Long,
    ): Flow<List<TransactionEntity>>

    @Query(PERIOD_AMOUNTS_SQL)
    suspend fun getAmountsByPeriod(startEpochMillis: Long, endEpochMillis: Long): List<PeriodAmountRow>

    @Query(PERIOD_AMOUNTS_SQL)
    fun observeAmountsByPeriod(startEpochMillis: Long, endEpochMillis: Long): Flow<List<PeriodAmountRow>>

    @Query(
        """
        SELECT COALESCE(SUM(amount_minor), 0) AS spent,
            COALESCE(SUM(CASE WHEN date_time_epoch_millis < :todayEpochMillis THEN amount_minor ELSE 0 END), 0) AS spent_before_today
        FROM transactions
        WHERE deleted_at IS NULL AND type = 'EXPENSE'
          AND date_time_epoch_millis >= :startEpochMillis AND date_time_epoch_millis < :endEpochMillis
        """,
    )
    suspend fun expenseTotals(startEpochMillis: Long, endEpochMillis: Long, todayEpochMillis: Long): PeriodExpenseTotals
}

private fun TransactionEntity.matchesExpected(expected: TransactionEntity): Boolean =
    if (updatedAt >= 0) this == expected else copy(updatedAt = 0) == expected

// 趋势按时间分桶，查询侧不需要为完整记录排序。
private const val PERIOD_AMOUNTS_SQL = """
    SELECT date_time_epoch_millis, amount_minor, type FROM transactions
    WHERE deleted_at IS NULL
      AND date_time_epoch_millis >= :startEpochMillis AND date_time_epoch_millis < :endEpochMillis
"""

data class PeriodAmountRow(
    @ColumnInfo(name = "date_time_epoch_millis") val epochMillis: Long,
    @ColumnInfo(name = "amount_minor") val amount: Long,
    val type: String,
)

data class PeriodExpenseTotals(
    val spent: Long,
    @ColumnInfo(name = "spent_before_today") val spentBeforeToday: Long,
)
