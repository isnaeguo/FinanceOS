package com.financeos.app.data

import android.content.Context
import com.financeos.shared.data.local.FinanceOsDatabase
import com.financeos.shared.data.local.createFinanceOsDatabase
import com.financeos.shared.data.local.repository.LocalBudgetRepository
import com.financeos.shared.data.local.repository.LocalCategoryRepository
import com.financeos.shared.data.local.repository.LocalFinanceDataRepository
import com.financeos.shared.data.local.repository.LocalTransactionRepository
import com.financeos.shared.data.transfer.FinanceDataTransferService
import com.financeos.shared.domain.model.FinanceDataImportResult
import com.financeos.shared.domain.repository.BudgetRepository
import com.financeos.shared.domain.repository.CategoryRepository
import com.financeos.shared.domain.repository.TransactionRepository

/**
 * 进程级 FinanceOS 数据组合根。
 *
 * FinanceOsAppContainer 只在 Compose 页面请求 ViewModel 时才初始化；小组件广播、局域网共享服务等
 * 没有 Activity Context 的场景直接通过 [get] 取用同一套数据库与仓库，保证任意入口读写同一数据文件。
 */
class FinanceDataBridge internal constructor(
    val database: FinanceOsDatabase,
) {
    val transactionRepository: TransactionRepository = LocalTransactionRepository(database.transactionDao())
    val budgetRepository: BudgetRepository = LocalBudgetRepository(database.budgetDao())
    val categoryRepository: CategoryRepository = LocalCategoryRepository(database.categoryDao())

    private val transferService = FinanceDataTransferService(
        repository = LocalFinanceDataRepository(database),
    )

    /** 导出当前完整数据快照文本（financeos-backup 格式）。 */
    suspend fun exportSnapshotJson(): String = transferService.exportJson()

    /** 按业务 ID 合并导入文本并返回实际写入笔数。 */
    suspend fun mergeImportJson(content: String): FinanceDataImportResult =
        transferService.importJson(content)

    companion object {
        @Volatile
        private var instance: FinanceDataBridge? = null

        /** 从任意 [Context] 获取进程内共享的数据桥；applicationContext 每进程只有一个。 */
        @JvmStatic
        @Synchronized
        fun get(context: Context): FinanceDataBridge {
            instance?.let { return it }
            return FinanceDataBridge(
                database = createFinanceOsDatabase(context.applicationContext),
            ).also { instance = it }
        }
    }
}
