package com.financeos.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.financeos.shared.design.FinanceOsDesignTokens

private val FinanceOsDarkColors = darkColorScheme(
    primary = Color(0xFFFAFAFA),
    onPrimary = Color(0xFF121214),
    primaryContainer = Color(0xFF27272B),
    onPrimaryContainer = Color(0xFFF8F8FA),
    secondary = Color(0xFFC5C5CC),
    onSecondary = Color(0xFF17171A),
    secondaryContainer = Color(0xFF26262B),
    onSecondaryContainer = Color(0xFFE6E6EB),
    tertiary = Color(0xFFBBC9D3),
    onTertiary = Color(0xFF171B1F),
    background = Color(0xFF0A0A0C),
    onBackground = Color(0xFFF7F7FA),
    surface = Color(0xFF131316),
    onSurface = Color(0xFFF7F7FA),
    onSurfaceVariant = Color(0xFFB6B6C0),
    surfaceVariant = Color(0xFF28282E),
    surfaceContainerLowest = Color(0xFF101012),
    surfaceContainerLow = Color(0xFF17171B),
    surfaceContainer = Color(0xFF1B1B20),
    surfaceContainerHigh = Color(0xFF222228),
    surfaceContainerHighest = Color(0xFF29292F),
    outline = Color(0xFF85858E),
    outlineVariant = Color(0xFF34343C),
    error = Color(0xFFFFB6AD),
    onError = Color(0xFF421710),
    errorContainer = Color(0xFF442522),
    onErrorContainer = Color(0xFFFFDAD4),
)

private val FinanceOsLightColors = lightColorScheme(
    primary = Color(0xFF19191D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8ED),
    onPrimaryContainer = Color(0xFF17171B),
    secondary = Color(0xFF555560),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDF1),
    onSecondaryContainer = Color(0xFF303039),
    tertiary = Color(0xFF455B69),
    onTertiary = Color.White,
    background = Color(0xFFF7F7F9),
    onBackground = Color(0xFF16161B),
    surface = Color.White,
    onSurface = Color(0xFF16161B),
    onSurfaceVariant = Color(0xFF52525C),
    surfaceVariant = Color(0xFFE8E8EE),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F4F7),
    surfaceContainer = Color(0xFFEFEFF3),
    surfaceContainerHigh = Color(0xFFE8E8EE),
    surfaceContainerHighest = Color(0xFFE1E1E8),
    outline = Color(0xFF767681),
    outlineVariant = Color(0xFFDADAE2),
    error = Color(0xFF9C2D24),
    onError = Color.White,
    errorContainer = Color(0xFFFFE2DE),
    onErrorContainer = Color(0xFF5B1913),
)

private val FinanceOsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(FinanceOsDesignTokens.Radius.button.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(FinanceOsDesignTokens.Radius.sheet.dp),
)

@Composable
fun FinanceOsTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) FinanceOsDarkColors else FinanceOsLightColors,
        shapes = FinanceOsShapes,
        content = content,
    )
}
