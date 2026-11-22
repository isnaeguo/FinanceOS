package com.financeos.shared.data.transfer.table

import com.financeos.shared.data.transfer.DataTransferException
import com.financeos.shared.data.transfer.StableRowId
import com.financeos.shared.data.transfer.isFinanceOsTransactionCsvHeader
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.CategoryType
import com.financeos.shared.domain.model.DefaultCategories
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import kotlin.Throws
import kotlin.math.absoluteValue
import kotlin.time.Instant

/** Column labels are taken directly from [StatementColumns.headers]. */
data class StatementColumnMapping(
    val date: String,
    val amount: String = "",
    val direction: String = "",
    val category: String = "",
    val merchant: String = "",
    val note: String = "",
    val account: String = "",
    val externalId: String = "",
    val credit: String = "",
    val debit: String = "",
    val invertAmountSign: Boolean = false,
)

data class StatementColumns(
    val headers: List<String>,
    val suggestedMapping: StatementColumnMapping,
    val format: String,
)

/** A parsed statement whose columns can be remapped without decoding the source again. */
class PreparedStatement internal constructor(private val input: StatementTable) {
    private val detectedColumns = input.detectColumns()

    val columns: StatementColumns get() = detectedColumns.display

    @Throws(DataTransferException::class)
    fun preview(
        categories: List<Category>,
        importedAtEpochMillis: Long,
        mapping: StatementColumnMapping? = null,
    ): TableTransactionImporter.DecodeResult =
        TableTransactionImporter.preview(input, detectedColumns, categories, importedAtEpochMillis, mapping)
}

/** Platform-independent statement preview. Parsing never modifies the ledger. */
object TableTransactionImporter {
    const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private const val MIN_STATEMENT_AMOUNT_CNY_MINOR = 50L

    data class DecodeResult(
        val transactions: List<Transaction>,
        val skippedRows: Int = 0,
        val format: String = "CSV",
        val duplicateRows: Int = 0,
        val issueMessages: List<String> = emptyList(),
        val invalidRows: Int = 0,
        val sourceCurrencyCode: String? = null,
        val appliedExchangeRate: String? = null,
    ) {
        val requiresCurrencyConversion: Boolean get() =
            sourceCurrencyCode != null && sourceCurrencyCode != "CNY" && appliedExchangeRate == null
    }

