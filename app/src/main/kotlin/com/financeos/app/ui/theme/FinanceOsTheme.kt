package com.financeos.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.financeos.shared.design.FinanceOsDesignTokens

/*
 * 玻璃关键表面依赖"表面色透出背景"。M3 默认 surface/container 是不透明的灰/白，会挡住品牌光斑。
 * 这里把容器语义色定义为**半透明**（基于黑/白 + 低 alpha），使 GlassCard、导航、输入框底透出背景。
 * 关键点：surface 被 GlassCard/GlassIconCircle/iOS 输入框底色引用，必须透出。
 */
private val FinanceOsLightColors = lightColorScheme(
    primary = Color(FinanceOsDesignTokens.Accent.tealLightHex),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF79F8E5),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8E0),
    onSecondaryContainer = Color(0xFF06201B),
    // 表面色透出背景：白底低透明，GlassCard 叠加在其上仍看得见光斑。
    surface = Color.White.copy(alpha = 0.80f),
    surfaceContainerLowest = Color.White.copy(alpha = 0.60f),
    surfaceContainerLow = Color.White.copy(alpha = 0.55f),
    surfaceContainer = Color.White.copy(alpha = 0.50f),
    surfaceContainerHigh = Color.White.copy(alpha = 0.55f),
    surfaceContainerHighest = Color.White.copy(alpha = 0.60f),
    surfaceVariant = Color.White.copy(alpha = 0.60f),
)

private val FinanceOsDarkColors = darkColorScheme(
    primary = Color(FinanceOsDesignTokens.Accent.tealDarkHex),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFF79F8E5),
    secondary = Color(0xFFB1CCC4),
    onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF334B46),
    onSecondaryContainer = Color(0xFFCDE8E0),
    // 暗色表面透出背景：近黑低透明，光斑从玻璃底上来，文字用 onSurface 高亮保证对比。
    surface = Color(0xFF11151A).copy(alpha = 0.72f),
    surfaceContainerLowest = Color(0xFF0A0E13).copy(alpha = 0.55f),
    surfaceContainerLow = Color(0xFF11151A).copy(alpha = 0.55f),
    surfaceContainer = Color(0xFF161B21).copy(alpha = 0.55f),
    surfaceContainerHigh = Color(0xFF1B2128).copy(alpha = 0.58f),
    surfaceContainerHighest = Color(0xFF20272F).copy(alpha = 0.60f),
    surfaceVariant = Color(0xFF22272E).copy(alpha = 0.60f),
)


/**
 * 暗色玻璃文字覆盖：玻璃卡底在暗色是低透明暗色，语义 on- 色（onSurfaceVariant/onSecondaryContainer/
 * onPrimaryContainer 等）若沿用主题会发灰/偏青，对比不足。这里统一覆盖为纯白——这是"暗色检测下字体
 * 变白"的单一来源。primary 等强调色保留。
 */
private val FinanceOsDarkGlassColors = FinanceOsDarkColors.copy(
    surface = FinanceOsDarkColors.surface,
    onSurface = Color(0xFFF2F4F7),
    onSurfaceVariant = Color(0xFFE2E5E9),
    onSecondaryContainer = Color(0xFFF2F4F7),
    onPrimaryContainer = Color(0xFFF4FBFA),
    onTertiaryContainer = Color(0xFFF2F4F7),
    onError = Color(0xFFF2F4F7),
)

/** 默认玻璃表面底色引用：透出光斑的半透明层。 */
val transparentSurface: Color = Color(0xA6FFFFFF)

/**
 * Material 3 形状：圆角档位由 [FinanceOsDesignTokens.Radius] 驱动。
 * card=22 对应 macOS GlassCard 圆角；sheet=28 为 M3 Expressive 底部弹层大圆角。
 */
private val FinanceOsShapes = Shapes(
    extraSmall = RoundedCornerShape(FinanceOsDesignTokens.Radius.button.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(FinanceOsDesignTokens.Radius.card.dp),
    extraLarge = RoundedCornerShape(FinanceOsDesignTokens.Radius.sheet.dp),
)

/** FinanceOS 的 Android Material 3 主题，支持系统深色模式和 Android 12 动态配色。 */
@Composable
fun FinanceOsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Android 12+ 动态配色会覆盖上述 surface 透明化。为保玻璃透光，仅用动态色方案的主色部分，
    // 容器语义色维持透明定义；因此这里只在 SDK<S 时用自定义轻/暗色，>=S 时动态方案。
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // 动态配色目前无法透出背景：为避免色块，改用自定义半透明 surface 方案（放弃动态 surface）。
        // 主色仍取动态色，圆角/容器透明化维持。
        val context = LocalContext.current
        val dynamic = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        if (darkTheme) {
            FinanceOsDarkGlassColors.copy(
                primary = dynamic.primary,
                onPrimary = dynamic.onPrimary,
                secondary = dynamic.secondary,
                onSecondary = dynamic.onSecondary,
                tertiary = dynamic.tertiary,
                onTertiary = dynamic.onTertiary,
            )
        } else {
            FinanceOsLightColors.copy(
                primary = dynamic.primary,
                onPrimary = dynamic.onPrimary,
                secondary = dynamic.secondary,
                onSecondary = dynamic.onSecondary,
                tertiary = dynamic.tertiary,
                onTertiary = dynamic.onTertiary,
            )
        }
    } else if (darkTheme) {
        FinanceOsDarkGlassColors
    } else {
        FinanceOsLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = FinanceOsShapes,
        content = content,
    )
}
