package com.financeos.shared.domain.model

/**
 * 跨平台的预算月份。
 *
 * 使用简单的年、月数值，避免 shared 层依赖 JVM/Android 专属的日期类型。
 */
data class BudgetMonth(
    val year: Int,
    val month: Int,
) {
    init {
        require(year > 0) { "Budget year must be greater than zero." }
        require(month in 1..12) { "Budget month must be between 1 and 12." }
    }
}

/**
 * 某个月的总预算或分类预算。
 *
 * [categoryId] 为 `null` 时表示月总预算，否则表示对应分类的月预算。
 * [amountLimit] 使用与 [Transaction.amount] 相同的最小货币单位。
 */
data class Budget(
    val id: String,
    val month: BudgetMonth,
    val amountLimit: Long,
    val categoryId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Budget id must not be blank." }
        require(amountLimit >= 0) { "Budget amountLimit must not be negative." }
        require(categoryId == null || categoryId.isNotBlank()) {
            "Budget categoryId must be null or non-blank."
        }
    }
}
