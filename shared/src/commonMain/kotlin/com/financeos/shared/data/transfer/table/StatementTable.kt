package com.financeos.shared.data.transfer.table

import com.financeos.shared.data.transfer.DataTransferException

internal data class StatementRow(val number: Int, val cells: List<String>)

internal data class StatementTable(
    val rows: List<StatementRow>,
    val format: String,
    val usesExcel1904Dates: Boolean = false,
) {
    fun detectColumns(): DetectedStatementColumns {
        val headerIndex = findKnownHeader(rows).takeIf { it >= 0 } ?: findMultiColumnHeader(rows)
        if (headerIndex < 0) throw DataTransferException("没有找到表头。请在首行提供日期、金额等列名。")
        val names = rows[headerIndex].cells
        val sourceLabels = names.map(::normalizeColumn).toSet()
        val used = mutableSetOf<String>()
        val headers = names.mapIndexed { index, raw ->
            val name = raw.trim().ifEmpty { "列 ${index + 1}" }
            var unique = name
            var suffix = 2
            while (!used.add(unique)) unique = "$name ($suffix)".also { suffix++ }
            unique
        }
        val standard = linkedMapOf<String, String>()
        names.zip(headers).forEach { (name, label) ->
            columnKey(name)?.let { key -> if (key !in standard) standard[key] = label }
        }
        fun label(key: String): String = standard[key].orEmpty()
        val appleCard = sourceLabels.containsAll(appleCardLabels)
        val legacy = sourceLabels.any { it in legacyProviderLabels }
        return DetectedStatementColumns(
            headerIndex,
            StatementColumns(
                headers,
                StatementColumnMapping(
                    date = label("date_time_epoch_millis").ifEmpty { label("date") },
                    amount = label("amount_minor").ifEmpty { label("amount") },
                    direction = label("type"),
                    category = label("category_id"),
                    merchant = label("counterparty"),
                    note = label("note").ifEmpty { if (legacy) "" else label("product") },
                    account = label("account_id"),
                    externalId = label("order_id"),
                    credit = label("credit"),
                    debit = label("debit"),
                    invertAmountSign = appleCard,
                ),
                format,
            ),
            appleCard,
            legacy,
            names,
            standard,
        )
    }
}

internal class DetectedStatementColumns(
    val headerIndex: Int,
    val display: StatementColumns,
    val isAppleCard: Boolean,
    val isLegacyProvider: Boolean,
    private val sourceLabels: List<String>,
    private val standard: Map<String, String>,
) {
    private val indexes = display.headers.withIndex().associate { (index, label) -> label to index }

    fun standardIndex(key: String): Int = indexes[standard[key]] ?: -1

    fun sourceLabel(index: Int): String = sourceLabels.getOrNull(index).orEmpty()

    fun index(label: String): Int =
        if (label.isBlank()) -1 else indexes[label]
            ?: throw DataTransferException("至少一个映射列不在当前表头中，请重新选择。")
}

internal const val MAX_STATEMENT_ROWS = 50_000
internal const val MAX_STATEMENT_COLUMNS = 256
private const val MAX_HEADER_SEARCH_ROWS = 200
private val appleCardLabels = setOf("clearingdate", "amountusd", "transactiondate")
private val legacyProviderLabels = setOf("交易对方", "对方", "收款方", "付款方", "对方账号", "交易单号", "交易订单号")

private val dateColumns = setOf("date", "date_time_epoch_millis")
private val amountColumns = setOf("amount", "amount_minor", "credit", "debit")

private fun findKnownHeader(rows: List<StatementRow>): Int = rows.asSequence()
    .take(MAX_HEADER_SEARCH_ROWS)
    .indexOfFirst { row ->
        val columns = row.cells.mapNotNull(::columnKey)
        columns.any(dateColumns::contains) && columns.any(amountColumns::contains)
    }

private fun findMultiColumnHeader(rows: List<StatementRow>): Int = rows.asSequence()
    .take(MAX_HEADER_SEARCH_ROWS)
    .indexOfFirst { it.cells.count(String::isNotBlank) >= 2 }

