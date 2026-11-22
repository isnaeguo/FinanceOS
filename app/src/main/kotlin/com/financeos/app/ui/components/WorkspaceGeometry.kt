package com.financeos.app.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

internal enum class WorkspaceSymbol { INBOX, ACCOUNTS, FORECAST, BOOKS, CHAT }

@Composable
internal fun GeometryLoader(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    val animated = if (ValueAnimator.areAnimatorsEnabled()) {
        rememberInfiniteTransition(label = "workspace-loading").animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(960, easing = LinearEasing)),
            label = "sequence",
        )
    } else {
        null
    }
    Box(
        modifier
            .size(width = 46.dp, height = 22.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
            .drawWithCache {
                val marks = List(3) { index ->
                    val origin = index * size.width * 0.28f
                    Path().apply {
                        moveTo(origin + size.width * 0.11f, size.height * 0.2f)
                        lineTo(origin + size.width * 0.28f, size.height * 0.2f)
                        lineTo(origin + size.width * 0.18f, size.height * 0.8f)
                        lineTo(origin + size.width * 0.01f, size.height * 0.8f)
                        close()
                    }
                }
                onDrawBehind {
                    val phase = animated?.value
                    marks.forEachIndexed { index, mark ->
                        val alpha = if (phase == null) (if (index == 1) 1f else 0.45f) else {
                            val wave = sin((phase - index * 0.23f) * 2.0 * PI).toFloat().coerceAtLeast(0f)
                            0.27f + 0.73f * wave
                        }
                        drawPath(mark, color, alpha = alpha)
                    }
                }
            },
    )
}

@Composable
internal fun WorkspaceGlyph(symbol: WorkspaceSymbol, modifier: Modifier = Modifier) {
    val tint = LocalContentColor.current
    Box(modifier.size(18.dp).drawWithCache {
        val unit = size.minDimension / 24f
        val left = (size.width - 24f * unit) / 2f
        val top = (size.height - 24f * unit) / 2f
        val stroke = Stroke(width = 1.65f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path()
        fun line(vararg coordinates: Int, closed: Boolean = false) {
            path.moveTo(left + coordinates[0] * unit, top + coordinates[1] * unit)
            for (index in 2 until coordinates.size step 2) {
                path.lineTo(left + coordinates[index] * unit, top + coordinates[index + 1] * unit)
            }
            if (closed) path.close()
        }
        when (symbol) {
            WorkspaceSymbol.INBOX -> {
                line(3, 10, 7, 4, 17, 4, 21, 10, 21, 20, 3, 20, closed = true)
                line(3, 11, 8, 11, 10, 15, 14, 15, 16, 11, 21, 11)
            }
            WorkspaceSymbol.ACCOUNTS -> {
                line(12, 3, 21, 8, 12, 13, 3, 8, closed = true)
                line(3, 13, 12, 18, 21, 13)
                line(3, 17, 12, 22, 21, 17)
            }
            WorkspaceSymbol.FORECAST -> {
                line(3, 19, 21, 19)
                line(3, 14, 8, 9, 12, 12, 20, 4)
                line(15, 4, 20, 4, 20, 9)
            }
            WorkspaceSymbol.BOOKS -> {
                line(12, 3, 21, 8, 21, 16, 12, 21, 3, 16, 3, 8, closed = true)
                line(3, 8, 12, 13, 21, 8)
                line(12, 13, 12, 21)
            }
            WorkspaceSymbol.CHAT -> {
                line(12, 3, 21, 8, 21, 15, 12, 20, 8, 22, 8, 18, 3, 15, 3, 8, closed = true)
                line(8, 12, 16, 12)
            }
        }
        onDrawBehind { drawPath(path, tint, style = stroke) }
    })
}
