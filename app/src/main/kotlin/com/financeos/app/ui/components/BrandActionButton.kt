package com.financeos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.financeos.shared.design.FinanceOsDesignTokens

/**
 * 主操作按钮的品牌渐变底（青→蓝→紫深档）。
 *
 * 三档取自 [FinanceOsDesignTokens.Brand.actionGradientStops]，均为 700/800 深档，
 * 白字对比 ≥4.5:1（对应 KDoc 对照表）。渐变仅用于“记一笔”等英雄操作。
 */
private fun brandActionBrush(): Brush = Brush.linearGradient(
    colors = FinanceOsDesignTokens.Brand.actionGradientStops.map { Color(it) },
    start = androidx.compose.ui.geometry.Offset.Zero,
    end = androidx.compose.ui.geometry.Offset.Infinite,
)

/** 圆形品牌渐变 FAB（沿用 56dp 与既有语义，仅换底）。 */
@Composable
fun BrandGradientFab(
    onClick: () -> Unit,
    contentDescription: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val gradientBrush = brandActionBrush()
    Box(
        modifier = modifier
            .size(56.dp)
            .shadow(elevation = 6.dp, shape = CircleShape)
            .clip(CircleShape)
            .background(gradientBrush)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
        )
    }
}

/** 品牌渐变扩展 FAB（文字白、带语义 contentDescription）。 */
@Composable
fun BrandGradientExtendedFab(
    onClick: () -> Unit,
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    val gradientBrush = brandActionBrush()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(FinanceOsDesignTokens.Radius.pill.dp))
            .background(gradientBrush)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = text }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White)
        Text(
            text = text,
            modifier = Modifier.padding(start = 8.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** 品牌渐变主按钮（全宽 pill，语义/禁用态与 Material Button 一致；文字由调用方置白）。 */
@Composable
fun BrandGradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val gradientBrush = brandActionBrush()
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.55f)
            .clip(RoundedCornerShape(FinanceOsDesignTokens.Radius.pill.dp))
            .background(gradientBrush)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (!enabled) disabled() },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            content()
        }
    }
}
