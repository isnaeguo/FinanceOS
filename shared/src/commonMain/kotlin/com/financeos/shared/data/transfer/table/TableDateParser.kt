package com.financeos.shared.data.transfer.table

/**
 * 账单日期解析，语义对齐 Android 端 `TableTransactionImporter`：
 * 大于 1e11 的纯数字按 Unix 毫秒；带小数按 Excel 序列日期；其余按本地文本时间。
 * 所有"本地时间 → 纪元毫秒"统一经 [localDateTimeToEpochMillis]（各平台用系统时区），
 * 保证同一份账单在任何端得到相同的时间戳与去重 ID。
 */
internal object TableDateParser {
    /** Unix 毫秒阈值：超过即视为绝对时间戳，否则按 Excel 序列处理。 */
    private const val UNIX_MILLIS_THRESHOLD = 100_000_000_000L

    fun resolveDateMillis(raw: String, lineNumber: Int, onError: (String) -> Nothing): Long {
        val text = raw.trim()
        if (text.isBlank()) onError("第 $lineNumber 行“日期”为空")

        val digitsOnly = text.filter { it.isDigit() || it == '.' || it == '-' }
        if (digitsOnly.isNotEmpty() && digitsOnly.all { it.isDigit() || it == '.' || it == '-' }) {
            parseDecimal(digitsOnly)?.let { number ->
                if (number >= UNIX_MILLIS_THRESHOLD) {
                    return number
                }
                return excelSerialToMillis(digitsOnly, number, lineNumber, onError)
            }
        }

        parseLocalDateTime(text.replace('T', ' '))?.let { fields ->
            return localDateTimeToEpochMillis(
                year = fields[0],
                month = fields[1],
                dayOfMonth = fields[2],
                hour = fields[3],
                minute = fields[4],
                second = fields[5],
            )
        }
        // 部分账单直接把毫秒时间戳写成纯文本，此处兜底。
        text.toLongOrNull()?.let { if (it > UNIX_MILLIS_THRESHOLD) return it }
        onError("第 $lineNumber 行“日期”无法识别：$text")
    }

    /**
     * 解析 "yyyy-MM-dd HH:mm:ss" / "yyyy-MM-dd HH:mm" / "yyyy-MM-dd" /
     * "yyyy/M/d HH:mm:ss" / "yyyy/M/d HH:mm" / "yyyy/M/d" 等常见中文账单格式。
     * 返回 [year, month, day, hour, minute, second]。
     */
    private val datePattern = Regex(
        """^\s*(\d{4})\s*[-/年.]\s*(\d{1,2})\s*[-/月.]\s*(\d{1,2})\s*[日号]?(?:\s+(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?)?\s*$""",
    )

    private fun parseLocalDateTime(text: String): IntArray? {
        val match = datePattern.find(text) ?: return null
        fun group(index: Int): Int = match.groupValues[index].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        val month = group(2)
        val day = group(3)
        if (month !in 1..12 || day !in 1..31) return null
        return intArrayOf(group(1), month, day, group(4), group(5), group(6))
    }

    /** 解析可能带小数的十进制字符串为整数毫秒/天表示；失败返回 null。 */
    private fun parseDecimal(text: String): Long? {
        val negative = text.startsWith('-')
        val unsigned = if (negative) text.substring(1) else text
        val dotIndex = unsigned.indexOf('.')
        val integerPart = if (dotIndex >= 0) unsigned.substring(0, dotIndex) else unsigned
        if (integerPart.isEmpty() && dotIndex == 0) return null
        val whole = integerPart.ifEmpty { "0" }.toLongOrNull() ?: return null
        val value = if (negative) -whole else whole
        // 含小数的值不是整数毫秒，交给 Excel 序列分支处理。
        return if (dotIndex >= 0) value else value.takeIf { unsigned.isNotEmpty() }
    }

    /** Excel 序列 → 纪元毫秒；与 Android 端的 BigDecimal 两步舍入语义一致。 */
    private fun excelSerialToMillis(
        raw: String,
        wholeDays: Long,
        lineNumber: Int,
        onError: (String) -> Nothing,
    ): Long {
        val dotIndex = raw.indexOf('.')
        val fractionText = if (dotIndex >= 0) raw.substring(dotIndex + 1) else ""
        // 小数部分取 6 位并四舍五入（HALF_UP），与 Android 端 setScale(6, HALF_UP) 一致。
        val frac6 = roundToSixDigits(fractionText)
        // 秒 = frac6 / 1e6 天 × 86400，四舍五入到整数秒。
        val secondsOfDay = ((frac6 * 86_400L) + 500_000L) / 1_000_000L
        val civil = civilFromDays(wholeDays - EXCEL_EPOCH_DAYS_TO_UNIX)
        val base = localDateTimeToEpochMillis(
            year = civil[0],
            month = civil[1],
            dayOfMonth = civil[2],
            hour = 0,
            minute = 0,
            second = 0,
        )
        if (base < 0) onError("第 $lineNumber 行“日期”超出可表示范围：$raw")
        return base + secondsOfDay * 1000L
    }

    private fun roundToSixDigits(fraction: String): Long {
        if (fraction.isEmpty()) return 0L
        val significant = fraction.take(7).padEnd(7, '0')
        var value = significant.substring(0, 6).toLong()
        if (significant[6] >= '5') value += 1L
        return value
    }

    private const val EXCEL_EPOCH_DAYS_TO_UNIX = 25_569L // 1899-12-30 → 1970-01-01

    /** 自 1970-01-01 起的天数 → (年, 月, 日)，使用 Hinnant 逆算法（公历，可处理负数）。 */
    internal fun civilFromDays(daysSinceUnixEpoch: Long): IntArray {
        val z = daysSinceUnixEpoch + 719_468L
        val era = if (z >= 0) z else z - 146_096L
        val dayOfEra = era - (era / 146_097L) * 146_097L
        val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
        val year = yearOfEra + (era / 146_097L) * 400L
        val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
        val monthPrime = (5L * dayOfYear + 2L) / 153L
        val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
        val month = monthPrime + if (monthPrime < 10L) 3L else -9L
        return intArrayOf((year + if (month <= 2L) 1L else 0L).toInt(), month.toInt(), day.toInt())
    }
}
