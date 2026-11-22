package com.financeos.shared.domain.repository

import com.financeos.shared.domain.model.FinanceDataImportResult
import com.financeos.shared.domain.model.FinanceDataSnapshot
import com.financeos.shared.domain.model.Transaction

/** 定义完整数据快照、合并导入与原子恢复所需的最小业务能力。 */
interface FinanceDataRepository {
    /** 读取当前全部流水、分类和预算，不包含数据库实现细节。 */
    @Throws(Exception::class)
    suspend fun snapshot(): FinanceDataSnapshot

    /** 按业务键合并数据，保存冲突裁决后的版本。 */
    @Throws(Exception::class)
    suspend fun merge(snapshot: FinanceDataSnapshot): FinanceDataImportResult

    /** 导入外部流水，保留本机已存在的 ID，包括已软删的流水。 */
    @Throws(Exception::class)
    suspend fun importTransactions(transactions: List<Transaction>): FinanceDataImportResult

    /** 在单个数据库事务中用快照完整替换现有数据。 */
    @Throws(Exception::class)
    suspend fun replaceAll(snapshot: FinanceDataSnapshot): FinanceDataImportResult
}
