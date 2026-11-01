package app.mizune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The parts the player's menus are built from, on the desktop and the phone alike: choices in
 * groups, each group under a [SectionLabel], the way the library groups its cards.
 */

/** How tall a menu's rows are: compact for a pointer, taller on the phone for a finger. */
val LocalMenuRowHeight = compositionLocalOf { 32.dp }

/** A quiet label over a group: small capitals, spaced out, with an optional note at the far end. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, note: String? = null) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(
            text.uppercase(),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.Medium,
            color = muted,
            modifier = Modifier.weight(1f),
        )
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted.copy(alpha = 0.8f)) }
    }
}

/** A group's label, with an optional [note] at the far end (a key, or how it's used). */
@Composable
fun MenuGroup(label: String, note: String? = null, first: Boolean = false) =
    SectionLabel(label, Modifier.padding(start = 10.dp, end = 10.dp, top = if (first) 6.dp else 14.dp, bottom = 6.dp), note)

/** A line of muted text under a group's label, saying why its choices don't apply. */
@Composable
fun MenuNote(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
)

/**
 * One choice of a group: its [title], what else tells it apart ([detail], muted at the end, never
 * cut short), and a tick in the accent when it's the one [selected].
 */
@Composable
fun MenuChoice(title: String, selected: Boolean, detail: String? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    MenuRow(selected, onClick) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1) }
        Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
            if (selected) Text("✓", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        }
    }
}

/** A row that shows or hides more choices below it, in place, so the menu stays up. */
@Composable
fun MenuDisclosure(title: String, open: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    MenuRow(selected = false, onClick = onClick) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(if (open) "▾" else "▸", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
    }
}

/** Two or three choices side by side, as one control, for a setting that's one of a few. */
@Composable
fun MenuSegments(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier.padding(horizontal = 4.dp),
    onSelect: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().height(LocalMenuRowHeight.current)
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.onSurface.copy(alpha = 0.06f))
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            on -> scheme.primary.copy(alpha = 0.18f)
                            hovered -> scheme.onSurface.copy(alpha = 0.06f)
                            else -> Color.Transparent
                        },
                    )
                    .hoverable(interaction)
                    .clickable(interaction, indication = null) { onSelect(i) }
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MenuRow(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(LocalMenuRowHeight.current)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> scheme.primary.copy(alpha = 0.12f)
                    hovered -> scheme.onSurface.copy(alpha = 0.06f)
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
