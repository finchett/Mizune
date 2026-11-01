package app.mizune.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/*
 * The small icons in front of a library menu's choices: outlines all of one weight, drawn rather
 * than shipped, the size of a line of the menu's text.
 */

/** What a menu's icons are drawn with: one weight of outline, with round ends. */
private fun DrawScope.outline() = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

@Composable
private fun MenuIcon(draw: DrawScope.(Stroke) -> Unit) = Canvas(Modifier.size(16.dp)) { draw(outline()) }

/** Back to the start: a bar, and a triangle pointing at it. */
@Composable
fun StartIcon(color: Color) = MenuIcon { stroke ->
    val w = size.width
    drawLine(color, Offset(w * 0.22f, w * 0.22f), Offset(w * 0.22f, w * 0.78f), stroke.width, StrokeCap.Round)
    val triangle = Path().apply {
        moveTo(w * 0.42f, w * 0.5f)
        lineTo(w * 0.8f, w * 0.24f)
        lineTo(w * 0.8f, w * 0.76f)
        close()
    }
    drawPath(triangle, color, style = stroke)
}

/** Watched: a tick. */
@Composable
fun TickIcon(color: Color) = MenuIcon { stroke ->
    val w = size.width
    val tick = Path().apply {
        moveTo(w * 0.2f, w * 0.52f)
        lineTo(w * 0.42f, w * 0.72f)
        lineTo(w * 0.8f, w * 0.3f)
    }
    drawPath(tick, color, style = stroke)
}

/** Back to unwatched: an arrow most of the way round anticlockwise, its head at the top. */
@Composable
fun ResetIcon(color: Color) = MenuIcon { stroke ->
    val w = size.width
    val inset = w * 0.2f
    // from twelve o'clock clockwise round to ten, leaving a gap at the top left
    drawArc(color, -90f, 300f, useCenter = false, topLeft = Offset(inset, inset), size = Size(w - inset * 2, w - inset * 2), style = stroke)
    // the head at twelve, pointing left, the way the arrow goes
    val head = Path().apply {
        moveTo(w * 0.6f, inset - w * 0.13f)
        lineTo(w * 0.46f, inset)
        lineTo(w * 0.6f, inset + w * 0.13f)
    }
    drawPath(head, color, style = stroke)
}

/** A folder: a tab on a rounded box. */
@Composable
fun GoToFolderIcon(color: Color) = MenuIcon { stroke ->
    val w = size.width
    val tab = Path().apply {
        moveTo(w * 0.12f, w * 0.34f)
        lineTo(w * 0.12f, w * 0.26f)
        lineTo(w * 0.4f, w * 0.26f)
        lineTo(w * 0.48f, w * 0.34f)
    }
    drawPath(tab, color, style = stroke)
    drawRoundRect(color, Offset(w * 0.12f, w * 0.34f), Size(w * 0.76f, w * 0.44f), CornerRadius(w * 0.08f), style = stroke)
}

/** Taken off a list: a cross. */
@Composable
fun RemoveIcon(color: Color) = MenuIcon { stroke ->
    val w = size.width
    drawLine(color, Offset(w * 0.28f, w * 0.28f), Offset(w * 0.72f, w * 0.72f), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(w * 0.72f, w * 0.28f), Offset(w * 0.28f, w * 0.72f), stroke.width, StrokeCap.Round)
}

/** Found where it is: a magnifying glass. */
@Composable
fun RevealIcon(color: Color) = MenuIcon { stroke ->
    val w = size.width
    drawCircle(color, radius = w * 0.25f, center = Offset(w * 0.43f, w * 0.43f), style = stroke)
    drawLine(color, Offset(w * 0.62f, w * 0.62f), Offset(w * 0.82f, w * 0.82f), stroke.width, StrokeCap.Round)
}

/** Copied: two sheets, one over the other. */
@Composable
fun CopyIcon(color: Color) = MenuIcon { stroke ->
    val w = size.width
    val corner = CornerRadius(w * 0.08f)
    drawRoundRect(color, Offset(w * 0.34f, w * 0.34f), Size(w * 0.48f, w * 0.48f), corner, style = stroke)
    // the sheet behind shows only where the front one doesn't cover it
    val back = Path().apply {
        moveTo(w * 0.22f, w * 0.66f)
        lineTo(w * 0.22f, w * 0.26f)
        quadraticTo(w * 0.22f, w * 0.18f, w * 0.3f, w * 0.18f)
        lineTo(w * 0.66f, w * 0.18f)
    }
    drawPath(back, color, style = stroke)
}
