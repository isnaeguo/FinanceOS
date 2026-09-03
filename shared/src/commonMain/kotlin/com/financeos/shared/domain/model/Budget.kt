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
 *
 * [updatedAt] 是跨设备冲突裁决的唯一依据；[deletedAt] 非 `null` 表示该预算已被软删，
 * 记录本身作为墓碑保留，用于把删除操作传播到其他设备。
 */
data class Budget(
    val id: String,
    val month: BudgetMonth,
    val amountLimit: Long,
    val categoryId: String? = null,
    /** 最后修改时间（Unix 纪元毫秒）。 */
    val updatedAt: Long = 0,
    /** 删除时间（Unix 纪元毫秒）；`null` 表示未删除。 */
    val deletedAt: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "Budget id must not be blank." }
        require(amountLimit >= 0) { "Budget amountLimit must not be negative." }
        require(categoryId == null || categoryId.isNotBlank()) {
            "Budget categoryId must be null or non-blank."
        }
        require(updatedAt >= 0) { "Budget updatedAt must not be negative." }
        require(deletedAt == null || deletedAt >= 0) {
            "Budget deletedAt must not be negative."
        }
    }
}
