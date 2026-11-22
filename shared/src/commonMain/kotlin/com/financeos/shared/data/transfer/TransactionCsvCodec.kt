package com.financeos.shared.data.transfer

import com.financeos.shared.data.transfer.table.readCommaSeparatedStatement
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.time.EpochClock
import kotlin.Throws
import kotlin.time.Instant

/** 流水 CSV 编解码器，金额以最小货币单位列作为无损导入依据。 */
class TransactionCsvCodec(
    private val clock: EpochClock = EpochClock.system,
) {
    fun encode(transactions: List<Transaction>): String = buildString {
        // BOM 让常见桌面表格软件无需手动选择编码即可正确显示中文备注。
        append('\uFEFF')
        appendLine(HEADERS.joinToString(","))
        transactions
            .filter { it.deletedAt == null }
            .sortedWith(compareByDescending<Transaction> { it.dateTime }.thenBy { it.id })
            .forEach { transaction ->
                appendLine(
                    listOf(
                        transaction.id,
                        transaction.amount.toString(),
                        formatMajorAmount(transaction.amount),
                        transaction.type.name,
                        transaction.categoryId,
                        transaction.accountId.orEmpty(),
                        transaction.dateTime.toEpochMilliseconds().toString(),
                        transaction.note.orEmpty(),
                    ).joinToString(",", transform = ::escapeField),
                )
            }
    }

    /**
     * 解析 CSV 为流水列表。
     *
     * CSV 只携带活动流水。解析结果用导入时间作为新记录的 `updatedAt`。
     */
    @Throws(DataTransferException::class)
    fun decode(content: String): List<Transaction> {
        val rows = readCommaSeparatedStatement(content)
        val header = rows.firstOrNull()?.cells ?: throw DataTransferException("CSV 文件为空。")
        val indices = HEADERS.associateWith { requiredHeader ->
            header.indexOf(requiredHeader).takeIf { it >= 0 }
                ?: throw DataTransferException("CSV 缺少字段：$requiredHeader。")
        }
        val importedAtMillis = clock.nowMillis()

        return rows.drop(1).map { row ->
            val lineNumber = row.number
            fun field(name: String): String = row.cells.getOrNull(indices.getValue(name)).orEmpty()
            try {
                Transaction(
                    id = field("id"),
                    // amount_minor 是导入的唯一金额来源，展示用 amount 不参与计算。
                    amount = field("amount_minor").toLong(),
                    type = TransactionType.valueOf(field("type")),
                    categoryId = field("category_id"),
                    accountId = field("account_id").ifBlank { null },
                    dateTime = Instant.fromEpochMilliseconds(
                        field("date_time_epoch_millis").toLong(),
                    ),
                    note = field("note").ifBlank { null },
                    updatedAt = importedAtMillis,
                )
            } catch (error: Exception) {
                throw DataTransferException(
                    "CSV 第 ${lineNumber} 行数据无效：${error.message ?: "字段格式错误"}",
                    error,
                )
            }
        }
    }
}

private fun escapeField(value: String): String {
    val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    return if (needsQuotes) "\"${value.replace("\"", "\"\"")}\"" else value
}

private fun formatMajorAmount(amountMinor: Long): String = buildString {
    append(amountMinor / 100L)
    append('.')
    append((amountMinor % 100L).toString().padStart(2, '0'))
}

private val HEADERS = listOf(
    "id",
    "amount_minor",
    "amount",
    "type",
    "category_id",
    "account_id",
    "date_time_epoch_millis",
    "note",
)

internal fun isFinanceOsTransactionCsvHeader(header: List<String>): Boolean =
    header.size == HEADERS.size && HEADERS.all(header::contains)
