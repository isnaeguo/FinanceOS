package com.financeos.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.financeos.shared.design.FinanceOsDesignTokens

/**
 * 品牌光斑背景，对应 macOS 的 [AuroraBackground](apple-macos Components.swift L87-115)。
 *
 * 实现为一次性 Canvas：浅/深底色 + 四颗径向渐变光斑（青/蓝/紫/橙），数值与 alpha 全部来自
 * [FinanceOsDesignTokens.Aurora]。无动画、无每帧重绘；作为根级背景垫在所有页面之下。
 */
@Composable
fun BrandBackdrop(
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    val base = if (darkTheme) {
        Color(FinanceOsDesignTokens.Aurora.baseDarkHex)
    } else {
        Color(FinanceOsDesignTokens.Aurora.baseLightHex)
    }
    val blobs = FinanceOsDesignTokens.Aurora.blobs
    Canvas(modifier = modifier) {
        drawRect(base)
        blobs.forEach { blob ->
            val color = Color(blob.colorHex)
                .copy(alpha = if (darkTheme) blob.alphaDark else blob.alphaLight)
            val center = Offset(
                x = size.width * blob.centerXFrac,
                y = size.height * blob.centerYFrac,
            )
            val radius = size.minDimension * blob.sizeFrac
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color, color.copy(alpha = 0f)),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }
}
