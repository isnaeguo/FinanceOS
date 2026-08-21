package com.financeos.shared.domain.model

/**
 * 可完整带走的 FinanceOS 业务数据快照。
 *
 * 快照只包含跨平台 Domain Model，不暴露 Room Entity 或平台文件类型，可供 Android 与未来 iOS
 * 使用相同的导入、导出和恢复规则。
 */
data class FinanceDataSnapshot(
    val transactions: List<Transaction>,
    val categories: List<Category>,
    val budgets: List<Budget>,
)

/** 一次合并导入或完整恢复实际写入的数据数量。 */
data class FinanceDataImportResult(
    val transactionCount: Int,
    val categoryCount: Int,
    val budgetCount: Int,
)
