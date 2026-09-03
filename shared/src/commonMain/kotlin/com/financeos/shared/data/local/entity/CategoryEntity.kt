package com.financeos.shared.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** Room 中的分类存储结构。 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val type: String,
    @ColumnInfo(name = "icon_key")
    val iconKey: String,
    @ColumnInfo(name = "is_system")
    val isSystem: Boolean,
    /** 最后修改时间（Unix 纪元毫秒），跨设备冲突裁决依据。 */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    /** 删除时间（Unix 纪元毫秒）；`NULL` 表示未删除，软删墓碑据此保留。 */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long?,
)
