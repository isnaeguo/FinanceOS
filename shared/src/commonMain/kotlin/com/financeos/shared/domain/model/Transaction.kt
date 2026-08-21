package com.financeos.shared.domain.model

import kotlin.time.Instant

/**
 * 一笔收入或支出流水。
 *
 * 金额始终保存为正的最小货币单位，收支方向仅由 [type] 决定，避免负金额与类型组合产生歧义。
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
) {
    init {
        require(id.isNotBlank()) { "Transaction id must not be blank." }
        require(amount > 0) { "Transaction amount must be greater than zero." }
        require(categoryId.isNotBlank()) { "Transaction categoryId must not be blank." }
        require(accountId == null || accountId.isNotBlank()) {
            "Transaction accountId must be null or non-blank."
        }
    }
}

/** 流水的资金方向。 */
enum class TransactionType {
    INCOME,
    EXPENSE,
}
