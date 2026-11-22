package com.financeos.app.ui.viewmodel

private const val MAX_MAJOR_DIGITS = 17
private val amountInputPattern = Regex("^(?:\\d{0,$MAX_MAJOR_DIGITS})(?:\\.\\d{0,2})?$")

/**
 * 过滤手机金额输入，只保留最多两位小数的十进制格式。
 *
 * 逗号同时作为小数点接受，便于使用不同区域设置的数字键盘。
 */
internal fun normalizeAmountInput(rawInput: String): String? {
    val normalized = rawInput.replace(',', '.')
    return normalized.takeIf { it.isEmpty() || amountInputPattern.matches(it) }
}

/**
 * 将用户输入精确转换为最小货币单位，整个过程不经过 Double，避免二进制浮点误差。
 */
internal fun parseAmountInMinorUnits(
    input: String,
    allowZero: Boolean = false,
): Long? {
    val normalized = normalizeAmountInput(input) ?: return null
    if (normalized.isEmpty() || normalized == ".") return null

    val major = normalized.substringBefore('.').ifEmpty { "0" }.toLong()
    val minor = normalized.substringAfter('.', "").padEnd(2, '0').toLong()
    if (major > (Long.MAX_VALUE - minor) / 100L) return null
    val amount = major * 100L + minor
    return amount.takeIf { it > 0L || (allowZero && it == 0L) }
}

/** 使用统一的人民币符号、千位分隔和两位小数展示非负金额。 */
internal fun formatMoney(amountMinor: Long): String {
    // 净支出允许为负（支出−收入）：负数按绝对值格式化并加负号前缀，避免整数除法的截断错误。
    val negative = amountMinor < 0L
    val major = kotlin.math.abs(amountMinor / 100L)
    val minor = kotlin.math.abs(amountMinor % 100L)
    val groupedMajor = major.toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()
    return (if (negative) "-" else "") + "¥$groupedMajor.${minor.toString().padStart(2, '0')}"
}

internal fun amountInputFromMinorUnits(amount: Long): String =
    "${amount / 100}.${(amount % 100).toString().padStart(2, '0')}"
