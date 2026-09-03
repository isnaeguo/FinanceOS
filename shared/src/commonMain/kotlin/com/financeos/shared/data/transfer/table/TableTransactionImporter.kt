package com.financeos.shared.data.transfer.table

import com.financeos.shared.data.transfer.DataTransferException
import com.financeos.shared.data.transfer.StableRowId
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import kotlin.Throws
import kotlin.time.Instant

/**
 * 宽容的“流水表”导入引擎，同时支持 CSV 与 XLSX，作为 Android 与 Apple 端唯一实现。
 *
 * 面向两类来源：
 * 1. FinanceOS 自身导出的标准列；
 * 2. 微信 / 支付宝账单（中文列名：交易时间、收/支、金额(元)、支付方式、商品/商品说明、
 *    交易对方、备注…）以及常见中文别名表。
 *
 * 能力：表头别名、id 自动生成（[StableRowId]，三端一致）、金额元/分、多种日期、
 * 宽容引号、Tab 分隔、GB18030 自动识别；“不计收支/退款/空收支”行自动跳过并计数；
 * 没有可识别分类时默认归入“其他”（system-other）。任一行字段损坏时整批中止并按行报告。
 */
object TableTransactionImporter {
    data class DecodeResult(
        val transactions: List<Transaction>,
        val skippedRows: Int = 0,
    )

