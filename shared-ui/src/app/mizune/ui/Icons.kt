package app.mizune.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * A cog: one outline round eight square-shouldered teeth, and a hole in the middle, drawn rather
 * than shipped as an icon font. Its teeth stand out from one body so it reads as a gear, not a sun.
 */
@Composable
fun Cog(color: Color, diameter: Dp = 14.dp) {
    Canvas(Modifier.size(diameter)) {
        val r = size.minDimension / 2
        val stroke = Stroke(width = r * 0.17f, join = StrokeJoin.Round)
        // half a stroke inside the edge, so the teeth aren't clipped
        val outer = r * 0.9f
        val body = r * 0.68f
        val teeth = 8
        val step = 360f / teeth
        val gear = Path()
        fun at(radius: Float, degrees: Float) = Offset(
            center.x + radius * cos(Math.toRadians(degrees.toDouble())).toFloat(),
            center.y + radius * sin(Math.toRadians(degrees.toDouble())).toFloat(),
        )
        repeat(teeth) { i ->
            val mid = i * step - 90f
            // each tooth narrows a little towards its tip, then the body runs on to the next
            val points = listOf(at(body, mid - 12f), at(outer, mid - 8f), at(outer, mid + 8f), at(body, mid + 12f))
            if (i == 0) gear.moveTo(points[0].x, points[0].y) else gear.lineTo(points[0].x, points[0].y)
            points.drop(1).forEach { gear.lineTo(it.x, it.y) }
            gear.arcTo(Rect(center, body), mid + 12f, step - 24f, forceMoveTo = false)
        }
        gear.close()
        drawPath(gear, color, style = stroke)
        drawCircle(color, radius = r * 0.27f, style = stroke)
    }
}

/** A cross, as heavy as [Cog]'s outline, for closing. */
@Composable
fun CloseIcon(color: Color, diameter: Dp = 14.dp) {
    Canvas(Modifier.size(diameter)) {
        val stroke = size.minDimension * 0.12f
        val inset = stroke / 2
        drawLine(color, Offset(inset, inset), Offset(size.width - inset, size.height - inset), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width - inset, inset), Offset(inset, size.height - inset), stroke, StrokeCap.Round)
    }
}

/** A folder without a video to show: a plain folder outline in the empty frame. */
@Composable
fun FolderMark(color: Color) {
    Canvas(Modifier.size(44.dp, 34.dp)) {
        val stroke = Stroke(width = 1.6.dp.toPx())
        val tab = Path().apply {
            moveTo(0f, size.height * 0.22f)
            lineTo(0f, size.height * 0.08f)
            quadraticTo(0f, 0f, size.width * 0.06f, 0f)
            lineTo(size.width * 0.34f, 0f)
            lineTo(size.width * 0.44f, size.height * 0.14f)
        }
        drawPath(tab, color, style = stroke)
        drawRoundRect(
            color,
            topLeft = Offset(0f, size.height * 0.14f),
            size = Size(size.width, size.height * 0.86f),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = stroke,
        )
    }
}
