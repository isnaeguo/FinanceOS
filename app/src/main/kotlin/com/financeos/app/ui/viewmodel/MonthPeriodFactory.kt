package com.financeos.app.ui.viewmodel

import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.model.MonthPeriod
import java.time.YearMonth
import java.time.ZoneId
import kotlin.time.Instant

/**
 * 按设备时区生成月份半开区间。
 *
 * 月初使用当地零点再转为 Instant，能够正确处理夏令时，避免直接用固定 UTC 边界漏算流水。
 */
internal fun YearMonth.toMonthPeriod(zoneId: ZoneId): MonthPeriod {
    val start = atDay(1).atStartOfDay(zoneId).toInstant()
    val end = plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant()
    return MonthPeriod(
        month = BudgetMonth(year = year, month = monthValue),
        startInclusive = Instant.fromEpochMilliseconds(start.toEpochMilli()),
        endExclusive = Instant.fromEpochMilliseconds(end.toEpochMilli()),
    )
}