    /** 是否是 .xlsx（zip 魔数 PK：50 4B 03 04）。 */
    fun looksLikeXlsx(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()

    /**
     * 从已读取的原始字节识别并解析为流水列表。
     *
     * [importedAtEpochMillis] 作为这批记录的统一 `updatedAt`，使其在跨设备合并中胜过旧数据；
     * [categories] 提供按名称/ID 的分类映射，未命中时归入 system-other。
     */
    @Throws(DataTransferException::class)
    fun decode(
        bytes: ByteArray,
        categories: List<Category> = emptyList(),
        importedAtEpochMillis: Long = 0,
    ): DecodeResult {
        val text = if (looksLikeXlsx(bytes)) {
            val grid = parseXlsx(bytes)
            return transactionsFromGrid(grid, categories, importedAtEpochMillis)
        } else {
            decodeSpreadsheetText(bytes)
        }
        val grid = parseCsvTolerant(text)
        return transactionsFromGrid(grid, categories, importedAtEpochMillis)
    }

    /** 直接从已解码文本解析（宽容 CSV），供 Swift 端文件读取后调用。 */
    @Throws(DataTransferException::class)
    fun decodeCsvText(
        content: String,
        categories: List<Category> = emptyList(),
        importedAtEpochMillis: Long = 0,
    ): DecodeResult {
        val grid = parseCsvTolerant(content)
        return transactionsFromGrid(grid, categories, importedAtEpochMillis)
    }

    // MARK: - 通用表格 → 流水

    private fun transactionsFromGrid(
        grid: List<List<String>>,
        categories: List<Category>,
        importedAtEpochMillis: Long,
    ): DecodeResult {
        if (grid.isEmpty()) throw DataTransferException("所选文件没有可导入的数据。")

        // 微信/支付宝账单顶部常有多行说明（“微信支付账单明细”“导出时间…”），
        // 真正表头不在第一行：自动扫描，找第一行能识别出 >=2 个已知列名的行。
        fun recognizedCount(row: List<String>): Int = row.count { canonicalColumn(it) != null }
        val headerIndex = grid.indexOfFirst { recognizedCount(it) >= 2 }
            .takeIf { it >= 0 }
            ?: grid.indexOfFirst { recognizedCount(it) >= 1 }
        val headers = if (headerIndex >= 0) grid[headerIndex].map { canonicalColumn(it) } else null
        if (headers == null) {
            throw DataTransferException("没有找到可识别的表头。请使用“交易时间/金额/收/支/分类/备注”等列名，并确保文件第一列是列名。")
        }
        val dataStart = headerIndex + 1
        if (dataStart >= grid.size) throw DataTransferException("所选文件只包含表头，没有流水数据。")

        val columnIndex = mutableMapOf<String, Int>()
        headers.forEachIndexed { index, canonical ->
            if (canonical != null && canonical !in columnIndex) columnIndex[canonical] = index
        }
        fun value(row: List<String>, key: String): String =
            columnIndex[key]?.let { row.getOrNull(it).orEmpty().trim() } ?: ""

        val categoryById = categories.associateBy { it.id }
        val categoryByName = categories.associateBy { it.name }

        val errors = mutableListOf<String>()
        val transactions = mutableListOf<Transaction>()
        var skipped = 0
        val skipSamples = mutableListOf<String>()

        for ((offset, rawRow) in grid.drop(dataStart).withIndex()) {
            val lineNumber = dataStart + offset + 1
            try {
                val typeRaw = value(rawRow, "type")
                val statusRaw = value(rawRow, "status")
                val skippedThisRow = typeRaw.isBlank() || typeRaw == "/" ||
                    typeRaw.contains("不计") || typeRaw.contains("退款") ||
                    (statusRaw.isNotEmpty() && (statusRaw.contains("退款") || statusRaw.contains("失败") ||
                        statusRaw.contains("关闭") || statusRaw.contains("撤销") || statusRaw.contains("未支付")))
                if (skippedThisRow) {
                    skipped++
                    if (skipSamples.size < 3) {
                        skipSamples += "行${typeRaw.ifBlank { "（空）" }}/${statusRaw.ifBlank { "（空）" }}"
                    }
                    continue
                }
                val parsed = parseRow(
                    typeRaw = value(rawRow, "type"),
                    categoryRaw = value(rawRow, "category_id"),
                    amountMinorRaw = value(rawRow, "amount_minor"),
                    amountMajorRaw = value(rawRow, "amount"),
                    lineNumber = lineNumber,
                    categoryById = categoryById,
                    categoryByName = categoryByName,
                )
                if (parsed == null) {
                    skipped++
                } else {
                    // 名称只取“交易对方”：账单中只有这一项是真实有效的收款/付款方信息；
                    // 支付方式保留在“账户”列。占位符（/ - 空 等）视为无名称。
                    val counterparty = value(rawRow, "counterparty")
                    val note = if (isPlaceholder(counterparty)) "" else counterparty
                    val millis = TableDateParser.resolveDateMillis(
                        value(rawRow, "date").ifBlank { value(rawRow, "date_time_epoch_millis") },
                        lineNumber,
                    ) { message -> throw DataTransferException(message) }
                    transactions += Transaction(
                        id = value(rawRow, "id").ifBlank {
                            StableRowId.generate(
                                orderId = value(rawRow, "order_id"),
                                dateMillis = millis,
                                amountMinor = parsed.amountMinor,
                                type = parsed.type,
                                note = note,
                                counterparty = counterparty,
                            )
                        },
                        amount = parsed.amountMinor,
                        type = parsed.type,
                        categoryId = parsed.categoryId,
                        accountId = value(rawRow, "account_id").let { if (isPlaceholder(it)) null else it },
                        dateTime = Instant.fromEpochMilliseconds(millis),
                        note = note.ifBlank { null },
                        updatedAt = importedAtEpochMillis,
                    )
                }
            } catch (error: DataTransferException) {
                errors += error.message ?: "第 $lineNumber 行数据无效"
            }
        }

        if (transactions.isEmpty()) {
            if (skipped > 0) {
                val samples = if (skipSamples.isNotEmpty()) "；示例（收/支/状态）：${skipSamples.joinToString("，")}" else ""
                throw DataTransferException("未导入任何流水：全部 $skipped 行属于不计收支/退款记录。$samples")
            }
            throw DataTransferException(
                if (errors.isNotEmpty()) errors.joinToString("；") else "所选文件没有可导入的流水。",
            )
        }
        if (errors.isNotEmpty()) throw DataTransferException(errors.joinToString("；"))
        return DecodeResult(transactions = transactions, skippedRows = skipped)
    }

    private class ParsedRow(
        val amountMinor: Long,
        val type: TransactionType,
        val categoryId: String,
    )

    /** 解析一行的类型/金额/分类。返回 null 表示该行不计收支（退款/不计/空收支）应跳过。 */
    private fun parseRow(
        typeRaw: String,
        categoryRaw: String,
        amountMinorRaw: String,
        amountMajorRaw: String,
        lineNumber: Int,
        categoryById: Map<String, Category>,
        categoryByName: Map<String, Category>,
    ): ParsedRow? {
        if (typeRaw.isBlank() || typeRaw == "/" || typeRaw.contains("不计") || typeRaw.contains("退款")) return null
        val type = when {
            typeRaw.contains("收入") || typeRaw.equals("INCOME", ignoreCase = true) -> TransactionType.INCOME
            typeRaw.contains("支出") || typeRaw.equals("EXPENSE", ignoreCase = true) -> TransactionType.EXPENSE
            typeRaw.contains("收") -> TransactionType.INCOME
            typeRaw.contains("支") -> TransactionType.EXPENSE
            else -> throw DataTransferException("第 $lineNumber 行“收/支”无法识别：$typeRaw")
        }

        // 金额：xlsx 的“金额(元)”可能为负数（退款记录），收支方向已由类型决定，这里取绝对值。
        val amountMinor = resolveAmountMinor(amountMinorRaw, amountMajorRaw, lineNumber)
        if (amountMinor == 0L) return null // 0 元交易（如优惠券抵扣）不产生收支，跳过
        if (amountMinor < 45L) return null // 屏蔽小于 0.45 元的小额流水

        // 分类：账单通常没有“分类”列，或列值是平台类目/未知名称。
        // 分类只作为可选项：先精确匹配 ID/名称，再用关键词把“餐饮美食/交通出行”等平台类目
        // 映射到语义最近的系统分类，其余一律归“其他”，绝不因分类拒绝导入。
        val categoryId = categoryById[categoryRaw]?.id
            ?: categoryByName[categoryRaw]?.id
            ?: matchCategoryKeywords(categoryRaw)
            ?: "system-other"
        return ParsedRow(amountMinor = amountMinor, type = type, categoryId = categoryId)
    }

    private fun resolveAmountMinor(minorRaw: String, majorRaw: String, lineNumber: Int): Long {
        val cleanMinor = minorRaw.filter { it.isDigit() || it == '-' }
        if (cleanMinor.isNotEmpty() && cleanMinor != "-") {
            return cleanMinor.toLongOrNull()
                ?: throw DataTransferException("第 $lineNumber 行“金额(分)”无法解析：$minorRaw")
        }
        return majorToMinor(majorRaw, lineNumber)
    }

    /** 元金额 → 最小货币单位（分），不经过浮点，避免精度误差。 */
    private fun majorToMinor(majorRaw: String, lineNumber: Int): Long {
        val cleaned = majorRaw
            .replace('，', ',')
            .replace("￥", "")
            .replace("¥", "")
            .replace(",", "")
            .replace("+", "")
        if (cleaned.isBlank()) throw DataTransferException("第 $lineNumber 行“金额”无法解析：$majorRaw")
        val negative = cleaned.startsWith('-')
        val unsigned = if (negative) cleaned.substring(1) else cleaned
        val dotIndex = unsigned.indexOf('.')
        val wholeText = if (dotIndex >= 0) unsigned.substring(0, dotIndex) else unsigned
        val whole = wholeText.ifEmpty { "0" }.toLongOrNull()
            ?: throw DataTransferException("第 $lineNumber 行“金额”无法解析：$majorRaw")
        val fractionText = if (dotIndex >= 0) unsigned.substring(dotIndex + 1) else ""
        // 小数超过两位的部分四舍五入（HALF_UP），与 Android 端 BigDecimal 语义一致。
        val fraction = when {
            fractionText.isEmpty() -> 0L
            fractionText.length == 1 -> fractionText.toLong() * 10L
            else -> fractionText.take(2).toLong() + if (fractionText.getOrNull(2)?.digitToIntOrNull()?.let { it >= 5 } == true) 1L else 0L
        }
        val minor = whole * 100L + fraction
        return if (negative) -minor else minor
    }

    private fun isPlaceholder(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isEmpty() || trimmed == "/" || trimmed == "-" || trimmed == "—" ||
            trimmed == "无" || trimmed == "暂无"
    }

    /**
     * 平台账单类目 → 系统分类的关键词映射（声明顺序即匹配优先级）。
     *
     * 支付宝/微信的类目名与系统分类名不同（如“餐饮美食”“交通出行”“日用百货”），
     * 精确匹配会落空；这里按“账单类目包含关键词”命中，未命中仍归 system-other。
     * 稳定行 ID 不含分类字段，因此映射调整不会改变已有流水的去重 ID。
     */
    private val CATEGORY_KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "system-food" to listOf("餐饮", "美食", "外卖", "夜宵"),
        "system-transport" to listOf("交通", "出行", "打车", "公交", "地铁", "加油", "火车", "机票", "网约车"),
        "system-daily-needs" to listOf("日用", "生活服务", "家政"),
        "system-shopping" to listOf("购物", "服饰", "装扮", "淘宝", "京东", "拼多多", "天猫", "百货", "商超"),
        "system-entertainment" to listOf("娱乐", "文化", "休闲", "游戏", "电影", "演出"),
        "system-travel" to listOf("旅行", "旅游", "酒店", "住宿", "度假"),
        "system-learning" to listOf("教育", "学习", "培训", "书籍", "书刊"),
        "system-digital" to listOf("数码", "电器", "电子", "话费", "通讯", "充值"),
        "system-income" to listOf("工资", "薪酬", "劳务报酬"),
    )

