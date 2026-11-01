package app.mizune.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/*
 * What a library card's frame says about how far you've got, on the desktop and the phone alike:
 * a line along its foot, and a tick in the corner once it's all watched.
 */

/** Behind a line on a frame, so it reads over any picture. */
private val Track = Color(0x59000000)

/** Between one season's slices and the next, light enough to read over a dark frame or a picture. */
private val Divider = Color.White.copy(alpha = 0.6f)

/** A soft dark fade up from the foot of a frame, under a line, so it reads over a bright picture too. */
private val Shade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f)))

private val Rounded = RoundedCornerShape(2.dp)

/** The most slices a folder's line is cut into; past that, each slice is a few videos together. */
private const val MAX_SLICES = 24

/** How far into a video [progress] is, 0 to 1, as a line held in from the foot of its frame. */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) = Shaded(modifier) {
    Box(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 7.dp).height(4.dp).clip(Rounded).background(Track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(MaterialTheme.colorScheme.primary))
    }
}

/**
 * A folder's videos along the foot of its frame, a slice each in order, filled as far as each has
 * been watched (0 to 1). [groups] are its seasons, with a short upright line between one and the
 * next. A long show's videos share slices, so they stay apart. Level with a video's [ProgressLine].
 */
@Composable
fun ProgressSlices(groups: List<List<Float>>, modifier: Modifier = Modifier) {
    val total = groups.sumOf { it.size }
    if (total == 0) return
    val per = (total + MAX_SLICES - 1) / MAX_SLICES
    val sliced = groups.filter { it.isNotEmpty() }.map { group -> group.chunked(per) { it.average().toFloat() } }
    val primary = MaterialTheme.colorScheme.primary
    Shaded(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp).height(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sliced.forEachIndexed { i, slices ->
                if (i > 0) Box(Modifier.padding(horizontal = 4.dp).width(1.5.dp).fillMaxHeight().clip(Rounded).background(Divider))
                Row(Modifier.weight(slices.size.toFloat()).height(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (share in slices) {
                        Box(Modifier.weight(1f).fillMaxHeight().clip(Rounded).background(Track)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(share.coerceIn(0f, 1f)).background(primary))
                        }
                    }
                }
            }
        }
    }
}

/** [Shade] behind a line along the foot of a frame. */
@Composable
private fun Shaded(modifier: Modifier, line: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().height(24.dp).background(Shade), contentAlignment = Alignment.BottomStart) { line() }
}

/** A tick in a disc of the accent, in a frame's corner: watched, or a folder watched all through. */
@Composable
fun WatchedMark(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Canvas(modifier.size(20.dp)) {
        drawCircle(scheme.primary)
        val tick = Path().apply {
            moveTo(size.width * 0.29f, size.height * 0.52f)
            lineTo(size.width * 0.44f, size.height * 0.66f)
            lineTo(size.width * 0.71f, size.height * 0.37f)
        }
        drawPath(tick, scheme.onPrimary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
