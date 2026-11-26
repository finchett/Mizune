package app.mizune.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.mizune.library.formatTime

/** How wide the picture over the seek bar is. */
private val FRAME_WIDTH = 192.dp
/** Room around it for its shadow, inside the popup. */
private val SHADOW_ROOM = 16.dp
/** How far it keeps from the bar's ends, and above the bar. */
private val EDGE = 6.dp
private val ABOVE = 4.dp
/** How far it rises as it fades in. */
private val RISE = 4.dp

/**
 * Over the bar the popup is placed in (from the bar's left end to its right): the picture from
 * [at] seconds into the video and the time, centred on [x] (from the bar's left, in pixels) and
 * kept inside the bar's ends. [at] null hides it. Until a picture is made, the time shows alone;
 * while the next is made, the last stays.
 */
@Composable
internal fun SeekBarPreview(previews: SeekPreviews, at: Double?, duration: Double, x: Float) {
    val shown = at != null && duration > 0
    // the last point stays for the fade out
    var last by remember { mutableStateOf(at ?: 0.0) }
    var lastX by remember { mutableStateOf(x) }
    if (shown) {
        last = at
        lastX = x
    }
    val visible by animateFloatAsState(if (shown) 1f else 0f, tween(140), label = "seek preview")
    if (!shown && visible == 0f) return

    val density = LocalDensity.current
    val width = with(density) { FRAME_WIDTH.roundToPx() }
    val step = previews.stepOf(last, duration)
    if (shown) LaunchedEffect(step) { previews.request(step, duration, width) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    previews.made // read, so a picture made comes in
    previews.cached(step)?.let { if (frame !== it) frame = it }

    val room = with(density) { SHADOW_ROOM.roundToPx() }
    val edge = with(density) { EDGE.roundToPx() }
    val above = with(density) { ABOVE.roundToPx() }
    val positions = remember(lastX, room, edge, above) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val seen = popupContentSize.width - 2 * room
                val left = (anchorBounds.left + lastX - seen / 2f).toInt()
                    .coerceAtMost(anchorBounds.right - edge - seen)
                    .coerceAtLeast(anchorBounds.left + edge)
                return IntOffset(left - room, anchorBounds.top - popupContentSize.height + room - above)
            }
        }
    }
    Popup(popupPositionProvider = positions, properties = PopupProperties(focusable = false)) {
        Column(
            Modifier.padding(SHADOW_ROOM).graphicsLayer {
                alpha = visible
                translationY = (1 - visible) * RISE.toPx()
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            frame?.let { image ->
                val shape = RoundedCornerShape(8.dp)
                Image(
                    image,
                    contentDescription = null,
                    modifier = Modifier.width(FRAME_WIDTH)
                        .aspectRatio(image.width.toFloat() / image.height)
                        .shadow(10.dp, shape)
                        .clip(shape)
                        .border(1.dp, Color.White.copy(alpha = 0.2f), shape),
                )
            }
            // as the bar's buttons' hints are
            Text(
                formatTime(last),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
