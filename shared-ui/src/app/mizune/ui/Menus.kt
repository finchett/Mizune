package app.mizune.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The parts the player's menus are built from, on the desktop and the phone alike: choices in
 * groups, each group under a [SectionLabel], the way the library groups its cards.
 */

/**
 * A menu that drops from what it's in: a compact card for these parts, the same on the desktop and
 * the phone, but for its [shadow] and [color], which the desktop sets for itself.
 */
@Composable
fun MenuCard(
    expanded: Boolean,
    onDismiss: () -> Unit,
    width: Dp = 320.dp,
    shadow: Dp = 10.dp,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).padding(horizontal = 6.dp),
        shape = RoundedCornerShape(12.dp),
        containerColor = color,
        tonalElevation = 0.dp,
        shadowElevation = shadow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
        content = content,
    )
}

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
 * cut short), and a tick in the accent when it's the one [selected]. An [icon] goes in front,
 * drawn in the colour it's given (see MenuIcons.kt).
 */
@Composable
fun MenuChoice(
    title: String,
    selected: Boolean,
    detail: String? = null,
    icon: (@Composable (Color) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    MenuRow(selected, onClick) {
        icon?.invoke(scheme.onSurfaceVariant)
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

/** A setting that's on or off, for [MenuToggles]: an icon (drawn in the colour it's given) and a word. */
class MenuToggle(val title: String, val on: Boolean, val icon: @Composable (Color) -> Unit, val onChange: (Boolean) -> Unit)

/** Settings that are on or off, side by side on one line: each an icon and a word, lit while on. */
@Composable
fun MenuToggles(toggles: List<MenuToggle>, modifier: Modifier = Modifier.padding(horizontal = 4.dp)) {
    val scheme = MaterialTheme.colorScheme
    val ease = tween<Color>(150, easing = FastOutSlowInEasing)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        toggles.forEach { toggle ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val background by animateColorAsState(
                when {
                    toggle.on -> scheme.primary.copy(alpha = 0.18f)
                    hovered -> scheme.onSurface.copy(alpha = 0.10f)
                    else -> scheme.onSurface.copy(alpha = 0.06f)
                },
                ease,
            )
            val text by animateColorAsState(if (toggle.on) scheme.onSurface else scheme.onSurfaceVariant, ease)
            val icon by animateColorAsState(if (toggle.on) scheme.primary else scheme.onSurfaceVariant, ease)
            Row(
                Modifier.weight(1f).height(LocalMenuRowHeight.current)
                    .clip(RoundedCornerShape(8.dp))
                    .background(background)
                    .hoverable(interaction)
                    .toggleable(toggle.on, interaction, indication = null, role = Role.Switch, onValueChange = toggle.onChange)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                toggle.icon(icon)
                Text(toggle.title, style = MaterialTheme.typography.labelLarge, color = text, maxLines = 1)
            }
        }
    }
}

/**
 * The rest of a group's choices, folded away under [title] until it's opened (see [MenuFoldBody]).
 * At its end, how many there are, or [detail]; its chevron turns down as it opens.
 */
@Composable
fun MenuFold(title: String, count: Int, open: Boolean, detail: String? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(150, easing = FastOutSlowInEasing))
    MenuRow(selected = false, onClick = onClick, modifier = Modifier.semantics { stateDescription = if (open) "Expanded" else "Collapsed" }) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(detail ?: count.toString(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
        Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
            Text("›", fontSize = 15.sp, color = scheme.onSurfaceVariant, modifier = Modifier.rotate(turn))
        }
    }
}

/**
 * What a [MenuFold] folds away: it slides open under it, and closed, its rows set in a little.
 * Opened near the menu's foot, it's scrolled into sight as it grows.
 */
@Composable
fun ColumnScope.MenuFoldBody(open: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val sight = remember { BringIntoViewRequester() }
    AnimatedVisibility(
        open,
        modifier = Modifier.bringIntoViewRequester(sight),
        enter = expandVertically(tween(220, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
            fadeIn(tween(220, delayMillis = 40, easing = FastOutSlowInEasing)),
        exit = shrinkVertically(tween(220, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) +
            fadeOut(tween(160, easing = FastOutSlowInEasing)),
    ) {
        CompositionLocalProvider(LocalMenuIndent provides 12.dp) { Column(content = content) }
    }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        // it can only scroll as far as the menu has grown, so it follows it down as it grows
        val until = System.nanoTime() + 280_000_000L
        while (System.nanoTime() < until) {
            withFrameNanos { }
            sight.bringIntoView()
        }
    }
}

/** How far a menu's rows are set in: further inside a [MenuFoldBody]. */
private val LocalMenuIndent = compositionLocalOf { 0.dp }

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
private fun MenuRow(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier.fillMaxWidth().height(LocalMenuRowHeight.current)
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
            .padding(start = 10.dp + LocalMenuIndent.current, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
