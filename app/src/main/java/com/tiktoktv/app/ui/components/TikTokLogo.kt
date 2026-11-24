package com.tiktoktv.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tiktoktv.app.ui.theme.TikTokColors

@Composable
fun TikTokNoteIcon(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeWidth = w * 0.145f

        fun drawNote(offsetX: Float, offsetY: Float, color: Color) {
            val stemX = w * 0.54f + offsetX
            val topY = h * 0.16f + offsetY
            val circleCenterX = w * 0.36f + offsetX
            val circleCenterY = h * 0.68f + offsetY
            val radius = w * 0.18f

            val path = Path().apply {
                moveTo(stemX, topY)
                lineTo(stemX, circleCenterY)
                moveTo(stemX, topY)
                quadraticTo(
                    w * 0.62f + offsetX,
                    h * 0.34f + offsetY,
                    w * 0.80f + offsetX,
                    h * 0.35f + offsetY
                )
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            drawArc(
                color = color,
                startAngle = -55f,
                sweepAngle = 295f,
                useCenter = false,
                topLeft = Offset(circleCenterX - radius, circleCenterY - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        val shift = w * 0.045f
        drawNote(-shift, -shift * 0.6f, TikTokColors.Cyan)
        drawNote(shift, shift * 0.6f, TikTokColors.Pink)
        drawNote(0f, 0f, TikTokColors.White)
    }
}

@Composable
fun TikTokLogoWordmark(
    modifier: Modifier = Modifier,
    iconSize: Dp = 56.dp,
    fontSize: TextUnit = 38.sp
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TikTokNoteIcon(size = iconSize)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "TikTok",
            color = TikTokColors.White,
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp
        )
    }
}