internal fun readStatement(bytes: ByteArray): StatementTable {
    if (bytes.size > TableTransactionImporter.MAX_FILE_BYTES) {
        throw DataTransferException("账单文件超过 16 MB，请拆分后导入。")
    }
    if (TableTransactionImporter.looksLikeXlsx(bytes)) return readXlsxStatement(bytes)
    val text = decodeStatementBytes(bytes)
    return if (looksLikeOfx(text)) readOfxRows(text) else readDelimitedStatement(text)
}

private fun decodeStatementBytes(bytes: ByteArray): String {
    if (bytes.size >= 2 &&
        ((bytes[0] == (-1).toByte() && bytes[1] == (-2).toByte()) ||
            (bytes[0] == (-2).toByte() && bytes[1] == (-1).toByte()))) {
        if (bytes.size % 2 != 0) throw DataTransferException("UTF-16 账单文件尾部不完整。")
        val littleEndian = bytes[0] == (-1).toByte()
        return buildString(bytes.size / 2) {
            for (index in 2 until bytes.lastIndex step 2) {
                val first = bytes[index].toInt() and 0xff
                val second = bytes[index + 1].toInt() and 0xff
                append((if (littleEndian) first or (second shl 8) else (first shl 8) or second).toChar())
            }
        }
    }
    return decodeSpreadsheetText(bytes)
}

private fun prepareDelimitedText(content: String): String {
    if (content.length > TableTransactionImporter.MAX_FILE_BYTES) {
        throw DataTransferException("账单文本过大，请拆分后导入。")
    }
    return content.removePrefix("\uFEFF")
}

internal fun readCommaSeparatedStatement(content: String): List<StatementRow> =
    DelimitedStatementParser(prepareDelimitedText(content), ',', strict = true).read(MAX_STATEMENT_ROWS, sample = false)

internal fun readDelimitedStatement(content: String): StatementTable {
    val text = prepareDelimitedText(content)
    if ('\u0000' in text || text.startsWith("%PDF-") || text.startsWith("PK\u0003\u0004")) {
        throw DataTransferException("请使用 CSV、TSV、XLSX 或 OFX/QFX/QBO 账单。")
    }
    var delimiter = ','
    var strongest = -1
    var earliest = Int.MAX_VALUE
    for (candidate in listOf(',', '\t', ';')) {
        val rows = try {
            DelimitedStatementParser(text, candidate).read(MAX_HEADER_SEARCH_ROWS, sample = true)
        } catch (_: DataTransferException) {
            continue
        }
        if (findKnownHeader(rows) >= 0) {
            delimiter = candidate
            break
        }
        val fallback = findMultiColumnHeader(rows)
        if (fallback < 0) continue
        val size = rows[fallback].cells.size
        val matchingRows = rows.count { it.cells.size == size }
        if (matchingRows > strongest || (matchingRows == strongest && rows[fallback].number < earliest)) {
            strongest = matchingRows
            earliest = rows[fallback].number
            delimiter = candidate
        }
    }
    return StatementTable(
        DelimitedStatementParser(text, delimiter).read(MAX_STATEMENT_ROWS, sample = false),
        if (delimiter == '\t') "TSV" else "CSV",
    )
}

private class DelimitedStatementParser(
    private val text: String,
    private val delimiter: Char,
    private val strict: Boolean = false,
) {
    fun read(limit: Int, sample: Boolean): List<StatementRow> {
        val rows = mutableListOf<StatementRow>()
        val fields = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var afterQuote = false
        var line = 1
        var startLine = 1
        var index = 0

        fun finishField() {
            if (fields.size >= MAX_STATEMENT_COLUMNS) throw DataTransferException("第 $startLine 行的列数超过 $MAX_STATEMENT_COLUMNS。")
            fields += value.toString()
            value.clear()
            afterQuote = false
        }

        fun finishRow() {
            finishField()
            if (fields.any(String::isNotBlank)) {
                if (rows.size >= limit) throw DataTransferException("账单行数超过 $limit，请拆分后导入。")
                rows += StatementRow(startLine, fields.toList())
            }
            fields.clear()
        }

        while (index < text.length) {
            val character = text[index++]
            when {
                quoted && character == '"' -> {
                    if (text.getOrNull(index) == '"') {
                        value.append('"')
                        index++
                    } else {
                        quoted = false
                        afterQuote = true
                    }
                }
                character == '\r' || character == '\n' -> {
                    val crlf = character == '\r' && text.getOrNull(index) == '\n'
                    if (crlf) index++
                    if (quoted) {
                        value.append(character)
                        if (crlf) value.append('\n')
                    } else {
                        finishRow()
                        if (sample && rows.size >= limit) return rows
                        startLine = line + 1
                    }
                    line++
                }
                quoted -> value.append(character)
                character == delimiter -> finishField()
                afterQuote && !strict && character.isWhitespace() -> Unit
                afterQuote -> throw DataTransferException("第 $line 行的引号后有多余内容。")
                character == '"' && (value.isEmpty() || (!strict && value.isBlank())) -> {
                    value.clear()
                    quoted = true
                }
                character == '"' && strict -> throw DataTransferException("第 $line 行的引号位置无效。")
                else -> value.append(character)
            }
        }
        if (quoted) throw DataTransferException("第 $startLine 行的引号未闭合。")
        if (value.isNotEmpty() || fields.isNotEmpty() || afterQuote) finishRow()
        return rows
    }
}

