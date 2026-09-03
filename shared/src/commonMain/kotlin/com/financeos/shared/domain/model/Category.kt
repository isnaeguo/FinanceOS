package com.financeos.shared.domain.model

/**
 * 用于组织流水的业务分类。
 *
 * [iconKey] 是跨平台语义键，Android 与 iOS 分别负责将它映射为平台原生图标。
 *
 * [updatedAt] 是跨设备冲突裁决的唯一依据；[deletedAt] 非 `null` 表示该分类已被软删，
 * 记录本身作为墓碑保留，用于把删除操作传播到其他设备。
 */
data class Category(
    val id: String,
    val name: String,
    val type: CategoryType,
    val iconKey: String,
    val isSystem: Boolean,
    /** 最后修改时间（Unix 纪元毫秒）。 */
    val updatedAt: Long = 0,
    /** 删除时间（Unix 纪元毫秒）；`null` 表示未删除。 */
    val deletedAt: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "Category id must not be blank." }
        require(name.isNotBlank()) { "Category name must not be blank." }
        require(iconKey.isNotBlank()) { "Category iconKey must not be blank." }
        require(updatedAt >= 0) { "Category updatedAt must not be negative." }
        require(deletedAt == null || deletedAt >= 0) {
            "Category deletedAt must not be negative."
        }
    }
}

/** 分类允许关联的流水方向。 */
enum class CategoryType {
    INCOME,
    EXPENSE,
    COMMON,
}
