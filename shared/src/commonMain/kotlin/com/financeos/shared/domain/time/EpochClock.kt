package com.financeos.shared.domain.time

import kotlin.time.Clock

/**
 * 可注入的纪元毫秒时钟，统一供应“当前时间”，避免系统时间调用散落在业务里。
 *
 * 创建流水、软删、预算保存和 CSV 导入等写入路径都需要它打上同步元数据；生产环境使用
 * [system]，测试注入固定时钟即可让时间完全可复现。
 */
fun interface EpochClock {
    /** 当前时刻的 Unix 纪元毫秒数。 */
    fun nowMillis(): Long

    companion object {
        /** 基于系统 UTC 时钟的默认实现，三端语义一致。 */
        val system: EpochClock = EpochClock { Clock.System.now().toEpochMilliseconds() }
    }
}
