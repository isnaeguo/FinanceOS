package com.financeos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.TextFieldColors

/**
 * 玻璃家族的低层表面：把 Android 实体色块（图标圆底、输入框底色、列表底）统一为半透明，
 * 让品牌光斑能从玻璃卡后透出。
 */

/** 图标圆形底：半透明着色替代实体 secondaryContainer，颜色语义保留。 */
@Composable
fun GlassIconCircle(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // 主题的 secondaryContainer 已是透出背景的半透明层（见 FinanceOsTheme），直接使用不再降 alpha。
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = if (darkTheme) 0.55f else 0.40f)),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * M3 OutlinedTextField 的玻璃配色：field 底色半透明，让输入框不再是一块实体色板；
 * 文本/边框仍走语义色保证可读。
 */
@Composable
fun glassTextFieldColors(
    darkTheme: Boolean = isSystemInDarkTheme(),
): TextFieldColors {
    // 主题 surface 已是半透明（见 FinanceOsTheme），这里仅用作字段底色，不再二次降 alpha。
    val field = MaterialTheme.colorScheme.surface
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = field,
        unfocusedContainerColor = field,
        disabledContainerColor = field.copy(alpha = 0.6f),
        errorContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
    )
}

/**
 * 玻璃卡内在暗色模式下保证对比的文字色。
 *
 * 玻璃卡底在暗色下是半透明暗色，语义 on-色（尤其 onSecondaryContainer/onSurfaceVariant）
 * 若沿用主题默认会产生对比不足；这里按明暗返回高亮色：
 * 暗色 → 近白（onSurface），亮色 → 常规 onSurfaceVariant（主题已保证）。
 */
@Composable
fun glassCardSecondaryText(
    darkTheme: Boolean = isSystemInDarkTheme(),
): androidx.compose.ui.graphics.Color =
    // 暗色模式下玻璃卡内文字统一提亮为近白，保证与半透明暗底对比（“暗色自动变白”）。
    if (darkTheme) Color(0xFFF2F4F7) else MaterialTheme.colorScheme.onSurfaceVariant
