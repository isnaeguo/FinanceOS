package com.financeos.shared.data.transfer.table

import com.financeos.shared.data.transfer.DataTransferException
import kotlin.time.Instant

internal object TableDateParser {
    private val yearFirst = Regex("""^(\d{4})\s*[-/年.]\s*(\d{1,2})\s*[-/月.]\s*(\d{1,2})\s*[日号]?(?:[ T]+(\d{1,2}):(\d{2})(?::(\d{2}))?)?$""")
    private val yearLast = Regex("""^(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})(?:[ T]+(\d{1,2}):(\d{2})(?::(\d{2}))?)?$""")
    private val excelNumber = Regex("""\d{1,7}(?:\.\d+)?""")

    fun resolveDateMillis(
        raw: String,
        line: Int,
        preferUsDates: Boolean = false,
        epochMillis: Boolean = false,
        usesExcel1904Dates: Boolean = false,
    ): Long {
        val text = raw.trim()
        fun invalid(): Nothing = throw DataTransferException("第 $line 行的日期无效。请使用 YYYY-MM-DD 或标准时间戳。")

        if (epochMillis) return text.toLongOrNull() ?: invalid()
        if (text.length >= 17 && ('T' in text || ' ' in text)) {
            parseInstant(text.replace(' ', 'T'))?.let { return it }
        }
        text.toLongOrNull()?.let {
            if (text.length == 13) return it
            if (text.length == 10 && it > 0) return it * 1_000L
            if (text.length == 8 && it >= 10_000_000L) {
                return localMillis((it / 10_000L).toInt(), (it / 100L % 100L).toInt(), (it % 100L).toInt(), 0, 0, 0)
                    ?: invalid()
            }
        }
        yearFirst.matchEntire(text)?.let {
            return fieldsToMillis(it.field(1), it.field(2), it.field(3), it) ?: invalid()
        }
        yearLast.matchEntire(text)?.let {
            val first = it.field(1)
            val second = it.field(2)
            if (!preferUsDates && first <= 12 && second <= 12 && first != second) {
                throw DataTransferException("第 $line 行的日期可能是月/日或日/月，请改为 YYYY-MM-DD。")
            }
            val dayFirst = !preferUsDates && first > 12
            val month = if (dayFirst) second else first
            val day = if (dayFirst) first else second
            return fieldsToMillis(it.field(3), month, day, it) ?: invalid()
        }
        if (excelNumber.matches(text)) return excelMillis(text, usesExcel1904Dates) ?: invalid()
        invalid()
    }

    private fun MatchResult.field(index: Int): Int = groupValues[index].toIntOrNull() ?: 0

    private fun fieldsToMillis(year: Int, month: Int, day: Int, fields: MatchResult): Long? =
        localMillis(year, month, day, fields.field(4), fields.field(5), fields.field(6))

    private fun parseInstant(text: String): Long? = try {
        Instant.parse(text).toEpochMilliseconds()
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun localMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long? {
        // Validate using a fixed Gregorian date before a platform calendar can normalize impossible dates.
        val dateTime = "${year.toString().padStart(4, '0')}-${month.two()}-${day.two()}T${hour.two()}:${minute.two()}:${second.two()}Z"
        if (year !in 1..9999 || hour !in 0..23 || minute !in 0..59 || second !in 0..59 ||
            parseInstant(dateTime) == null) return null
        return localDateTimeToEpochMillis(year, month, day, hour, minute, second)
    }

    private fun Int.two(): String = toString().padStart(2, '0')

    private fun excelMillis(value: String, usesExcel1904Dates: Boolean): Long? {
        val sourceSerial = value.substringBefore('.').toLong()
        // The workbook epoch presets differ by 1,462 days.
        val serial = if (usesExcel1904Dates) sourceSerial + 1_462L else sourceSerial
        if (serial !in 1L..2_958_465L || serial == 60L) return null
        val fraction = value.substringAfter('.', "").take(7).padEnd(7, '0').toLong()
        val sixDigits = (fraction + 5L) / 10L
        val seconds = ((sixDigits * 86_400L) + 500_000L) / 1_000_000L
        val offset = if (serial < 60L) 25_568L else 25_569L
        val (year, month, day) = civilFromDays(serial - offset + seconds / 86_400L)
        val time = seconds % 86_400L
        return localMillis(year, month, day, (time / 3_600).toInt(), (time / 60 % 60).toInt(), (time % 60).toInt())
    }

    /** 自 1970-01-01 起的天数 → (年, 月, 日) */
    internal fun civilFromDays(daysSinceUnixEpoch: Long): IntArray {
        val days = daysSinceUnixEpoch + 719_468L
        val era = if (days >= 0) days else days - 146_096L
        val dayOfEra = days - (era / 146_097L) * 146_097L
        val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
        val year = yearOfEra + (era / 146_097L) * 400L
        val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
        val monthPrime = (5L * dayOfYear + 2L) / 153L
        val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
        val month = monthPrime + if (monthPrime < 10L) 3L else -9L
        return intArrayOf((year + if (month <= 2L) 1L else 0L).toInt(), month.toInt(), day.toInt())
    }
}
