package com.financeos.shared.data.transfer.table

import kotlinx.cinterop.*
import platform.CoreFoundation.CFStringCreateWithBytes
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringGetMaximumSizeForEncoding
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.NSCalendar
import platform.Foundation.NSDateComponents
import platform.Foundation.NSTimeZone
import platform.Foundation.systemTimeZone
import platform.Foundation.timeIntervalSince1970

private const val GB_18030_ENCODING = 0x0631U // kCFStringEncodingGB_18030_2000

/** 与 Android 端同源：UTF-8 出现替换字符时按 GB18030 重试。 */
@OptIn(ExperimentalForeignApi::class)
actual fun decodeSpreadsheetText(bytes: ByteArray): String {
    val utf8 = bytes.decodeToString()
    if (!utf8.contains('\uFFFD')) return utf8.removePrefix("\uFEFF")
    return memScoped {
        val source = allocArray<UByteVar>(bytes.size)
        var fillIndex = 0
        while (fillIndex < bytes.size) {
            source[fillIndex] = bytes[fillIndex].toUByte()
            fillIndex += 1
        }
        val decoded = CFStringCreateWithBytes(
            alloc = null,
            bytes = source,
            numBytes = bytes.size.toLong(),
            encoding = GB_18030_ENCODING,
            isExternalRepresentation = false,
        ) ?: return@memScoped utf8.removePrefix("\uFEFF")
        try {
            val maxSize =
                CFStringGetMaximumSizeForEncoding(CFStringGetLength(decoded), kCFStringEncodingUTF8).toInt() + 1
            val buffer = allocArray<ByteVar>(maxSize)
            if (CFStringGetCString(decoded, buffer, maxSize.toLong(), kCFStringEncodingUTF8)) {
                var endIndex = 0
                while (endIndex < maxSize && buffer[endIndex] != 0.toByte()) {
                    endIndex += 1
                }
                val result = ByteArray(endIndex)
                var readIndex = 0
                while (readIndex < endIndex) {
                    result[readIndex] = buffer[readIndex]
                    readIndex += 1
                }
                result.decodeToString()
            } else {
                utf8.removePrefix("\uFEFF")
            }
        } finally {
            CFRelease(decoded)
        }
    }
}

/** 与 Android 端 `LocalDateTime.atZone(ZoneId.systemDefault())` 语义一致。 */
@OptIn(ExperimentalForeignApi::class)
actual fun localDateTimeToEpochMillis(
    year: Int,
    month: Int,
    dayOfMonth: Int,
    hour: Int,
    minute: Int,
    second: Int,
): Long {
    val calendar = NSCalendar.currentCalendar()
    calendar.timeZone = NSTimeZone.systemTimeZone
    val components = NSDateComponents()
    components.year = year.toLong()
    components.month = month.toLong()
    components.day = dayOfMonth.toLong()
    components.hour = hour.toLong()
    components.minute = minute.toLong()
    components.second = second.toLong()
    val date = calendar.dateFromComponents(components)
        ?: error("无效的本地日期：$year-$month-$dayOfMonth")
    return (date.timeIntervalSince1970 * 1000).toLong()
}
