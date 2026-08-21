package com.financeos.shared.domain.model

/**
 * 用于组织流水的业务分类。
 *
 * [iconKey] 是跨平台语义键，Android 与 iOS 分别负责将它映射为平台原生图标。
 */
data class Category(
    val id: String,
    val name: String,
    val type: CategoryType,
    val iconKey: String,
    val isSystem: Boolean,
) {
    init {
        require(id.isNotBlank()) { "Category id must not be blank." }
        require(name.isNotBlank()) { "Category name must not be blank." }
        require(iconKey.isNotBlank()) { "Category iconKey must not be blank." }
    }
}

/** 分类允许关联的流水方向。 */
enum class CategoryType {
    INCOME,
    EXPENSE,
    COMMON,
}
