package com.financeos.app.design

import com.financeos.shared.design.FinanceOsDesignTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 设计 token 健全性断言（纯逻辑，不依赖 Compose/平台）。 */
class FinanceOsDesignTokensTest {
    @Test
    fun auroraHasAtLeastThreeDistinctBlobs() {
        val blobs = FinanceOsDesignTokens.Aurora.blobs
        assertTrue(blobs.size >= 3)
        val colors = blobs.map { it.colorHex }.toSet()
        assertTrue(colors.size == blobs.size, "光斑颜色必须互异：$colors")
        blobs.forEach { blob ->
            assertTrue(blob.centerXFrac in 0f..1f)
            assertTrue(blob.centerYFrac in 0f..1f)
            assertTrue(blob.sizeFrac in 0f..1f)
            assertTrue(blob.alphaLight in 0f..1f)
            assertTrue(blob.alphaDark in 0f..1f)
            assertTrue(blob.colorHex in 0..0xFFFFFFFFL)
        }
    }

    @Test
    fun brandGradientStopsAreLegalAndDistinct() {
        val stops = FinanceOsDesignTokens.Brand.gradientStops
        assertTrue(stops.size >= 3)
        assertTrue(stops.toSet().size == stops.size)
        stops.forEach { assertTrue(it in 0..0xFFFFFFFFL) }
        assertTrue(FinanceOsDesignTokens.Brand.gradientAngleDeg in 0f..360f)
    }

    @Test
    fun radiusSpacingAndMotionArePositive() {
        assertTrue(FinanceOsDesignTokens.Radius.card > 0)
        assertTrue(FinanceOsDesignTokens.Radius.button > 0)
        assertTrue(FinanceOsDesignTokens.Radius.sheet > 0)
        assertTrue(FinanceOsDesignTokens.Radius.pill > 0)
        assertTrue(FinanceOsDesignTokens.Spacing.cardPadding > 0)
        assertTrue(FinanceOsDesignTokens.Spacing.page > 0)
        assertTrue(FinanceOsDesignTokens.Motion.pageTransitionMs in 120..180)
        assertTrue(FinanceOsDesignTokens.Motion.fadeInMs > 0)
    }

    @Test
    fun macAlignmentTableIsIntact() {
        // 与 macOS GlassCard 源数值逐项一致的回归护栏。
        assertEquals(22, FinanceOsDesignTokens.Radius.card)
        assertEquals(18, FinanceOsDesignTokens.Spacing.cardPadding)
    }
}
