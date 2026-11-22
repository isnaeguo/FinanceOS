package com.financeos.shared.data.local

/** 保证同一记录在时钟回拨或同毫秒多次写入时仍按修改顺序同步。 */
internal fun nextLocalRevision(previous: Long, current: Long): Long =
    maxOf(current, previous.coerceAtMost(Long.MAX_VALUE - 1) + 1)