    /** 按关键词把账单类目映射到系统分类 ID；未命中返回 null（由调用方兜底 system-other）。 */
    private fun matchCategoryKeywords(categoryRaw: String): String? {
        val text = categoryRaw.trim()
        if (text.isEmpty()) return null
        return CATEGORY_KEYWORDS.firstOrNull { (_, keywords) -> keywords.any { text.contains(it) } }?.first
    }

    // MARK: - 表头别名

    private val ALIASES: Map<String, List<String>> = mapOf(
        "id" to listOf("id", "流水id", "编号"),
        "amount_minor" to listOf("amount_minor", "金额分", "金额(分)", "金额(最小单位)"),
        "amount" to listOf("amount", "金额", "金额元", "金额(元)"),
        "type" to listOf("type", "类型", "收支", "收支类型", "收/支", "收支"),
        "category_id" to listOf("category_id", "分类id", "分类", "分类名称", "分类编号", "标签", "交易分类", "消费分类", "账单分类"),
        "account_id" to listOf("account_id", "账户", "账户id", "账号", "银行卡", "支付方式", "收付款方式", "付款方式"),
        "date" to listOf("date", "时间", "日期", "日期时间", "记账时间", "交易时间", "时间戳"),
        "order_id" to listOf("order_id", "交易单号", "交易订单号", "商户单号", "商家订单号", "订单号", "流水号"),
        "status" to listOf("状态", "交易状态", "当前状态", "支付状态", "订单状态", "交易状态(成功)", "状态说明"),
        "note" to listOf("note", "备注", "描述"),
        "product" to listOf("商品", "商品说明", "商品名称", "商品描述"),
        "counterparty" to listOf("交易对方", "对方", "对方账号", "收款方", "付款方"),
        "date_time_epoch_millis" to listOf("date_time_epoch_millis", "时间戳毫秒"),
    )

