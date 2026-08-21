package com.financeos.shared.domain.repository

import com.financeos.shared.domain.model.FinanceDataImportResult
import com.financeos.shared.domain.model.FinanceDataSnapshot

/** 定义完整数据快照、合并导入与原子恢复所需的最小业务能力。 */
interface FinanceDataRepository {
    /** 读取当前全部流水、分类和预算，不包含数据库实现细节。 */
    suspend fun snapshot(): FinanceDataSnapshot

    /** 按业务 ID 合并数据；相同 ID 使用导入内容更新，未涉及的数据保持不变。 */
    suspend fun merge(snapshot: FinanceDataSnapshot): FinanceDataImportResult

    /** 在单个数据库事务中用快照完整替换现有数据。 */
    suspend fun replaceAll(snapshot: FinanceDataSnapshot): FinanceDataImportResult
}
