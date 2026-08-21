package com.financeos.shared.domain.model

import kotlin.time.Instant

/**
 * 用户时区中的一个月份及其对应的绝对时间边界。
 *
 * 平台层负责按用户时区生成边界，Domain 只使用半开区间，避免自行假定设备或 UTC 时区。
 */
data class MonthPeriod(
    val month: BudgetMonth,
    val startInclusive: Instant,
    val endExclusive: Instant,
) {
    init {
        require(startInclusive < endExclusive) {
            "Month period must have a positive duration."
        }
    }
}