    private fun canonicalColumn(raw: String): String? {
        val key = raw.trim().lowercase().filter { it.isLetterOrDigit() }
        if (key.isEmpty()) return null
        return ALIASES.entries.firstOrNull { key in it.value }?.key
    }

    // MARK: - XLSX 读取（简易实现：zip 条目 + 工作表/共享字符串文本解析）

    private fun parseXlsx(bytes: ByteArray): List<List<String>> {
        val parts = ZipArchiveReader.readEntries(bytes)
        if (parts.isEmpty()) throw DataTransferException("所选文件不是有效的 XLSX。")
        val utf8 = { name: String -> parts[name]?.decodeToString() }
        val sharedStrings = parseSharedStrings(utf8("xl/sharedStrings.xml"))
        val sheetPath = resolveFirstSheetPath(utf8("xl/workbook.xml"), utf8("xl/_rels/workbook.xml.rels"))
        val sheet = utf8(sheetPath)
            ?: throw DataTransferException("XLSX 中没有可读取的工作表。")
        return parseSheet(sheet, sharedStrings)
    }

    private val sharedStringRegex = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
    private val tagRegex = Regex("<[^>]+>")
    private val entityRegex = Regex("&(amp|lt|gt|quot|apos);")

    private fun parseSharedStrings(data: String?): List<String> {
        if (data == null) return emptyList()
        return sharedStringRegex.findAll(data).map { match ->
            tagRegex.replace(match.groupValues[1], "")
                .replace(entityRegex) { entity ->
                    when (entity.groupValues[1]) {
                        "amp" -> "&"
                        "lt" -> "<"
                        "gt" -> ">"
                        "quot" -> "\""
                        "apos" -> "'"
                        else -> entity.value
                    }
                }
        }.toList()
    }

