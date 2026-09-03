package com.financeos.shared.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Room 中的流水存储结构，与业务模型独立演进。 */
@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["date_time_epoch_millis"]),
        Index(value = ["category_id"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,
    val type: String,
    @ColumnInfo(name = "category_id")
    val categoryId: String,
    @ColumnInfo(name = "account_id")
    val accountId: String?,
    @ColumnInfo(name = "date_time_epoch_millis")
    val dateTimeEpochMillis: Long,
    val note: String?,
    /** 最后修改时间（Unix 纪元毫秒），跨设备冲突裁决依据。 */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    /** 删除时间（Unix 纪元毫秒）；`NULL` 表示未删除，软删墓碑据此保留。 */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long?,
)
