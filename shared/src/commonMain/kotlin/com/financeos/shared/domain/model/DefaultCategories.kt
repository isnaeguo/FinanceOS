package com.financeos.shared.domain.model

/**
 * FinanceOS v0.1 内置分类。
 *
 * 固定 ID 用于保证本地数据升级时仍能识别同一系统分类；展示名称和图标由后续迁移单独处理。
 */
object DefaultCategories {
    val all: List<Category> = listOf(
        Category("system-food", "餐饮", CategoryType.EXPENSE, "food", isSystem = true),
        Category("system-transport", "交通", CategoryType.EXPENSE, "transport", isSystem = true),
        Category("system-shopping", "购物", CategoryType.EXPENSE, "shopping", isSystem = true),
        Category("system-entertainment", "娱乐", CategoryType.EXPENSE, "entertainment", isSystem = true),
        Category("system-digital", "数码", CategoryType.EXPENSE, "digital", isSystem = true),
        Category("system-learning", "学习", CategoryType.EXPENSE, "learning", isSystem = true),
        Category("system-travel", "旅行", CategoryType.EXPENSE, "travel", isSystem = true),
        Category("system-daily-needs", "日用品", CategoryType.EXPENSE, "daily-needs", isSystem = true),
        Category("system-income", "工资/生活费", CategoryType.INCOME, "income", isSystem = true),
        Category("system-other", "其他", CategoryType.COMMON, "other", isSystem = true),
    )
}
