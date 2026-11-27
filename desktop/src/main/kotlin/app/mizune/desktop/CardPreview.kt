package app.mizune.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mizune.anki.AnkiConnect
import app.mizune.anki.MinedWord
import app.mizune.dictionary.Definition
import app.mizune.dictionary.DictionaryLookup
import app.mizune.dictionary.TermEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

// The Anki pane's preview: a card as it'll be made, its two sides drawn from the note type's own
// templates and CSS (CardTemplates.kt, CardHtml.kt, CardView.kt), filled with 天気 in
// 今日はいい天気ですね as the dictionaries have it. Until there's a note type to ask about,
// Anki's own plain look.

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardPreview(app: DesktopApp, facts: PaneFacts) {
    val settings = app.settings
    val scheme = MaterialTheme.colorScheme
    val on = settings.ankiEnabled && facts.ankiConnected == true
    val noteType = settings.ankiNoteType
    val format = settings.ankiFormat
    val fields = facts.ankiFields
    val translation = settings.peekLanguage?.let { PEEKS[it] }
    // the note type's own look once AnkiConnect's said what it is
    var real by remember { mutableStateOf<CardLook?>(null) }
    LaunchedEffect(on, noteType, format, fields, settings.ankiUrl, translation) {
        if (!on || noteType == null || format == null || fields.isEmpty()) {
            real = null
            return@LaunchedEffect
        }
        real = withContext(Dispatchers.IO) {
            runCatching {
                val anki = AnkiConnect(settings.ankiUrl)
                CardLook(anki.templates(noteType), anki.styling(noteType), sampleWord(app, translation).fields(format))
            }.getOrNull()?.takeIf { it.templates.isNotEmpty() }
        }
    }
    val look = real ?: PLAIN_LOOK
    val own = mapOf("Tags" to settings.ankiTags, "Deck" to settings.ankiDeck.orEmpty(), "Type" to (noteType ?: "Basic"))
    val dark = scheme.background.luminance() < 0.5f
    val sides = remember(look, own, dark) {
        CardTemplates.sides(look, own).map { side -> side.name to CardPage(side.html, look.css, dark) }
    }

    val caption = when {
        !settings.ankiEnabled -> "Anki cards are off."
        facts.ankiConnected != true -> "Anki's plain look, for now. Once AnkiConnect answers, the card's drawn as the note type has it."
        real == null -> "Choose a note type, and the card's drawn as it has it."
        else -> buildString {
            append("Drawn with $noteType's own templates and styling.")
            if (look.templates.size > 1) append(" The first of its ${look.templates.size} cards.")
            if (look.hasScripts) append(" Its scripts don't run in this preview.")
            append(if (settings.ankiWarnDuplicates) " If the deck has the word already, it asks first." else " It's made even if the deck has the word already.")
        }
    }
    val dim by animateFloatAsState(if (on) 0f else 1f, tween(250), label = "dim")
    PreviewBlock("A card", caption) {
        Column(Modifier.fillMaxWidth().alpha(1f - 0.55f * dim).greyed(dim), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val muted = scheme.onSurfaceVariant
                Text(settings.ankiDeck ?: "No deck yet", style = MaterialTheme.typography.bodySmall, color = muted)
                Text("›", style = MaterialTheme.typography.bodySmall, color = muted.copy(alpha = 0.6f))
                Text(noteType ?: "No note type yet", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                sides.forEach { (name, page) ->
                    Column {
                        Text(
                            name.uppercase(), fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium,
                            color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, bottom = 5.dp),
                        )
                        val shape = RoundedCornerShape(10.dp)
                        ScaledToFit(
                            Modifier.fillMaxWidth().shadow(6.dp, shape).clip(shape).border(1.dp, scheme.hairline, shape),
                        ) { CardPageView(page, animated = settings.ankiImageAnimated) }
                    }
                }
            }
            val tags = settings.ankiTags.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    tags.forEach { Chip(it) }
                }
            }
        }
    }
}

/** 天気 in the sample line, as the dictionaries have it (or a stand-in), made into a card's word. */
private fun sampleWord(app: DesktopApp, translation: String?): MinedWord {
    val found = runCatching { DictionaryLookup(app.database).lookup(SAMPLE_LINE, SAMPLE_START) }.getOrNull()
        ?.takeIf { it.entries.isNotEmpty() }
    return MinedWord(
        SAMPLE_LINE, SAMPLE_START, found?.matchLength ?: 2, found?.entries?.first() ?: SAMPLE_ENTRY, translation,
        "Show 01", 83.765, 85.345, audioFile = SAMPLE_AUDIO, imageFile = SAMPLE_IMAGE,
    )
}

/** Where 天気 starts in the sample line. */
private const val SAMPLE_START = 5

/** 天気, for a card when no dictionary has it. */
private val SAMPLE_ENTRY = TermEntry(
    "天気", "てんき", 2, emptyList(),
    listOf(
        Definition("Sample", "[\"weather\",\"the elements\"]", "n", ""),
        Definition("Sample", "[\"fair weather\",\"fine weather\"]", "n", ""),
    ),
    emptyList(), listOf(1),
)

/**
 * [content] laid out at least a phone wide ([CARD_LAYOUT_WIDTH]) and, with less room than that,
 * shown smaller rather than squeezed.
 */
@Composable
private fun ScaledToFit(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val room = constraints.maxWidth
        val width = maxOf(room, CARD_LAYOUT_WIDTH.dp.roundToPx())
        val scale = room / width.toFloat()
        val placeable = measurables.first().measure(Constraints(minWidth = width, maxWidth = width))
        layout(room, ceil(placeable.height * scale).toInt()) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

/** Drawn in greys, by [amount]: for a card that won't be made while Anki's off. */
private fun Modifier.greyed(amount: Float): Modifier = if (amount == 0f) this else drawWithContent {
    val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1 - amount) }) }
    drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), paint) }
    drawContent()
    drawIntoCanvas { it.restore() }
}
