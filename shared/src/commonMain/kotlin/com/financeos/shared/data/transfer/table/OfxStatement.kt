package com.financeos.shared.data.transfer.table

import com.financeos.shared.data.transfer.DataTransferException
import kotlin.math.abs
import kotlin.time.Instant

private val ofxRoot = Regex("""<OFX\b""", RegexOption.IGNORE_CASE)

internal fun looksLikeOfx(text: String): Boolean = ofxRoot.containsMatchIn(text.take(4_096))

/** OFX 1.x SGML and 2.x XML bank/credit-card statement transactions. */
internal fun readOfxRows(text: String): StatementTable {
    val rows = mutableListOf(StatementRow(1, listOf(
        "date_time_epoch_millis", "amount", "merchant", "memo", "fitid", "account", "currency", "direction",
    )))
    val institutionId = field(text.substringBefore("<BANKMSGSRSV1", text).substringBefore("<CREDITCARDMSGSRSV1", text), "FID")
    for (statement in statementBlock.findAll(text)) {
        val body = statement.groupValues[2]
        val account = field(body.substringBefore("<BANKTRANLIST", body), "ACCTID")
        val institution = field(body, "BANKID").ifBlank { institutionId }
        val accountLabel = when {
            account.isBlank() -> ""
            institution.isBlank() -> account
            else -> "$institution · $account"
        }
        val currency = field(body, "CURDEF")
        for (transaction in transactionBlock.findAll(body)) {
            if (rows.size >= MAX_STATEMENT_ROWS) throw DataTransferException("账单行数过多，请拆分后导入。")
            val entry = transaction.groupValues[1]
            val direction = if (field(entry, "TRNTYPE").uppercase() == "XFER") "transfer" else ""
            rows += StatementRow(rows.size + 1, listOf(
                parseOfxDate(field(entry, "DTPOSTED")),
                field(entry, "TRNAMT"),
                field(entry, "NAME"),
                field(entry, "MEMO"),
                field(entry, "FITID"),
                accountLabel,
                currency,
                direction,
            ))
        }
    }
    if (rows.size == 1) throw DataTransferException("OFX 中没有可读取的银行或信用卡流水。")
    return StatementTable(rows, "OFX")
}

private val statementBlock = Regex("""<(STMTRS|CCSTMTRS)\b[^>]*>(.*?)</\1\s*>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val transactionBlock = Regex("""<STMTTRN\b[^>]*>(.*?)</STMTTRN\s*>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val fields = listOf("ACCTID", "BANKID", "FID", "CURDEF", "TRNTYPE", "DTPOSTED", "TRNAMT", "NAME", "MEMO", "FITID")
    .associateWith { tag -> Regex("""<$tag\s*>\s*([^<\r\n]+)""", RegexOption.IGNORE_CASE) }

private fun field(content: String, name: String): String =
    fields.getValue(name).find(content)?.groupValues?.get(1)?.trim()?.let(::decodeXmlText).orEmpty()

private val ofxDate = Regex("""^(\d{4})(\d{2})(\d{2})(?:(\d{2})(\d{2})(\d{2})(\.\d+)?)?(?:\[([+-]?\d+(?:\.\d+)?)(?::[^\]]+)?\])?$""")

private fun parseOfxDate(raw: String): String {
    val match = ofxDate.matchEntire(raw.replace(" ", "")) ?: return ""
    val year = match.groupValues[1]
    val month = match.groupValues[2]
    val day = match.groupValues[3]
    val hour = match.groupValues[4].ifEmpty { "00" }
    val minute = match.groupValues[5].ifEmpty { "00" }
    val second = match.groupValues[6].ifEmpty { "00" }
    val fraction = match.groupValues[7]
    val offsetMinutes = match.groupValues[8].toDoubleOrNull()?.let { (it * 60).toInt() } ?: 0
    if (offsetMinutes !in -1_080..1_080) return ""
    val sign = if (offsetMinutes < 0) "-" else "+"
    val absoluteOffset = abs(offsetMinutes)
    val offset = "$sign${(absoluteOffset / 60).toString().padStart(2, '0')}:${(absoluteOffset % 60).toString().padStart(2, '0')}"
    return try {
        Instant.parse("$year-$month-${day}T$hour:$minute:$second$fraction$offset")
            .toEpochMilliseconds().toString()
    } catch (_: IllegalArgumentException) {
        ""
    }
}
