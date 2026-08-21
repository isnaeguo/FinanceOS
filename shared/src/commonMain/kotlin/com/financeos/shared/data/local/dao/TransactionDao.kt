package com.financeos.shared.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.financeos.shared.data.local.entity.TransactionEntity

/** 流水表的最小数据库访问接口。 */
@Dao
interface TransactionDao {
    /** 插入流水；重复 ID 视为调用错误。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TransactionEntity)

    /** 真正删除指定 ID 的记录，并返回受影响行数。 */
    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions ORDER BY date_time_epoch_millis DESC, id DESC")
    suspend fun getAll(): List<TransactionEntity>

    /** 使用半开时间区间查询月份，避免月末边界被重复计入。 */
    @Query(
        """
        SELECT * FROM transactions
        WHERE date_time_epoch_millis >= :startEpochMillis
          AND date_time_epoch_millis < :endEpochMillis
        ORDER BY date_time_epoch_millis DESC, id DESC
        """,
    )
    suspend fun getByPeriod(
        startEpochMillis: Long,
        endEpochMillis: Long,
    ): List<TransactionEntity>
}
