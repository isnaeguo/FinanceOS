package com.financeos.shared.lansync

import kotlin.math.abs

/**
 * 配对加密协议的纯策略函数与标准错误响应，三端服务端共用，保证提示文案与判定一致。
 */
object LanSyncPolicy {
    /** 时间戳新鲜度：|now - ts| 超出容忍窗口视为重放/过期。 */
    fun isTimestampFresh(ts: Long, nowMillis: Long): Boolean =
        abs(nowMillis - ts) <= LanSyncSpec.TS_TOLERANCE_MILLIS

    /** 旧版明文客户端升级提示（HTTP 426）。 */
    fun upgradeRequiredBody(): String = jsonError("对方客户端版本过旧，请升级 FinanceOS")

    /** 配对码错误或数据损坏（HTTP 401）。 */
    fun authFailedBody(): String = jsonError("配对码错误或数据已损坏")

    /** 请求过于频繁（HTTP 429）。 */
    fun rateLimitedBody(): String = jsonError("尝试过于频繁，请重新开启接收并重试")

    /** 时间戳越界（HTTP 400）。 */
    fun staleTimestampBody(): String = jsonError("请求已过期，请检查两端系统时间")

    private fun jsonError(message: String): String = "{\"error\":\"$message\"}"
}