private val aliases = mapOf(
    "id" to listOf("id", "流水id", "编号"),
    "amount_minor" to listOf("amount_minor", "金额分", "金额(分)", "金额(最小单位)"),
    "amount" to listOf("amount", "金额", "金额元", "金额(元)", "交易金额", "transaction amount", "total"),
    "credit" to listOf("credit", "credits", "收款", "收入", "存入", "贷方发生额", "收入金额", "入账金额", "inflow", "deposit"),
    "debit" to listOf("debit", "debits", "付款", "支出", "支取", "借方发生额", "支出金额", "扣款金额", "outflow", "withdrawal"),
    "type" to listOf("type", "类型", "收支", "收支类型", "收/支", "方向", "direction", "debit/credit", "transaction type"),
    "category_id" to listOf("category_id", "分类id", "分类", "分类名称", "分类编号", "标签", "交易分类", "消费分类", "账单分类", "category"),
    "account_id" to listOf("account_id", "账户", "账户id", "账号", "银行卡", "支付方式", "收付款方式", "付款方式", "account", "payment method"),
    "date" to listOf("date", "时间", "日期", "日期时间", "记账时间", "入账日期", "交易日期", "交易时间", "时间戳",
        "transaction date", "posted date", "posting date", "datetime"),
    "order_id" to listOf("order_id", "交易单号", "交易订单号", "商户单号", "商家订单号", "订单号", "流水号",
        "transaction id", "reference", "reference number", "fitid"),
    "status" to listOf("状态", "交易状态", "当前状态", "支付状态", "订单状态", "交易状态(成功)", "状态说明", "status"),
    "note" to listOf("note", "备注", "描述", "说明", "memo", "description", "notes", "details"),
    "product" to listOf("商品", "商品说明", "商品名称", "商品描述"),
    "counterparty" to listOf("交易对方", "对方", "对方账号", "收款方", "付款方", "payee", "merchant", "counterparty"),
    "date_time_epoch_millis" to listOf("date_time_epoch_millis", "时间戳毫秒"),
    "currency" to listOf("currency", "currency_code", "币种", "货币"),
).flatMap { (key, labels) -> labels.map { normalizeColumn(it) to key } }.toMap()

private fun normalizeColumn(text: String): String = text.lowercase().filter(Char::isLetterOrDigit)

internal fun columnKey(label: String): String? =
    aliases[normalizeColumn(label)] ?: amountColumnWithCurrency(label)?.first

private val currencyColumnLabel = Regex("""^(.+?)\s*[(（]([A-Za-z]{3})[)）]$""")

internal fun currencyFromLabel(label: String): String? = amountColumnWithCurrency(label)?.second

private fun amountColumnWithCurrency(label: String): Pair<String, String>? {
    val (name, code) = currencyColumnLabel.matchEntire(label.trim())?.destructured ?: return null
    val column = aliases[normalizeColumn(name)]?.takeIf(amountColumns::contains) ?: return null
    val currency = normalizeCurrency(code) ?: return null
    return column to currency
}

internal fun isStatementFooter(cells: List<String>): Boolean {
    val first = cells.firstOrNull(String::isNotBlank)?.trim().orEmpty()
    return first.startsWith("共计") || first.startsWith("共导出") || first.startsWith("合计") ||
        first.startsWith("总计") || first.startsWith("交易合计") || first.all { it in "-=－—" }
}
