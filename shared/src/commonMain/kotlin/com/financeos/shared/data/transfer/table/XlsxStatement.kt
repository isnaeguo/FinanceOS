package com.financeos.shared.data.transfer.table

import com.financeos.shared.data.transfer.DataTransferException

internal fun readXlsxStatement(bytes: ByteArray): StatementTable {
    val files = ZipArchiveReader.readEntries(bytes)
    val workbook = files["xl/workbook.xml"]?.decodeToString()
        ?: throw DataTransferException("此 ZIP 不是 XLSX 工作簿。请解压账单后选择其中的表格文件。")
    val relationships = files["xl/_rels/workbook.xml.rels"]?.decodeToString()
    val relation = sheetTag.find(workbook)?.value?.let { attribute(it, "r:id") }
    val target = relationships?.let(relationshipTag::findAll)?.firstOrNull {
        attribute(it.value, "Id") == relation
    }?.value?.let { attribute(it, "Target") }
    val path = target?.removePrefix("/")?.let { if (it.startsWith("xl/")) it else "xl/$it" }
        ?: "xl/worksheets/sheet1.xml"
    val sheet = files[path]?.decodeToString() ?: throw DataTransferException("XLSX 中没有可读取的工作表。")
    val shared = files["xl/sharedStrings.xml"]?.decodeToString()?.let { input ->
        sharedString.findAll(input).take(MAX_STATEMENT_ROWS * 8).map { textRuns(it.value) }.toList()
    }.orEmpty()

    val date1904 = workbookProperties.find(workbook)?.value?.let { attribute(it, "date1904") } in setOf("1", "true")
    val rows = mutableListOf<StatementRow>()
    for (row in rowTag.findAll(sheet)) {
        val values = mutableListOf<String>()
        val number = attribute(row.groupValues[1], "r")?.toIntOrNull() ?: rows.size + 1
        for (cell in cellTag.findAll(row.groupValues[2])) {
            val attributes = cell.groupValues[1]
            val body = cell.groupValues[2]
            val reference = attribute(attributes, "r")
            val column = reference?.let(::columnIndex) ?: values.size
            if (column !in 0 until MAX_STATEMENT_COLUMNS) continue
            while (values.size <= column) values.add("")
            val raw = cellValue.find(body)?.groupValues?.get(1).orEmpty()
            values[column] = when (attribute(attributes, "t")) {
                "s" -> raw.toIntOrNull()?.let(shared::getOrNull).orEmpty()
                "inlineStr" -> textRuns(body)
                else -> decodeXmlText(raw)
            }
        }
        if (values.any(String::isNotBlank)) {
            if (rows.size >= MAX_STATEMENT_ROWS) throw DataTransferException("账单行数过多，请拆分后导入。")
            rows += StatementRow(number, values)
        }
    }
    return StatementTable(rows, "XLSX", usesExcel1904Dates = date1904)
}

private fun columnIndex(reference: String): Int {
    val letters = reference.takeWhile { it in 'A'..'Z' }
    if (letters.length !in 1..3) return -1
    return letters.fold(0) { column, character -> column * 26 + (character - 'A' + 1) } - 1
}

private fun textRuns(xml: String): String = textTag.findAll(xml).joinToString("") { decodeXmlText(it.groupValues[1]) }

private fun attribute(tag: String, key: String): String? =
    attributes.findAll(tag).firstOrNull { it.groupValues[1] == key }?.groupValues?.get(3)?.let(::decodeXmlText)

private val attributes = Regex("""([A-Za-z_:][-\w:.]*)\s*=\s*(["'])(.*?)\2""")
private val sheetTag = Regex("""<sheet\b[^>]*>""")
private val workbookProperties = Regex("""<workbookPr\b[^>]*>""")
private val relationshipTag = Regex("""<Relationship\b[^>]*>""")
private val rowTag = Regex("""<row\b([^>]*?)(?:/>|>(.*?)</row\s*>)""", RegexOption.DOT_MATCHES_ALL)
private val cellTag = Regex("""<c\b([^>]*?)(?:/>|>(.*?)</c\s*>)""", RegexOption.DOT_MATCHES_ALL)
private val sharedString = Regex("""<si\b[^>]*>.*?</si\s*>""", RegexOption.DOT_MATCHES_ALL)
private val textTag = Regex("""<t\b[^>]*>(.*?)</t\s*>""", RegexOption.DOT_MATCHES_ALL)
private val cellValue = Regex("""<v\b[^>]*>(.*?)</v\s*>""", RegexOption.DOT_MATCHES_ALL)
private val xmlEntity = Regex("""&(amp|lt|gt|quot|apos|#\d{1,7}|#x[\da-fA-F]{1,6});""")

internal fun decodeXmlText(text: String): String = text.replace(xmlEntity) { entity ->
    when (val value = entity.groupValues[1]) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        else -> {
            val point = if (value.startsWith("#x")) value.drop(2).toIntOrNull(16) else value.drop(1).toIntOrNull()
            when {
                point == null || point !in 1..0x10ffff || point in 0xd800..0xdfff -> entity.value
                point <= 0xffff -> point.toChar().toString()
                else -> {
                    val suffix = point - 0x10000
                    charArrayOf((0xd800 + (suffix shr 10)).toChar(), (0xdc00 + (suffix and 0x3ff)).toChar()).concatToString()
                }
            }
        }
    }
}