    fun looksLikeXlsx(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    @Throws(DataTransferException::class)
    fun prepare(bytes: ByteArray): PreparedStatement = PreparedStatement(readStatement(bytes))

    @Throws(DataTransferException::class)
    fun prepareText(content: String): PreparedStatement = PreparedStatement(readDelimitedStatement(content))

    @Throws(DataTransferException::class)
    fun inspect(bytes: ByteArray): StatementColumns = prepare(bytes).columns

    @Throws(DataTransferException::class)
    fun inspectCsvText(content: String): StatementColumns = prepareText(content).columns

    @Throws(DataTransferException::class)
    fun decode(
        bytes: ByteArray,
        categories: List<Category> = emptyList(),
        importedAtEpochMillis: Long = 0,
    ): DecodeResult = prepare(bytes).preview(categories, importedAtEpochMillis)

    @Throws(DataTransferException::class)
    fun decodeCsvText(
        content: String,
        categories: List<Category> = emptyList(),
        importedAtEpochMillis: Long = 0,
    ): DecodeResult = prepareText(content).preview(categories, importedAtEpochMillis)

    @Throws(DataTransferException::class)
    fun decodeMapped(
        bytes: ByteArray,
        categories: List<Category>,
        importedAtEpochMillis: Long,
        mapping: StatementColumnMapping,
    ): DecodeResult = prepare(bytes).preview(categories, importedAtEpochMillis, mapping)

    @Throws(DataTransferException::class)
    fun decodeMappedCsvText(
        content: String,
        categories: List<Category>,
        importedAtEpochMillis: Long,
        mapping: StatementColumnMapping,
    ): DecodeResult = prepareText(content).preview(categories, importedAtEpochMillis, mapping)

    /** Apply the user's CNY-per-source-unit rate without changing the source IDs. */
    @Throws(DataTransferException::class)
    fun convertToCny(result: DecodeResult, exchangeRateText: String): DecodeResult {
        if (!result.requiresCurrencyConversion) return result
        val rate = exchangeRateText.trim()
        val rateUnits = parseExchangeRate(rate)
        return result.copy(
            issueMessages = listOf("已按 1 ${result.sourceCurrencyCode} = $rate CNY 换算。") + result.issueMessages,
            appliedExchangeRate = rate,
        ).withLedgerAmounts { amount -> convertedToCnyMinor(amount, rateUnits) }
    }

    internal fun preview(
        input: StatementTable,
        columns: DetectedStatementColumns,
        categories: List<Category>,
        importedAt: Long,
        customMapping: StatementColumnMapping?,
    ): DecodeResult {
        val mapping = customMapping ?: columns.display.suggestedMapping
        requireMapping(mapping)

        val dateColumn = columns.index(mapping.date)
        val amountColumn = columns.index(mapping.amount)
        val creditColumn = columns.index(mapping.credit)
        val debitColumn = columns.index(mapping.debit)
        val directionColumn = columns.index(mapping.direction)
        val categoryColumn = columns.index(mapping.category)
        val merchantColumn = columns.index(mapping.merchant)
        val noteColumn = columns.index(mapping.note)
        val accountColumn = columns.index(mapping.account)
        val externalIdColumn = columns.index(mapping.externalId)
        val explicitIdColumn = columns.standardIndex("id")
        val statusColumn = columns.standardIndex("status")
        val currencyColumn = columns.standardIndex("currency")
        val amountHeader = columns.sourceLabel(amountColumn)
        val amountUsesMinorUnits = columnKey(amountHeader) == "amount_minor"
        val dateUsesEpochMillis = columnKey(columns.sourceLabel(dateColumn)) == "date_time_epoch_millis"
        val updatedAt = importedAt.coerceAtLeast(0)
        val knownCategories = categories.ifEmpty { DefaultCategories.all }.filter { it.deletedAt == null }
        val categoriesById = knownCategories.associateBy { it.id }
        val categoriesByName = knownCategories.groupBy { it.name.lowercase() }
        val result = linkedMapOf<String, Transaction>()
        val occurrences = mutableMapOf<String, Int>()
        val issues = mutableListOf<String>()
        val currencies = listOf(amountHeader, columns.sourceLabel(creditColumn), columns.sourceLabel(debitColumn))
            .mapNotNullTo(linkedSetOf(), ::currencyFromLabel)
        var invalid = 0
        var skipped = 0
        var duplicates = 0

        for (row in input.rows.subList(columns.headerIndex + 1, input.rows.size)) {
            fun value(index: Int): String = row.cells.getOrNull(index)?.trim().orEmpty()
            if (isStatementFooter(row.cells)) continue

            try {
                val direction = value(directionColumn)
                if (shouldSkip(direction, value(statusColumn), columns.isAppleCard)) {
                    skipped++
                    continue
                }
                val sourceAmount = value(amountColumn)
                val sourceCredit = value(creditColumn)
                val sourceDebit = value(debitColumn)
                val signedAmount = resolveStatementAmount(
                    sourceAmount, sourceCredit, sourceDebit, amountUsesMinorUnits, row.number,
                )
                if (signedAmount == 0L) {
                    skipped++
                    continue
                }
                val type = resolveDirection(direction, signedAmount, mapping.invertAmountSign, row.number)
                val amount = signedAmount.absoluteValue
                val dateText = value(dateColumn)
                val date = TableDateParser.resolveDateMillis(
                    dateText,
                    row.number,
                    preferUsDates = columns.isAppleCard,
                    epochMillis = dateUsesEpochMillis,
                    usesExcel1904Dates = input.usesExcel1904Dates,
                )
                val merchant = value(merchantColumn).ifPlaceholderEmpty()
                val memo = value(noteColumn).ifPlaceholderEmpty()
                val note = when {
                    merchant.isEmpty() -> memo
                    memo.isEmpty() || merchant == memo -> merchant
                    else -> "$merchant · $memo"
                }
                val account = value(accountColumn).ifPlaceholderEmpty().ifBlank {
                    if (columns.isAppleCard) "Apple Card · USD" else ""
                }
                val sourceId = value(externalIdColumn).ifPlaceholderEmpty()
                val existingId = value(explicitIdColumn)
                val baseId = when {
                    existingId.isNotBlank() -> existingId
                    columns.isLegacyProvider -> StableRowId.generate(sourceId, date, amount, type, merchant, merchant)
                    sourceId.isNotBlank() -> StableRowId.generateStatementId(listOf("external", account, sourceId))
                    else -> StableRowId.generateStatementId(
                        listOf("row", dateText, amount.toString(), type.name, account, merchant, memo),
                    )
                }
                // Identical statement rows without an external ID can be two genuine purchases.
                val id = if (existingId.isBlank() && sourceId.isBlank()) {
                    val occurrence = (occurrences[baseId] ?: 0) + 1
                    occurrences[baseId] = occurrence
                    if (occurrence == 1) baseId else "$baseId-$occurrence"
                } else {
                    baseId
                }

                val label = value(categoryColumn)
                val category = categoriesById[label]?.takeIf { it.accepts(type) }
                    ?: categoriesByName[label.lowercase()]?.firstOrNull { it.accepts(type) }
                    ?: categoriesById[categoryKeyword(label)]?.takeIf { it.accepts(type) }
                val transaction = Transaction(
                    id = id,
                    amount = amount,
                    type = type,
                    categoryId = category?.id ?: "system-other",
                    accountId = account.ifEmpty { null },
                    dateTime = Instant.fromEpochMilliseconds(date),
                    note = note.ifEmpty { null },
                    updatedAt = updatedAt,
                )
                val prior = result[id]
                if (prior == null) {
                    result[id] = transaction
                } else if (prior == transaction) {
                    duplicates++
                } else {
                    throw DataTransferException("第 ${row.number} 行与同一交易编号的其他流水冲突。")
                }
                normalizeCurrency(value(currencyColumn))?.let(currencies::add)
                currencyFromAmount(sourceAmount)?.let(currencies::add)
                currencyFromAmount(sourceCredit)?.let(currencies::add)
                currencyFromAmount(sourceDebit)?.let(currencies::add)
            } catch (error: DataTransferException) {
                invalid++
                if (issues.size < 8) issues += error.message ?: "第 ${row.number} 行数据无效。"
            }
        }

        if (currencies.size > 1) {
            throw DataTransferException("该账单包含多种币种。请先按币种拆分，再按账本币种换算。")
        }
        if (result.isEmpty()) {
            val message = if (issues.isEmpty()) "没有可导入的流水，已跳过 $skipped 行。" else issues.joinToString("；")
            throw DataTransferException(message)
        }
        val preview = DecodeResult(
            transactions = result.values.toList(),
            skippedRows = skipped,
            format = input.format,
            duplicateRows = duplicates,
            issueMessages = issues,
            invalidRows = invalid,
            sourceCurrencyCode = currencies.singleOrNull(),
        )
        val financeOsExport = isFinanceOsTransactionCsvHeader(input.rows[columns.headerIndex].cells)
        return if (financeOsExport || preview.requiresCurrencyConversion) preview else preview.withLedgerAmounts()
    }

    private fun DecodeResult.withLedgerAmounts(toLedgerAmount: (Long) -> Long = { it }): DecodeResult {
        val retained = transactions.mapNotNull { transaction ->
            val amount = toLedgerAmount(transaction.amount)
            when {
                amount < MIN_STATEMENT_AMOUNT_CNY_MINOR -> null
                amount == transaction.amount -> transaction
                else -> transaction.copy(amount = amount)
            }
        }
        val skippedSmall = transactions.size - retained.size
        val warnings = if (skippedSmall == 0) issueMessages else {
            listOf("已跳过 $skippedSmall 笔入账金额低于 ¥0.50 的小额流水。") + issueMessages
        }
        return copy(
            transactions = retained,
            skippedRows = skippedRows + skippedSmall,
            issueMessages = warnings,
        )
    }

    private fun requireMapping(mapping: StatementColumnMapping) {
        if (mapping.date.isBlank() || (mapping.amount.isBlank() && mapping.credit.isBlank() && mapping.debit.isBlank())) {
            throw DataTransferException("请为日期和金额选择对应列。也可以分别指定收入和支出列。")
        }
    }

    private fun shouldSkip(direction: String, state: String, appleCard: Boolean): Boolean {
        val normalized = direction.lowercase()
        return normalized in skippedDirections ||
            (appleCard && normalized == "payment") ||
            (normalized !in refundDirections && state in fullRefundStates) ||
            failedStates.any { state.contains(it, ignoreCase = true) }
    }

    private val skippedDirections = setOf("/", "不计收支", "不计", "transfer", "转账", "内部转账", "balance")
    private val refundDirections = setOf("refund", "退款")
    private val fullRefundStates = setOf("退款", "已退款", "全额退款", "退款成功")
    private val failedStates = listOf("失败", "关闭", "撤销", "未支付", "cancel", "declined", "failed", "pending")

    private fun resolveDirection(direction: String, amount: Long, invert: Boolean, line: Int): TransactionType {
        val key = direction.lowercase()
        return when {
            key.isBlank() -> if ((amount > 0) xor invert) TransactionType.INCOME else TransactionType.EXPENSE
            key in incomeDirections || key.contains("收入") -> TransactionType.INCOME
            key in expenseDirections || key.contains("支出") -> TransactionType.EXPENSE
            else -> throw DataTransferException("第 $line 行的收支方向无法识别。")
        }
    }

    private val incomeDirections = setOf("收入", "收", "进账", "贷", "入账", "in", "income", "credit", "cr", "deposit", "refund", "退款")
    private val expenseDirections = setOf("支出", "支", "出账", "借", "扣款", "out", "expense", "debit", "dr", "purchase",
        "payment", "charge", "sale", "withdrawal", "fee", "interest charge")

    private fun Category.accepts(type: TransactionType): Boolean =
        this.type == CategoryType.COMMON || this.type.name == type.name

    private val placeholders = setOf("/", "-", "—", "无", "暂无")

    private fun String.ifPlaceholderEmpty(): String = if (this in placeholders) "" else this

    private val categoryKeywords = listOf(
        "system-food" to listOf("餐饮", "美食", "外卖", "夜宵", "food", "restaurant", "dining"),
        "system-transport" to listOf("交通", "出行", "打车", "公交", "地铁", "加油", "火车", "机票", "transport"),
        "system-daily-needs" to listOf("日用", "生活服务", "家政"),
        "system-shopping" to listOf("购物", "服饰", "装扮", "淘宝", "京东", "百货", "商超", "shopping"),
        "system-entertainment" to listOf("娱乐", "文化", "休闲", "游戏", "电影", "演出", "entertainment"),
        "system-travel" to listOf("旅行", "旅游", "酒店", "住宿", "度假", "travel"),
        "system-learning" to listOf("教育", "学习", "培训", "书籍", "education"),
        "system-digital" to listOf("数码", "电器", "电子", "话费", "通讯", "充值", "electronics"),
        "system-income" to listOf("工资", "薪酬", "劳务报酬", "salary", "payroll"),
    )

    private fun categoryKeyword(label: String): String? = categoryKeywords.firstOrNull { (_, keywords) ->
        keywords.any { label.contains(it, ignoreCase = true) }
    }?.first
}
