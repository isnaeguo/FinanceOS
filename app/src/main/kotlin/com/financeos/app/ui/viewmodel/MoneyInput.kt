package com.financeos.app.ui.viewmodel

private const val MAX_MAJOR_DIGITS = 9
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
    if (input.isBlank() || input == ".") return null

    val parts = input.split('.', limit = 2)
    val major = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
    val minor = parts.getOrNull(1)
        ?.padEnd(2, '0')
        ?.take(2)
        ?.toLongOrNull()
        ?: 0L
    val amount = major * 100L + minor
    return amount.takeIf { it > 0L || (allowZero && it == 0L) }
}

/** 使用统一的人民币符号、千位分隔和两位小数展示非负金额。 */
internal fun formatMoney(amountMinor: Long): String {
    require(amountMinor >= 0L) { "Money amount must not be negative." }
    val major = amountMinor / 100L
    val minor = amountMinor % 100L
    val groupedMajor = major.toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()
    return "¥$groupedMajor.${minor.toString().padStart(2, '0')}"
}
