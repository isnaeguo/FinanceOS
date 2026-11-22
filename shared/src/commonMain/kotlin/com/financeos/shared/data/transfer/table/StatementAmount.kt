package com.financeos.shared.data.transfer.table

import com.financeos.shared.data.transfer.DataTransferException
import kotlin.math.absoluteValue

internal fun resolveStatementAmount(
    amount: String,
    credit: String,
    debit: String,
    minorUnits: Boolean,
    line: Int,
): Long {
    if (amount.isNotBlank()) return parseStatementAmount(amount, minorUnits, line)
    val incoming = parseStatementAmount(credit.ifBlank { "0" }, false, line)
    val outgoing = parseStatementAmount(debit.ifBlank { "0" }, false, line)
    if (incoming != 0L && outgoing != 0L) {
        throw DataTransferException("第 $line 行同时包含非零收入和支出。")
    }
    if (credit.isBlank() && debit.isBlank()) throw DataTransferException("第 $line 行缺少金额。")
    return if (incoming != 0L) incoming.absoluteValue else -outgoing.absoluteValue
}

private val groupedAmount = Regex("""[0-9]{1,3}(?:,[0-9]{3})+""")
private val majorAmount = Regex("""(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)""")
private val integerAmount = Regex("""[0-9]+""")

private fun parseStatementAmount(raw: String, minorUnits: Boolean, line: Int): Long {
    fun invalid(): Nothing = throw DataTransferException("第 $line 行的金额无法解析或超出范围。")

    var text = raw.trim().replace('−', '-').replace('，', ',').replace('．', '.')
    if (text in amountPlaceholders) return 0L
    val parenthesized = text.startsWith('(') && text.endsWith(')')
    if (parenthesized) text = text.drop(1).dropLast(1).trim()
    val prefixSign = text.firstOrNull()?.takeIf { it == '+' || it == '-' }
    if (prefixSign != null) text = text.drop(1).trim()
    if (text.firstOrNull() in currencySymbols) text = text.drop(1).trim()
    currencyPrefix.find(text)?.let { text = text.substring(it.range.last + 1).trim() }
    currencySuffix.find(text)?.let { text = text.substring(0, it.range.first).trim() }
    if (text.endsWith("元")) text = text.dropLast(1).trim()
    val amountSign = text.firstOrNull()?.takeIf { it == '+' || it == '-' }
    if (amountSign != null) text = text.drop(1).trim()
    if (parenthesized && (prefixSign != null || amountSign != null) || prefixSign != null && amountSign != null) invalid()
    val negative = parenthesized || prefixSign == '-' || amountSign == '-'
    val wholeText = text.substringBefore('.')
    if (',' in wholeText && !groupedAmount.matches(wholeText)) invalid()
    if (',' in text.substringAfter('.', "")) invalid()
    text = text.replace(",", "")
    if (minorUnits) {
        if (!integerAmount.matches(text)) invalid()
        val value = text.toLongOrNull() ?: invalid()
        return if (negative) -value else value
    }
    if (!majorAmount.matches(text)) invalid()
    val whole = text.substringBefore('.').ifEmpty { "0" }.toLongOrNull() ?: invalid()
    val decimals = text.substringAfter('.', "")
    val fraction = decimals.take(2).padEnd(2, '0').toLong() +
        if ((decimals.getOrNull(2) ?: '0') >= '5') 1L else 0L
    if (whole > (Long.MAX_VALUE - fraction) / 100L) invalid()
    val value = whole * 100L + fraction
    return if (negative) -value else value
}

private val amountPlaceholders = setOf("-", "—", "/")
private val currencySymbols = setOf('￥', '¥', '$', '€', '£')
private val currencyPrefix = Regex("""^([A-Za-z]{3})(?=[\s0-9.+-])""")
private val currencySuffix = Regex("""([A-Za-z]{3})$""")

internal fun normalizeCurrency(raw: String): String? {
    val code = raw.trim().uppercase()
    return when {
        code == "RMB" || code == "人民币" -> "CNY"
        code.length == 3 && code.all { it in 'A'..'Z' } -> code
        else -> null
    }
}

internal fun currencyFromAmount(raw: String): String? {
    val text = raw.trim().trim('(', ')', '-', '+', '−', ' ')
    val code = currencyPrefix.find(text)?.groupValues?.get(1) ?: currencySuffix.find(text)?.groupValues?.get(1)
    return when (text.firstOrNull()) {
        '€' -> "EUR"
        '£' -> "GBP"
        '￥' -> "CNY"
        else -> code?.let(::normalizeCurrency)
    }
}

private const val EXCHANGE_SCALE = 1_000_000L
private val exchangeRatePattern = Regex("""[0-9]{1,6}(?:\.[0-9]{1,6})?""")

internal fun parseExchangeRate(text: String): Long {
    if (!exchangeRatePattern.matches(text)) invalidRate()
    val whole = text.substringBefore('.').toLong()
    val fraction = text.substringAfter('.', "").padEnd(6, '0').toLong()
    return (whole * EXCHANGE_SCALE + fraction).takeIf { it > 0 } ?: invalidRate()
}

private fun invalidRate(): Nothing =
    throw DataTransferException("请填写大于零的人民币换算汇率，最多保留六位小数。")

internal fun convertedToCnyMinor(source: Long, rate: Long): Long {
    val base = source / EXCHANGE_SCALE
    val tail = ((source % EXCHANGE_SCALE) * rate + EXCHANGE_SCALE / 2) / EXCHANGE_SCALE
    if (base > (Long.MAX_VALUE - tail) / rate) {
        throw DataTransferException("换算后有流水金额超出范围，请检查汇率。")
    }
    return base * rate + tail
}
