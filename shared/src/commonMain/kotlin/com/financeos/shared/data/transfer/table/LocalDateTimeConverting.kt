package com.financeos.shared.data.transfer.table

/**
 * 按设备当前时区把本地时间字段转换为 Unix 纪元毫秒。
 *
 * 账单中的日期都是无时区标记的本地时间，导入语义必须与 Android 端
 * `LocalDateTime.atZone(ZoneId.systemDefault())` 一致。
 */
expect fun localDateTimeToEpochMillis(
    year: Int,
    month: Int,
    dayOfMonth: Int,
    hour: Int,
    minute: Int,
    second: Int,
): Long
