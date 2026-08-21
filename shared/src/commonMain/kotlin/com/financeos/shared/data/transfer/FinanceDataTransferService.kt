package com.financeos.shared.data.transfer

import com.financeos.shared.domain.model.FinanceDataImportResult
import com.financeos.shared.domain.model.FinanceDataSnapshot
import com.financeos.shared.domain.repository.FinanceDataRepository

/**
 * 组织 FinanceOS 数据文件的导出、合并导入和恢复。
 *
 * 文件读写由平台层负责；这里仅处理跨平台文本格式和数据库业务操作。
 */
class FinanceDataTransferService(
    private val repository: FinanceDataRepository,
    private val jsonCodec: FinanceDataJsonCodec = FinanceDataJsonCodec(),
    private val csvCodec: TransactionCsvCodec = TransactionCsvCodec(),
) {
    suspend fun exportJson(): String = jsonCodec.encode(repository.snapshot())

    suspend fun exportTransactionsCsv(): String = csvCodec.encode(repository.snapshot().transactions)

    suspend fun importJson(content: String): FinanceDataImportResult =
        repository.merge(parseJson(content))

    suspend fun importTransactionsCsv(content: String): FinanceDataImportResult = repository.merge(
        FinanceDataSnapshot(
            transactions = csvCodec.decode(content),
            categories = emptyList(),
            budgets = emptyList(),
        ),
    )

    fun parseJson(content: String): FinanceDataSnapshot = jsonCodec.decode(content)

    suspend fun restore(snapshot: FinanceDataSnapshot): FinanceDataImportResult =
        repository.replaceAll(snapshot)
}
