package com.financeos.shared.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Room 中的月预算存储结构。 */
@Entity(
    tableName = "budgets",
    indices = [
        Index(
            value = ["year", "month", "category_key"],
            unique = true,
        ),
    ],
)
data class BudgetEntity(
    @PrimaryKey
    val id: String,
    val year: Int,
    val month: Int,
    @ColumnInfo(name = "category_key")
    val categoryKey: String,
    @ColumnInfo(name = "amount_limit_minor")
    val amountLimitMinor: Long,
    /** 最后修改时间（Unix 纪元毫秒），跨设备冲突裁决依据。 */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    /** 删除时间（Unix 纪元毫秒）；`NULL` 表示未删除，软删墓碑据此保留。 */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long?,
)
