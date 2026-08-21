package com.financeos.shared.data.transfer

import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import kotlin.time.Instant

/** 流水 CSV 编解码器，金额以最小货币单位列作为无损导入依据。 */
class TransactionCsvCodec {
    fun encode(transactions: List<Transaction>): String = buildString {
        // BOM 让常见桌面表格软件无需手动选择编码即可正确显示中文备注。
        append('\uFEFF')
        appendLine(HEADERS.joinToString(","))
        transactions
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

    fun decode(content: String): List<Transaction> {
        val rows = parseRows(content.removePrefix("\uFEFF"))
        if (rows.isEmpty()) throw DataTransferException("CSV 文件为空。")
        val header = rows.first()
        val indices = HEADERS.associateWith { requiredHeader ->
            header.indexOf(requiredHeader).takeIf { it >= 0 }
                ?: throw DataTransferException("CSV 缺少字段：$requiredHeader。")
        }

        return rows.drop(1)
            .filterNot { row -> row.all(String::isBlank) }
            .mapIndexed { index, row ->
                val lineNumber = index + 2
                fun field(name: String): String = row.getOrNull(indices.getValue(name)).orEmpty()
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

/** 按 RFC 4180 的核心转义规则解析，支持备注中的逗号、引号和换行。 */
private fun parseRows(content: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var index = 0

    fun finishField() {
        row += field.toString()
        field.clear()
    }

    fun finishRow() {
        finishField()
        rows += row.toList()
        row.clear()
    }

    while (index < content.length) {
        when (val character = content[index]) {
            '"' -> {
                if (inQuotes && content.getOrNull(index + 1) == '"') {
                    field.append('"')
                    index++
                } else if (inQuotes) {
                    inQuotes = false
                } else if (field.isEmpty()) {
                    inQuotes = true
                } else {
                    throw DataTransferException("CSV 引号位置无效。")
                }
            }

            ',' -> if (inQuotes) field.append(character) else finishField()
            '\n' -> if (inQuotes) field.append(character) else finishRow()
            '\r' -> if (inQuotes) {
                field.append(character)
            } else if (content.getOrNull(index + 1) == '\n') {
                index++
                finishRow()
            } else {
                finishRow()
            }

            else -> field.append(character)
        }
        index++
    }

    if (inQuotes) throw DataTransferException("CSV 存在未闭合的引号。")
    if (field.isNotEmpty() || row.isNotEmpty()) finishRow()
    return rows
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
