package com.financeos.shared.data.transfer.table

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId

private val gb18030: Charset = Charset.forName("GB18030")

/** Android 端导入器的同源实现：UTF-8 出现替换字符时按 GB18030 重试。 */
actual fun decodeSpreadsheetText(bytes: ByteArray): String {
    val utf8 = String(bytes, StandardCharsets.UTF_8)
    if (!utf8.contains('\uFFFD')) return utf8.removePrefix("\uFEFF")
    return try {
        String(bytes, gb18030)
    } catch (_: Exception) {
        utf8.removePrefix("\uFEFF")
    }
}

/** 与 Android 端 `LocalDateTime.atZone(ZoneId.systemDefault())` 完全一致。 */
actual fun localDateTimeToEpochMillis(
    year: Int,
    month: Int,
    dayOfMonth: Int,
    hour: Int,
    minute: Int,
    second: Int,
): Long = LocalDateTime.of(year, month, dayOfMonth, hour, minute, second, 0)
    .atZone(ZoneId.systemDefault())
    .toInstant()
    .toEpochMilli()
