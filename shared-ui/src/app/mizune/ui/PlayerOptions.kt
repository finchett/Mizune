package app.mizune.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mizune.media.AudioTrack
import app.mizune.subs.SubtitleTrack
import app.mizune.subs.detail
import app.mizune.subs.shortName
import kotlinx.coroutines.delay

/**
 * The player's options on the phone, in the desktop menu's groups. Each track choice is one field
 * showing what's chosen; a tap opens its choices inside the field, and they stay open until the
 * field is tapped again or another is opened. Choosing never closes anything. An open list
 * scrolls inside its field rather than making the card much taller. Side by side, where the card
 * has no height to spare, an open field is a page of its own, the size of the card.
 */
@Composable
fun PlayerOptionsCard(
    fill: Boolean,
    onFill: (Boolean) -> Unit,
    audioTracks: List<AudioTrack>,
    audioTrack: Int?,
    onAudio: (AudioTrack) -> Unit,
    tracks: List<SubtitleTrack>,
    primary: SubtitleTrack?,
    secondary: SubtitleTrack?,
    onPrimary: (SubtitleTrack) -> Unit,
    onSecondary: (SubtitleTrack?) -> Unit,
    offset: Double,
    onOffset: (Double) -> Unit,
    autoPause: Boolean,
    onAutoPause: (Boolean) -> Unit,
    landscapeLock: Boolean,
    onLandscapeLock: (Boolean) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // one field open at a time, so the card stays short
    var open by remember { mutableStateOf<String?>(null) }
    fun toggle(group: String) {
        open = if (open == group) null else group
    }
    Column(modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Options", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Done") }
        }
        BoxWithConstraints(Modifier.padding(end = 8.dp)) {
            // a phone on its side is short: the groups go side by side, closer together
            val wide = maxWidth >= TWO_COLUMNS
            val gap = if (wide) 12.dp else 18.dp
            val rowHeight = if (wide) 46.dp else 52.dp

            val audioChoices = audioTracks.map { track ->
                Choice(track.name, track.detail, track.id == audioTrack) { onAudio(track) }
            }
            val studyChoices = tracks.map { track ->
                Choice(track.shortName, track.detail, track === primary) { onPrimary(track) }
            }
            val translationChoices = listOf(Choice("None", null, secondary == null) { onSecondary(null) }) +
                tracks.filter { it !== primary }.map { track ->
                    Choice(track.shortName, track.detail, track === secondary) { onSecondary(track) }
                }

            @Composable
            fun picture(first: Boolean) {
                Label("Picture", if (first) 4.dp else gap)
                CompositionLocalProvider(LocalMenuRowHeight provides 44.dp) {
                    MenuSegments(listOf("Whole picture", "Fill the area"), if (fill) 1 else 0, Modifier) { onFill(it == 1) }
                }
            }

            @Composable
            fun audio() {
                if (audioTracks.isEmpty()) return
                Label("Audio", gap)
                ChoiceField(open == "audio" && !wide, { toggle("audio") }, audioChoices)
            }

            @Composable
            fun subtitles(first: Boolean) {
                Label("Subtitles to study", if (first) 4.dp else gap)
                if (tracks.isEmpty()) {
                    Field {
                        Text(
                            "No text subtitle tracks in this video.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                        )
                    }
                    return
                }
                ChoiceField(open == "study" && !wide, { toggle("study") }, studyChoices)
                Label("Translation", gap, note = "Hold to peek")
                ChoiceField(open == "translation" && !wide, { toggle("translation") }, translationChoices)
            }

            @Composable
            fun timing() {
                Label("Timing", gap)
                TimingField(offset, onOffset)
            }

            @Composable
            fun watching() {
                Label("While watching", gap)
                Field {
                    SwitchRow("Stop at each line", autoPause, onAutoPause, rowHeight)
                    HorizontalDivider(Modifier.padding(start = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchRow("Lock landscape", landscapeLock, onLandscapeLock, rowHeight)
                }
            }

            if (wide) {
                // the page takes the options' place at their height, so the card doesn't move
                var height by remember { mutableStateOf(0) }
                Crossfade(open) { shown ->
                    val page = when (shown) {
                        "audio" -> "Audio" to audioChoices
                        "study" -> "Subtitles to study" to studyChoices
                        "translation" -> "Translation" to translationChoices
                        else -> null
                    }
                    if (page != null) {
                        ChoicePage(page.first, page.second, { open = null }, with(LocalDensity.current) { height.toDp() })
                    } else {
                        Row(Modifier.onSizeChanged { height = it.height }, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Column(Modifier.weight(1f)) {
                                picture(first = true)
                                audio()
                                timing()
                            }
                            Column(Modifier.weight(1f)) {
                                subtitles(first = true)
                                watching()
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    picture(first = true)
                    audio()
                    subtitles(first = false)
                    timing()
                    watching()
                }
            }
        }
    }
}

/** How wide the card is before its groups go side by side, in two columns. */
private val TWO_COLUMNS = 600.dp

/** The most an open list takes in one column: its choice and about four more, then it scrolls. */
private val LIST_HEIGHT = 300.dp

/** The choices of [title] in two columns, [height] tall, scrolling when there are more. */
@Composable
private fun ChoicePage(title: String, choices: List<Choice>, onBack: () -> Unit, height: Dp) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.height(height)) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹", style = MaterialTheme.typography.titleLarge, color = scheme.primary, modifier = Modifier.padding(end = 8.dp))
            SectionLabel(title)
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.chunked(2).forEach { pair ->
                // the two in a row are as tall as each other
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { choice ->
                        Row(
                            Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (choice.selected) scheme.primary.copy(alpha = 0.12f) else scheme.onSurface.copy(alpha = 0.06f))
                                .clickable(onClick = choice.onClick).padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TwoLines(choice.title, choice.detail, if (choice.selected) scheme.primary else scheme.onSurface, Modifier.weight(1f))
                            Box(Modifier.width(20.dp), contentAlignment = Alignment.CenterEnd) {
                                if (choice.selected) Text("✓", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                            }
                        }
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

private class Choice(val title: String, val detail: String?, val selected: Boolean, val onClick: () -> Unit)

@Composable
private fun Label(text: String, top: Dp, note: String? = null) =
    SectionLabel(text, Modifier.padding(start = 4.dp, end = 4.dp, top = top, bottom = 8.dp), note)

/** What every option sits in: a faint lift off the card, as the segments have. */
@Composable
private fun Field(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Column(
    modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
    content = content,
)

/** The chosen one of [choices] (its name and what tells it apart), and when [open], all of them below it. */
@Composable
private fun ChoiceField(open: Boolean, onToggle: () -> Unit, choices: List<Choice>) {
    val scheme = MaterialTheme.colorScheme
    val chosen = choices.firstOrNull { it.selected }
    // when the card scrolls, an opened list is scrolled into sight once it has opened
    val sight = remember { BringIntoViewRequester() }
    LaunchedEffect(open) {
        if (open) {
            delay(350)
            sight.bringIntoView()
        }
    }
    Field(Modifier.heightIn(max = LIST_HEIGHT).bringIntoViewRequester(sight)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TwoLines(chosen?.title ?: "None", chosen?.detail, scheme.onSurface, Modifier.weight(1f))
            Text(if (open) "▴" else "▾", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        }
        AnimatedVisibility(
            open,
            Modifier.weight(1f, fill = false),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HorizontalDivider(color = scheme.outlineVariant)
                choices.forEach { choice ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = choice.onClick)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TwoLines(choice.title, choice.detail, if (choice.selected) scheme.primary else scheme.onSurface, Modifier.weight(1f))
                        Box(Modifier.width(20.dp), contentAlignment = Alignment.CenterEnd) {
                            if (choice.selected) Text("✓", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                        }
                    }
                }
            }
        }
    }
}

/** A name over what tells it apart; the name may take two lines, so it's rarely cut short. */
@Composable
private fun TwoLines(title: String, detail: String?, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The subtitles' shift: steps either side of where it stands, which a tap puts back in sync. */
@Composable
private fun TimingField(offset: Double, onOffset: (Double) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Field {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable
            fun step(label: String, delta: Double) = Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f).fillMaxHeight().clickable { onOffset(offset + delta) }.wrapContentHeight(),
            )
            step("−1", -1.0)
            step("−0.1", -0.1)
            Text(
                if (offset == 0.0) "In sync" else "%+.1f s".format(offset),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = if (offset == 0.0) scheme.onSurfaceVariant else scheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1.6f).fillMaxHeight()
                    .clickable(enabled = offset != 0.0) { onOffset(0.0) }
                    .wrapContentHeight(),
            )
            step("+0.1", 0.1)
            step("+1", 1.0)
        }
    }
}

@Composable
private fun SwitchRow(title: String, on: Boolean, onChange: (Boolean) -> Unit, height: Dp) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = height).clickable { onChange(!on) }.padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}
