package com.split.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate

@Composable
fun NwcSymbolIcon(
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFFFFA81A),
    secondaryColor: Color = Color(0xFF8573FF),
    foregroundColor: Color = Color.Black
) {
    Canvas(modifier = modifier) {
        val symbolSize = minOf(size.width, size.height)
        val left = (size.width - symbolSize) / 2f
        val top = (size.height - symbolSize) / 2f
        val center = Offset(left + symbolSize / 2f, top + symbolSize / 2f)
        val corner = CornerRadius(symbolSize * 0.085f, symbolSize * 0.085f)

        drawCircle(
            color = Color.Black,
            radius = symbolSize / 2f,
            center = center
        )

        val secondarySize = Size(symbolSize * 0.34f, symbolSize * 0.58f)
        val secondaryCenter = Offset(
            center.x + symbolSize * 0.20f,
            center.y - symbolSize * 0.16f
        )
        rotate(degrees = -35f, pivot = secondaryCenter) {
            drawRoundRect(
                color = secondaryColor,
                topLeft = Offset(
                    secondaryCenter.x - secondarySize.width / 2f,
                    secondaryCenter.y - secondarySize.height / 2f
                ),
                size = secondarySize,
                cornerRadius = corner
            )
        }

        val accentSize = Size(symbolSize * 0.55f, symbolSize * 0.55f)
        val accentTopLeft = Offset(
            center.x - accentSize.width / 2f,
            center.y - accentSize.height / 2f
        )
        rotate(degrees = -45f, pivot = center) {
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(accentColor, Color(0xFFFF8C14)),
                    start = accentTopLeft,
                    end = Offset(
                        accentTopLeft.x + accentSize.width,
                        accentTopLeft.y + accentSize.height
                    )
                ),
                topLeft = accentTopLeft,
                size = accentSize,
                cornerRadius = corner
            )
        }

        val plugSize = Size(symbolSize * 0.39f, symbolSize * 0.38f)
        val plugCenter = Offset(
            center.x - symbolSize * 0.01f,
            center.y + symbolSize * 0.07f
        )
        val plugTopLeft = Offset(
            plugCenter.x - plugSize.width / 2f,
            plugCenter.y - plugSize.height / 2f
        )
        rotate(degrees = -45f, pivot = plugCenter) {
            drawPath(
                path = nwcPlugPath(
                    left = plugTopLeft.x,
                    top = plugTopLeft.y,
                    width = plugSize.width,
                    height = plugSize.height
                ),
                color = foregroundColor
            )
        }
    }
}

private fun nwcPlugPath(
    left: Float,
    top: Float,
    width: Float,
    height: Float
): Path {
    fun x(value: Float) = left + width * value
    fun y(value: Float) = top + height * value

    return Path().apply {
        moveTo(x(0.08f), y(0.36f))
        lineTo(x(0.24f), y(0.36f))
        lineTo(x(0.24f), y(0.18f))
        quadraticTo(x(0.275f), y(0.11f), x(0.31f), y(0.18f))
        lineTo(x(0.31f), y(0.36f))
        lineTo(x(0.42f), y(0.36f))
        lineTo(x(0.42f), y(0.11f))
        quadraticTo(x(0.46f), y(0.04f), x(0.50f), y(0.11f))
        lineTo(x(0.50f), y(0.36f))
        lineTo(x(0.62f), y(0.36f))
        lineTo(x(0.62f), y(0.18f))
        quadraticTo(x(0.66f), y(0.11f), x(0.70f), y(0.18f))
        lineTo(x(0.70f), y(0.38f))
        quadraticTo(x(0.84f), y(0.42f), x(0.90f), y(0.59f))
        lineTo(x(0.90f), y(0.72f))
        lineTo(x(0.72f), y(0.72f))
        quadraticTo(x(0.63f), y(0.92f), x(0.39f), y(0.92f))
        quadraticTo(x(0.18f), y(0.90f), x(0.12f), y(0.62f))
        lineTo(x(0.08f), y(0.62f))
        quadraticTo(x(-0.02f), y(0.49f), x(0.08f), y(0.36f))
        close()
    }
}
