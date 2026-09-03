package com.financeos.shared.design

/**
 * FinanceOS 跨端设计 token —— 纯 UI 表现层数据，非领域逻辑。
 *
 * 取色纪律：数值以 macOS 源文件为准，Android/iOS 各自把 token 映射到本平台实现。
 * 下表为「token → macOS 源符号」的逐项对照（可追溯依据）：
 *
 * | token | 值 | macOS 源（apple-macos/Sources/FinanceOSMac/Views/Components.swift） |
 * |---|---|---|
 * | aurora.baseLight / baseDark | 0xF5F5F5 / 0x000000 | AuroraBackground L92 `.white 0.96` / D92 `Color.black` |
 * | aurora.blobs[].color | teal 0x30D5C8、blue 0x007AFF、purple 0xAF52DE、orange 0xFF9500 | L95-102 `.teal/.blue/.purple/.orange`（SwiftUI 标准色） |
 * | aurora.blobs[].alphaLight/Dark | 0.24/0.20 teal、0.30/0.28 blue、0.26/0.24 purple、0.18/0.16 orange | L95-102 `opacity(light/dark)` |
 * | aurora.blobs[].pos/size | teal .55/.95/.55、blue .18/.12/.70、purple .88/.30/.62、orange .10/.82/.45 | L95-102 `.position`/`size` 归一 |
 * | brand.gradientStops | teal→blue→purple 0x30D5C8/0x007AFF/0xAF52DE | 三端图标“青→蓝→紫”品牌渐变（apple-macos/Resources 图标） |
 * | accent.teal 亮/暗 | 0x006B5F / 0x5DDBCA | Android 现有 primary（FinanceOsTheme.kt）与 LanShare `.teal` 家族同色相 |
 * | radius.card | 22dp | GlassCard L119 `cornerRadius: 22` |
 * | radius.button | 14dp | M3 按钮圆角（无 mac 直源，取 Expressive 圆角按钮） |
 * | radius.sheet | 28dp | M3 Expressive 底部弹层大圆角（无 mac 直源） |
 * | radius.pill | 999dp | 胶囊（进度条等） |
 * | spacing.cardPadding | 18dp | GlassCard L120 `padding: 18` |
 * | spacing.page | 16dp | 各页内容边距 |
 * | motion.pageTransitionMs | 150 | 与现状导航过渡 120–180 区间一致 |
 * | motion.gradientAngleDeg | 135 | 线性辅助渐变方向；主氛围光斑按 blobs 布局，与 Aurora 一致 |
 *
 * 文本/表面色不在此定义：交给各端 ColorScheme 语义；光斑可叠加在浅（0.96 白）与深（纯黑）
 * 表面上，正文用语义色保证 WCAG AA（正文 4.5:1）。
 */
object FinanceOsDesignTokens {
    /** 一颗品牌光斑：颜色（ARGB Long）、浅/深模式 alpha、中心点与直径的容器占比。 */
    data class AuroraBlob(
        val colorHex: Long,
        val alphaLight: Float,
        val alphaDark: Float,
        val centerXFrac: Float,
        val centerYFrac: Float,
        val sizeFrac: Float,
    )

    object Aurora {
        val baseLightHex: Long = 0xFFF5F5F5
        val baseDarkHex: Long = 0xFF000000

        /** 顺序对应 AuroraBackground 的实现顺序；低开销光斑绘制用同一数值。 */
        val blobs: List<AuroraBlob> = listOf(
            AuroraBlob(0xFF007AFF, alphaLight = 0.30f, alphaDark = 0.28f, centerXFrac = 0.18f, centerYFrac = 0.12f, sizeFrac = 0.70f), // blue
            AuroraBlob(0xFFAF52DE, alphaLight = 0.26f, alphaDark = 0.24f, centerXFrac = 0.88f, centerYFrac = 0.30f, sizeFrac = 0.62f), // purple
            AuroraBlob(0xFF30D5C8, alphaLight = 0.24f, alphaDark = 0.20f, centerXFrac = 0.55f, centerYFrac = 0.95f, sizeFrac = 0.55f), // teal
            AuroraBlob(0xFFFF9500, alphaLight = 0.18f, alphaDark = 0.16f, centerXFrac = 0.10f, centerYFrac = 0.82f, sizeFrac = 0.45f), // orange
        )
    }

    object Brand {
        /** 图标品牌渐变 青→蓝→紫（十六进制 RGB，无 alpha）。 */
        val gradientStops: List<Long> = listOf(0xFF30D5C8, 0xFF007AFF, 0xFFAF52DE)

        /**
         * 主操作按钮渐变（青→蓝→紫深档）：白字对比需 ≥4.5:1，故用各色相 700/800 档；
         * 参考 Material Design tonal palette：teal700 0xFF00897B、blue800 0xFF1565C0、
         * purple800 0xFF6A1B9A（白字对比约 4.6–6.3:1）。
         */
        val actionGradientStops: List<Long> = listOf(0xFF00897B, 0xFF1565C0, 0xFF6A1B9A)

        /** 线性辅助渐变角度（度）：主氛围用 [Aurora.blobs] 光斑布局，此角度仅用于按钮等辅助渐变。 */
        const val gradientAngleDeg: Float = 135f
    }

    object Accent {
        /** Android 亮色主题主强调（teal 家族，与 LanShare .teal 同色相家族）。 */
        const val tealLightHex: Long = 0xFF006B5F

        /** Android 暗色主题主强调。 */
        const val tealDarkHex: Long = 0xFF5DDBCA
    }

    object Radius {
        const val card: Int = 22
        const val button: Int = 14
        const val sheet: Int = 28
        const val pill: Int = 999
    }

    object Spacing {
        const val cardPadding: Int = 18
        const val page: Int = 16
    }

    object Motion {
        /** 页面切换动画时长（毫秒）。 */
        const val pageTransitionMs: Int = 150

        /** 光斑等氛围元素的淡入时长（毫秒）。 */
        const val fadeInMs: Int = 120
    }
}
