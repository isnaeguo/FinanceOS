package com.financeos.shared.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.financeos.shared.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

/** 流水表的最小数据库访问接口。 */
@Dao
interface TransactionDao {
    /**
     * 写入流水；同 ID 已存在时整体覆盖。
     *
     * 覆盖是软删模型的一部分：编辑流水先软删旧记录再以同 ID 写回，必须能覆盖墓碑复活记录；
     * 合并导入则统一走 [upsertAll]。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TransactionEntity)

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
        SET deleted_at = :deletedAt, updated_at = :updatedAt
        WHERE id = :id AND deleted_at IS NULL
        """,
    )
    suspend fun softDeleteById(
        id: String,
        deletedAt: Long,
        updatedAt: Long,
    ): Int

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
}
