package app.mizune.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The video stopped partway most recently, floating over the foot of the library: its [frame],
 * [show] (null for one at the top or a film alone), [title], time [left] and [progress]. The bar
 * goes to it in its folder; its play button plays it from where it stopped.
 */
@Composable
fun ResumeBar(
    frame: ImageBitmap?,
    show: String?,
    title: String,
    left: String,
    progress: Float,
    onShow: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val playInteraction = remember { MutableInteractionSource() }
    val playHovered by playInteraction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.widthIn(max = 520.dp)
            .shadow(14.dp, shape)
            .clip(shape)
            .background(if (hovered && !playHovered) scheme.primary.copy(alpha = 0.08f).compositeOver(scheme.surfaceVariant) else scheme.surfaceVariant)
            .border(1.dp, scheme.outlineVariant, shape)
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onShow)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(scheme.background)) {
            frame?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x59000000))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(scheme.primary))
            }
        }
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                show?.let { "$it · $title" } ?: title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (hovered && !playHovered) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(left, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Box(
            Modifier.size(36.dp)
                .background(if (playHovered) scheme.primary.copy(alpha = 0.85f) else scheme.primary, CircleShape)
                .hoverable(playInteraction).clickable(playInteraction, indication = null, onClick = onPlay)
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics { contentDescription = "Play" },
            contentAlignment = Alignment.Center,
        ) {
            PlayIcon(scheme.onPrimary, diameter = 14.dp)
        }
    }
}
