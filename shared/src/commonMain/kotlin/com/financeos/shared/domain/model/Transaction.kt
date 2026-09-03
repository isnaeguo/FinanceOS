package com.financeos.shared.domain.model

import kotlin.time.Instant

/**
 * 一笔收入或支出流水。
 *
 * 金额始终保存为正的最小货币单位，收支方向仅由 [type] 决定，避免负金额与类型组合产生歧义。
 *
 * [updatedAt] 是跨设备冲突裁决的唯一依据；[deletedAt] 非 `null` 表示该流水已被软删，
 * 记录本身作为墓碑保留，用于把删除操作传播到其他设备。
 */
data class Transaction(
    val id: String,
    /** 用户货币的最小单位金额，例如人民币的“分”。 */
    val amount: Long,
    val type: TransactionType,
    val categoryId: String,
    val accountId: String? = null,
    val dateTime: Instant,
    val note: String? = null,
    /** 最后修改时间（Unix 纪元毫秒）。 */
    val updatedAt: Long = 0,
    /** 删除时间（Unix 纪元毫秒）；`null` 表示未删除。 */
    val deletedAt: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "Transaction id must not be blank." }
        require(amount > 0) { "Transaction amount must be greater than zero." }
        require(categoryId.isNotBlank()) { "Transaction categoryId must not be blank." }
        require(accountId == null || accountId.isNotBlank()) {
            "Transaction accountId must be null or non-blank."
        }
        require(updatedAt >= 0) { "Transaction updatedAt must not be negative." }
        require(deletedAt == null || deletedAt >= 0) {
            "Transaction deletedAt must not be negative."
        }
    }
}

/** 流水的资金方向。 */
enum class TransactionType {
    INCOME,
    EXPENSE,
}
