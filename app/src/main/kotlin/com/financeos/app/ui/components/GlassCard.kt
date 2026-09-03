package com.financeos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.financeos.shared.design.FinanceOsDesignTokens
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

/**
 * 导航根创建的 Haze 状态，供背景源与各玻璃表面共享同一实例。
 * 仅做视觉模糊；导航模型、文案与语义不受影响。
 */
val LocalGlassHaze = staticCompositionLocalOf<HazeState?> { null }

/** 若当前有 Haze 状态则应用玻璃模糊效果（API<31 由 Haze 自动降级为半透明）。 */
@Composable
internal fun Modifier.glassHaze(): Modifier {
    val state = LocalGlassHaze.current ?: return this
    return hazeEffect(state)
}

/**
 * 玻璃卡片（非模糊实现，对应 macOS GlassCard L118-129）。
 *
 * 结构要点：**底色必须透出背景光斑**，而非叠一层实心白/黑。做法是用 theme 的 surface 语义色
 * 降到很低的不透明度（暗 8% / 亮 16%）+ 顶部高光 + 1px 上亮下暗描边 + 大圆角（22dp）。
 * 内容不设背景（透明），由光斑经 [GlassCard] 透上来。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(FinanceOsDesignTokens.Radius.card.dp),
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable BoxScope.() -> Unit,
) {
    // 主题 surface 已是透出光斑的半透明层（见 FinanceOsTheme），这里直接使用，不再二次降 alpha。
    val baseTint = MaterialTheme.colorScheme.surface
    val highlight: Color = if (darkTheme) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.50f)
    val border = if (darkTheme) {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.04f)),
        )
    } else {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.90f), Color.White.copy(alpha = 0.40f)),
        )
    }
    val base = Brush.verticalGradient(
        listOf(baseTint, baseTint.copy(alpha = 0.55f)),
    )
    val elevation = if (darkTheme) 4.dp else 8.dp
    Box(
        modifier = modifier
            .shadow(elevation = elevation, shape = shape, spotColor = Color.Black.copy(alpha = 0.18f))
            .clip(shape)
            .background(base)
            .background(Brush.verticalGradient(listOf(highlight, Color.Transparent)))
            .border(width = 1.dp, brush = border, shape = shape),
    ) {
        content()
    }
}

/** 带统一内边距的玻璃卡片便捷形式；间距取 [FinanceOsDesignTokens.Spacing.cardPadding]。 */
@Composable
fun GlassCardPadded(
    modifier: Modifier = Modifier,
    padding: Dp = FinanceOsDesignTokens.Spacing.cardPadding.dp,
    shape: Shape = RoundedCornerShape(FinanceOsDesignTokens.Radius.card.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    GlassCard(modifier = modifier, shape = shape) {
        Box(modifier = Modifier.padding(padding)) {
            content()
        }
    }
}