    private val cellTagRegex = Regex("""<c\b[^>]*?(?:/>|>.*?</c>)""", RegexOption.DOT_MATCHES_ALL)
    private val cellRefRegex = Regex("""<c r="([A-Z]+)\d+""")
    private val cellTypeRegex = Regex("""\st="([^"]*)"""")
    private val cellValueRegex = Regex("""<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
    private val rowTagRegex = Regex("""<row r="\d+"[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)

    private fun resolveFirstSheetPath(workbook: String?, rels: String?): String {
        if (workbook != null && rels != null) {
            val sheetRId = Regex("""<sheet[^>]*r:id="([^"]+)"[^>]*/?>""")
                .find(workbook)?.groupValues?.get(1)
            if (sheetRId != null) {
                val target = Regex("""<Relationship[^>]*Id="([^"]*)"[^>]*Target="([^"]*)"[^>]*/?>""")
                    .findAll(rels)
                    .firstOrNull { it.groupValues[1] == sheetRId }
                    ?.groupValues?.get(2)
                if (!target.isNullOrBlank()) {
                    val cleaned = target.removePrefix("/")
                    return if (cleaned.startsWith("xl/")) cleaned else "xl/$cleaned"
                }
            }
        }
        return "xl/worksheets/sheet1.xml"
    }

    private fun parseSheet(sheet: String, sharedStrings: List<String>): List<List<String>> {
        val grid = mutableListOf<List<String>>()
        for (rowMatch in rowTagRegex.findAll(sheet)) {
            val rowXml = rowMatch.groupValues[1]
            val cells = mutableMapOf<Int, String>()
            var maxCol = -1
            for (cell in cellTagRegex.findAll(rowXml)) {
                val cellXml = cell.value
                val ref = cellRefRegex.find(cellXml)?.groupValues?.get(1) ?: continue
                val type = cellTypeRegex.find(cellXml)?.groupValues?.get(1) ?: ""
                val raw = cellValueRegex.find(cellXml)?.groupValues?.get(1) ?: ""
                var col = 0
                for (ch in ref) col = col * 26 + (ch - 'A' + 1)
                col -= 1
                maxCol = maxOf(maxCol, col)
                val value = when {
                    type == "s" -> raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: ""
                    else -> raw
                }
                cells[col] = value
            }
            if (cells.isNotEmpty()) {
                val rowValues = ArrayList<String>(maxCol + 1)
                for (col in 0..maxCol) rowValues.add(cells[col] ?: "")
                if (rowValues.any { it.isNotBlank() }) grid.add(rowValues)
            }
        }
        return grid
    }

    // MARK: - 宽容 CSV 解析（逗号或 Tab 分隔）

    private fun parseCsvTolerant(content: String): List<List<String>> {
        // 微信/支付宝导出的 CSV 可能用 Tab 分隔；若首行 Tab 数量明显多于逗号则按 Tab 解析。
        val firstLine = content.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
        val tabs = firstLine.count { it == '\t' }
        val commas = firstLine.count { it == ',' }
        val delimiter = if (tabs > commas) '\t' else ','
        return parseWithDelimiter(content, delimiter)
    }

    private fun parseWithDelimiter(content: String, delimiter: Char): List<List<String>> {
        // 按物理行解析，每行独立；账单类文件没有跨行引号字段，可避免状态串扰。
        val text = content.removePrefix("\uFEFF")
        val grid = mutableListOf<List<String>>()
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        for (rawLine in normalized.split("\n")) {
            val line = rawLine.trimEnd('\r')
            if (line.isBlank()) continue
            val cells = parseCsvLine(line, delimiter)
            if (cells.any { it.isNotEmpty() }) grid.add(cells)
        }
        return grid
    }

    private fun parseCsvLine(line: String, delimiter: Char): List<String> {
        val cells = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        val chars = line.toCharArray()
        while (index < chars.size) {
            val character = chars[index]
            when (character) {
                '"' -> {
                    if (inQuotes && index + 1 < chars.size && chars[index + 1] == '"') {
                        field.append('"')
                        index++
                    } else if (inQuotes) {
                        inQuotes = false
                    } else if (field.isEmpty()) {
                        inQuotes = true
                    } else {
                        // 宽容：字段中间的引号按普通字符处理
                        field.append('"')
                    }
                }
                delimiter -> if (inQuotes) {
                    field.append(character)
                } else {
                    cells.add(field.toString())
                    field.setLength(0)
                }
                else -> field.append(character)
            }
            index++
        }
        cells.add(field.toString())
        return cells
    }
}
