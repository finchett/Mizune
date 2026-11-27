package app.mizune.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mizune.subs.SubtitleFiles
import app.mizune.ui.SectionLabel
import app.mizune.ui.drawSummerScene
import app.mizune.ui.rememberSceneTime

// Beside each settings pane, while the window has room, what its settings look like: peeking, a
// lookup through the dictionaries in their order, the player stopping (or not) at each line, the
// player in its theme, an Anki card as it'll be made (CardPreview.kt), and the keyboard with the
// player's keys. Drawn from the settings as they are, so a change shows here as it's made. The
// video in them is the summer scene (SummerScene.kt), the same in any theme.

/** Whether [pane] has a preview: every one but About. */
internal fun hasPreview(pane: SettingsPane) = pane != SettingsPane.About

/**
 * The preview column at the right of settings: the sidebar's colour, so the settings sit between
 * the two, with its own header line (under the window's buttons, where it has them).
 */
@Composable
fun SettingsPreview(app: DesktopApp, pane: SettingsPane, facts: PaneFacts, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxHeight()) {
        VerticalDivider(color = scheme.hairline)
        Column(Modifier.fillMaxSize().background(scheme.surfaceVariant)) {
            Box(
                Modifier.fillMaxWidth().height(HeaderHeight).movesWindow().padding(start = 24.dp, end = 12.dp + HeaderEnd),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("Preview", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = scheme.onSurfaceVariant)
            }
            HorizontalDivider(color = scheme.outlineVariant)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val viewport = maxHeight
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    // a pane's preview rises in as it opens, the last one fading out
                    AnimatedContent(
                        pane,
                        transitionSpec = {
                            (fadeIn(tween(300, 80)) + slideInVertically(tween(380, 80, FastOutSlowInEasing)) { it.coerceAtMost(30) / 3 })
                                .togetherWith(fadeOut(tween(150)))
                        },
                        label = "preview",
                    ) { shown ->
                        // in the middle of the column, both ways, while it fits
                        Box(Modifier.fillMaxWidth().heightIn(min = viewport), contentAlignment = Alignment.Center) {
                            Column(
                                Modifier.widthIn(max = 540.dp).fillMaxWidth().padding(horizontal = 28.dp, vertical = 32.dp),
                                verticalArrangement = Arrangement.spacedBy(28.dp),
                            ) {
                                when (shown) {
                                    SettingsPane.General -> PeekingPreview(app.settings)
                                    SettingsPane.Dictionaries -> DictionariesPreview(facts)
                                    SettingsPane.Playback -> PlaybackPreview(app.settings)
                                    SettingsPane.Appearance -> AppearancePreview(app.settings)
                                    SettingsPane.Anki -> CardPreview(app, facts)
                                    SettingsPane.Keyboard -> KeyboardPreview(app.settings, facts)
                                    SettingsPane.About -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A preview's part: its label, what it shows on a panel of its own, and a line on it. */
@Composable
internal fun PreviewBlock(label: String, caption: String?, stage: @Composable BoxScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column {
        SectionLabel(label, Modifier.padding(start = 2.dp, bottom = 10.dp))
        // the panel takes what room there is; what's on it keeps to a sensible size, in its middle
        Box(
            Modifier.fillMaxWidth().heightIn(min = 240.dp).clip(RoundedCornerShape(16.dp)).background(scheme.card)
                .padding(horizontal = 28.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.widthIn(max = 460.dp).fillMaxWidth(), contentAlignment = Alignment.Center, content = stage)
        }
        caption?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 12.dp),
            )
        }
    }
}

internal const val SAMPLE_LINE = "今日はいい天気ですね"

/** The line in each language there is to peek in, as the extension has them. */
internal val PEEKS = mapOf(
    "ar" to "الطقس جميل اليوم، أليس كذلك؟", "zh" to "今天天气真好啊。", "nl" to "Mooi weer vandaag, hè?",
    "en" to "Nice weather today, isn’t it?", "fr" to "Il fait beau aujourd’hui, n’est-ce pas ?",
    "de" to "Schönes Wetter heute, nicht wahr?", "hi" to "आज मौसम अच्छा है, है ना?", "id" to "Cuacanya bagus hari ini, ya?",
    "it" to "Che bella giornata oggi, vero?", "ja" to SAMPLE_LINE, "ko" to "오늘 날씨 좋네요.",
    "pl" to "Ładna dziś pogoda, prawda?", "pt" to "Que tempo bom hoje, não é?", "ru" to "Сегодня хорошая погода, правда?",
    "es" to "Hace buen tiempo hoy, ¿verdad?", "th" to "วันนี้อากาศดีนะ", "tr" to "Bugün hava çok güzel, değil mi?",
    "uk" to "Сьогодні гарна погода, правда?", "vi" to "Hôm nay trời đẹp nhỉ.",
)

/** Japanese, so its kanji are drawn the Japanese way rather than the Chinese. */
internal val Japanese = LocaleList("ja")

private val SceneSky = Color(0xFF2B7DE0)
private val LineShadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f), 3f)

/** The summer scene, covering the box, moving unless [paused]. */
@Composable
private fun SceneBox(modifier: Modifier, paused: Boolean = false, content: @Composable BoxScope.() -> Unit = {}) {
    val time by rememberSceneTime(paused)
    Box(modifier.background(SceneSky)) {
        Canvas(Modifier.matchParentSize()) { coverWithScene(time, Offset.Zero, size) }
        content()
    }
}

/** The scene drawn over [size] at [topLeft], cropped to it rather than letterboxed. */
internal fun DrawScope.coverWithScene(time: Float, topLeft: Offset, size: Size) {
    val w = maxOf(size.width, size.height * 4 / 3)
    val left = topLeft.x + (size.width - w) / 2
    val top = topLeft.y + (size.height - w * 3 / 4) / 2
    clipRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height) { drawSummerScene(time, left, top, w) }
}

/** A subtitle line over the video: white, with a shadow to stand out from the sky. */
@Composable
private fun VideoLine(text: CharSequence, modifier: Modifier = Modifier, size: TextUnit = 15.sp, weight: FontWeight = FontWeight.SemiBold, locale: LocaleList? = Japanese) {
    val style = TextStyle(color = Color.White, fontSize = size, lineHeight = size * 1.33f, fontWeight = weight, textAlign = TextAlign.Center, shadow = LineShadow, localeList = locale)
    when (text) {
        is androidx.compose.ui.text.AnnotatedString -> Text(text, style = style, modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp))
        else -> Text(text.toString(), style = style, modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp))
    }
}

/** 天気 picked out in a line, as a looked-up word is. */
@Composable
private fun lookedUpLine(highlight: Color) = buildAnnotatedString {
    append("今日はいい")
    withStyle(SpanStyle(color = highlight, background = highlight.copy(alpha = 0.14f))) { append("天気") }
    append("ですね")
}

// ---- General: peeking ---------------------------------------------------------------------

@Composable
private fun PeekingPreview(settings: Settings) {
    val studying = settings.targetLanguage
    val line = PEEKS[studying] ?: SAMPLE_LINE
    val peek = settings.peekLanguage
    val name = peek?.let { SubtitleFiles.language(it)?.name ?: it }
    val key = settings.keyMap.label(PlayerAction.Peek)
    // held, then let go, then held again
    val t by rememberInfiniteTransition(label = "peek").animateFloat(0f, 1f, infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "t")
    val caption = when {
        peek == null -> "Peeking's off: the line stays in ${SubtitleFiles.language(studying)?.name ?: studying}."
        key != null -> "Hold $key, or hold the line, and it shows in $name."
        else -> "Hold the line, and it shows in $name."
    }
    PreviewBlock("Peeking", caption) {
        SceneBox(Modifier.fillMaxWidth().aspectRatio(16 / 9f).clip(RoundedCornerShape(10.dp))) {
            BoxWithConstraints(Modifier.matchParentSize()) {
                val other = if (peek == null) 0f else peekShown(t)
                val bottom = Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * 0.12f)
                VideoLine(line, bottom.alpha(1 - other), locale = LocaleList(studying))
                if (peek != null) {
                    VideoLine(PEEKS[peek].orEmpty(), bottom.alpha(other), size = 13.sp, weight = FontWeight.Medium, locale = LocaleList(peek))
                }
            }
        }
    }
}

/** How far the peek line's shown at [t] through the loop: in from 42 %, out from 92 %. */
private fun peekShown(t: Float): Float {
    fun ease(f: Float) = FastOutSlowInEasing.transform(f.coerceIn(0f, 1f))
    return when {
        t < 0.42f -> 0f
        t < 0.5f -> ease((t - 0.42f) / 0.08f)
        t < 0.92f -> 1f
        else -> 1 - ease((t - 0.92f) / 0.08f)
    }
}

// ---- Dictionaries: a lookup through them, in their order ----------------------------------

private val GLOSSES = listOf(
    listOf("weather", "the elements"),
    listOf("fair weather", "fine weather", "sunshine"),
    listOf("the weather, as something to talk about"),
    listOf("mood", "temper"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DictionariesPreview(facts: PaneFacts) {
    val scheme = MaterialTheme.colorScheme
    val all = facts.dictionaries
    val on = all.orEmpty().filter { it.enabled }
    val terms = on.filter { it.termCount > 0 }
    val counts = on.filter { it.termCount == 0 && it.metaCount > 0 }
    val caption = when {
        all == null -> null
        terms.isNotEmpty() -> "A word's meanings come from each dictionary in turn, top first. Ones turned off are left out."
        all.any { it.termCount > 0 } -> "With every dictionary turned off, words aren't looked up. Turn one on to have them back."
        else -> "Words are looked up once there's a dictionary. Import a Yomitan dictionary to add one."
    }
    PreviewBlock("A lookup", caption) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.background)
                .border(1.dp, scheme.hairline, RoundedCornerShape(14.dp)).animateContentSize(),
        ) {
            Text(
                lookedUpLine(scheme.primary),
                fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                style = TextStyle(localeList = Japanese),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            HorizontalDivider(color = scheme.hairline)
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (all != null && terms.isEmpty()) {
                    Text(
                        if (all.any { it.termCount > 0 }) "Every dictionary is turned off" else "No dictionaries yet",
                        style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    return@Column
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("天気", fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, style = TextStyle(localeList = Japanese))
                    Text("てんき", fontSize = 13.sp, color = scheme.onSurfaceVariant, style = TextStyle(localeList = Japanese), modifier = Modifier.padding(bottom = 5.dp))
                }
                if (counts.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        counts.forEach { Chip(it.title, muted = true) }
                    }
                }
                terms.forEachIndexed { i, dictionary ->
                    if (i > 0) HorizontalDivider(Modifier.padding(top = 12.dp), color = scheme.rowLine)
                    Column(Modifier.padding(top = if (i == 0) 8.dp else 12.dp)) {
                        Chip(dictionary.title)
                        GLOSSES[i % GLOSSES.size].forEachIndexed { n, gloss ->
                            Row(Modifier.padding(top = if (n == 0) 8.dp else 0.dp)) {
                                Text("${n + 1}.", fontSize = 11.sp, lineHeight = 20.sp, color = scheme.onSurfaceVariant, modifier = Modifier.width(20.dp))
                                Text(gloss, fontSize = 13.sp, lineHeight = 20.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A dictionary's name on a lookup; [muted] for one that only gives counts. */
@Composable
internal fun Chip(text: String, muted: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text,
        fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
        color = if (muted) scheme.onSurfaceVariant else scheme.primary,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (muted) scheme.onSurface.copy(alpha = 0.07f) else scheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

// ---- Playback: the player going from line to line -----------------------------------------

private val LINES = listOf("駅まで歩いて行こう", SAMPLE_LINE, "また明日")
private const val LINE_MS = 1600f
private const val PAUSE_MS = 1100f

@Composable
private fun PlaybackPreview(settings: Settings) {
    val scheme = MaterialTheme.colorScheme
    val autoPause = settings.autoPause
    val pauseOnLookup = settings.pauseOnLookup
    val copyLines = settings.copyLines
    var line by remember { mutableIntStateOf(0) }
    /** How far into the line it's played. */
    var at by remember { mutableFloatStateOf(0f) }
    /** Left of a pause, and whether the next line comes after it (a stop at a line's end). */
    var paused by remember { mutableFloatStateOf(0f) }
    var nextAfter by remember { mutableStateOf(false) }
    /** Left of a lookup's bubble, while the player goes on under it. */
    var looking by remember { mutableFloatStateOf(0f) }
    var copies by remember { mutableIntStateOf(0) }

    // a loop: each line plays, then the player stops at its end or goes on; halfway through the
    // second a word's looked up, pausing the player or not
    LaunchedEffect(autoPause, pauseOnLookup, copyLines) {
        fun next() {
            line = (line + 1) % LINES.size
            if (copyLines) copies++
        }
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e6f).coerceAtMost(100f)
            last = now
            if (looking > 0) looking -= dt
            if (paused > 0) {
                paused -= dt
                if (paused <= 0 && nextAfter) {
                    nextAfter = false
                    at = 0f
                    next()
                }
            } else {
                val before = at
                at += dt
                if (line == 1 && before < LINE_MS / 2 && at >= LINE_MS / 2) {
                    if (pauseOnLookup) paused = PAUSE_MS else looking = PAUSE_MS
                }
                if (at >= LINE_MS) {
                    if (autoPause) {
                        at = LINE_MS
                        paused = PAUSE_MS
                        nextAfter = true
                    } else {
                        at = 0f
                        next()
                    }
                }
            }
        }
    }

    val lookedUp = line == 1 && (looking > 0 || (paused > 0 && !nextAfter))
    val stopped = paused > 0
    val badge by animateFloatAsState(if (stopped) 1f else 0f, tween(200), label = "badge")
    val bubble by animateFloatAsState(if (lookedUp) 1f else 0f, tween(200), label = "bubble")
    val toast = remember { Animatable(0f) }
    LaunchedEffect(copies) {
        if (copies == 0) return@LaunchedEffect
        toast.snapTo(0f)
        toast.animateTo(1f, tween(200))
        kotlinx.coroutines.delay(780)
        toast.animateTo(0f, tween(320))
    }

    val said = listOf(
        if (autoPause) "It stops at the end of each line" else "It plays straight on from line to line",
        if (pauseOnLookup) "pauses while a word's looked up" else "keeps playing while a word's looked up",
        if (copyLines) "and copies each line as it comes." else "and leaves the clipboard alone.",
    )
    PreviewBlock("Playing", said.joinToString(", ")) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // a paused player's picture stands still
            SceneBox(Modifier.fillMaxWidth().aspectRatio(16 / 9f).clip(RoundedCornerShape(10.dp)), paused = stopped) {
                BoxWithConstraints(Modifier.matchParentSize()) {
                    val lineBottom = maxHeight * 0.12f
                    VideoLine(
                        if (lookedUp) lookedUpLine(scheme.primary) else buildAnnotatedString { append(LINES[line]) },
                        Modifier.align(Alignment.BottomCenter).padding(bottom = lineBottom),
                        size = 16.sp,
                    )
                    // the word's meaning over the line
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = lineBottom + 30.dp).offset(y = (4 * (1 - bubble)).dp)
                            .alpha(bubble).shadow(8.dp, RoundedCornerShape(9.dp)).clip(RoundedCornerShape(9.dp))
                            .background(scheme.background).padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("天気", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = scheme.primary, style = TextStyle(localeList = Japanese))
                        Text(" weather", fontSize = 12.sp, color = scheme.onSurface)
                    }
                    PauseBadge(Modifier.align(Alignment.Center).alpha(badge).scale(0.8f + 0.2f * badge))
                    Text(
                        "Copied",
                        fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = scheme.background,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).offset(y = (-4 * (1 - toast.value)).dp)
                            .alpha(toast.value).clip(RoundedCornerShape(8.dp)).background(scheme.primary)
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }
            Track(line, at)
        }
    }
}

@Composable
private fun PauseBadge(modifier: Modifier) {
    Canvas(modifier.size(46.dp)) {
        drawCircle(Color.Black.copy(alpha = 0.5f))
        val u = size.width / 46f
        drawRoundRect(Color.White, Offset(16 * u, 15 * u), Size(5 * u, 16 * u), CornerRadius(2 * u))
        drawRoundRect(Color.White, Offset(25 * u, 15 * u), Size(5 * u, 16 * u), CornerRadius(2 * u))
    }
}

/** The lines along the time, and the player's place among them. */
@Composable
private fun Track(line: Int, at: Float) {
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant.copy(alpha = 0.25f)
    val segment = scheme.primary.copy(alpha = 0.35f)
    val head = scheme.primary
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val u = density
        drawRoundRect(muted, Offset(0f, 5 * u), Size(size.width, 2 * u), CornerRadius(u))
        repeat(LINES.size) { i ->
            drawRoundRect(segment, Offset(size.width * (0.06f + i * 0.32f), 4 * u), Size(size.width * 0.26f, 4 * u), CornerRadius(2 * u))
        }
        val x = size.width * (0.06f + line * 0.32f + (minOf(at, LINE_MS) / LINE_MS) * 0.26f)
        drawCircle(head.copy(alpha = 0.25f), 8 * u, Offset(x, 6 * u))
        drawCircle(head, 5 * u, Offset(x, 6 * u))
    }
}

// ---- Appearance: the player, in its theme --------------------------------------------------

@Composable
private fun AppearancePreview(settings: Settings) {
    val scheme = MaterialTheme.colorScheme
    val rounded = settings.roundedCorners
    val fill by animateFloatAsState(if (settings.videoFill) 1f else 0f, tween(450), label = "fill")
    val frame by animateDpAsState(if (rounded) 6.dp else 0.dp, tween(350), label = "frame")
    val corner by animateDpAsState(if (rounded) 8.dp else 0.dp, tween(350), label = "corner")
    val time by rememberSceneTime(false)
    val caption = listOf(
        if (settings.videoFill) "The picture fills its area, its edges cropped" else "The whole picture shows, with bars where it's wider",
        if (rounded) "framed with rounded corners." else "edge to edge.",
    ).joinToString(", ")
    PreviewBlock("The player", caption) {
        Column(
            Modifier.fillMaxWidth().aspectRatio(16 / 10f).clip(RoundedCornerShape(12.dp)).background(Color.Black).padding(frame),
            verticalArrangement = Arrangement.spacedBy(frame),
        ) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(frame)) {
                // the video's area: fitted, the picture's whole width with bars above and below;
                // filling, a little bigger than the area, its edges cut off
                Canvas(Modifier.weight(63f).fillMaxHeight().clip(RoundedCornerShape(corner)).background(Color.Black)) {
                    val insetY = size.height * (0.21f + (-0.02f - 0.21f) * fill)
                    val insetX = size.width * (-0.14f * fill)
                    val picture = Size(size.width - 2 * insetX, size.height - 2 * insetY)
                    clipRect { coverWithScene(time, Offset(insetX, insetY), picture) }
                }
                // the panel, with the line at the size set, drawn at the scale the rest is
                Column(
                    Modifier.weight(37f).fillMaxHeight().clip(RoundedCornerShape(corner)).background(scheme.background)
                        .then(if (rounded) Modifier else Modifier.border(1.dp, scheme.hairline)),
                ) {
                    Box(
                        Modifier.fillMaxWidth().background(scheme.onSurface.copy(alpha = 0.04f)).padding(horizontal = 8.dp, vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val size = (settings.subtitleSize * 0.34f * 10).toInt() / 10f
                        Text(
                            SAMPLE_LINE, fontSize = size.sp, lineHeight = (size * 1.3f).sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, softWrap = false, style = TextStyle(localeList = Japanese),
                        )
                    }
                    HorizontalDivider(color = scheme.hairline)
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("天気", fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, style = TextStyle(localeList = Japanese))
                            Text("てんき", fontSize = 9.sp, lineHeight = 14.sp, color = scheme.onSurfaceVariant, style = TextStyle(localeList = Japanese))
                        }
                        listOf(0.86f, 0.55f, 0.86f).forEach { width ->
                            Box(Modifier.fillMaxWidth(width).height(5.dp).clip(RoundedCornerShape(3.dp)).background(scheme.onSurface.copy(alpha = 0.12f)))
                        }
                    }
                }
            }
            // the controls along the foot
            Row(
                Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Canvas(Modifier.size(7.dp, 9.dp)) {
                    drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, size.height / 2); lineTo(0f, size.height); close() }, Color.White.copy(alpha = 0.85f))
                }
                val primary = scheme.primary
                Canvas(Modifier.weight(1f).height(3.dp)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(Color.White.copy(alpha = 0.25f), cornerRadius = r)
                    drawRoundRect(primary, size = Size(size.width * 0.34f, size.height), cornerRadius = r)
                }
            }
        }
    }
}

// ---- Keyboard: the player's keys -----------------------------------------------------------

private val ROWS = listOf(
    listOf("Escape", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
    listOf("A", "S", "D", "F", "G", "H", "J", "K", "L", ";"),
    listOf("Z", "X", "C", "V", "B", "N", "M", ",", ".", "/"),
)

/** What a key does: the row it's on in the pane, and how that row writes the key. */
private class Job(val title: String, val cap: String, val withModifier: Boolean)

@Composable
private fun KeyboardPreview(settings: Settings, facts: PaneFacts) {
    val map = settings.keyMap
    // what each key does, by its name as a key map writes it
    val does = remember(map) {
        val jobs = linkedMapOf<String, MutableList<Job>>()
        for (action in PlayerAction.entries) {
            for (binding in map.keysFor(action)) {
                val plain = !(binding.shift || binding.ctrl || binding.alt || binding.meta)
                jobs.getOrPut(binding.name.substringAfterLast('+')) { mutableListOf() }.add(Job(action.label, binding.label, !plain))
            }
        }
        FIXED_KEYS.firstOrNull { it.caps == listOf("Esc") }?.let { jobs.getOrPut("Escape") { mutableListOf() }.add(Job(it.action, "Esc", false)) }
        jobs as Map<String, List<Job>>
    }
    var pointedKey by remember { mutableStateOf<String?>(null) }
    val pointedRow = facts.pointedRow
    // a key pointed at lights the rows it does in the pane; a row pointed at, its keys here
    LaunchedEffect(pointedKey, does) {
        facts.litRows = pointedKey?.let { key -> does[key].orEmpty().map { it.title }.toSet() } ?: emptySet()
    }
    DisposableEffect(Unit) { onDispose { facts.litRows = emptySet() } }
    val lit: Set<String> = when {
        pointedKey != null -> setOf(pointedKey!!)
        pointedRow != null -> does.filterValues { jobs -> jobs.any { it.title == pointedRow } }.keys
        else -> emptySet()
    }
    val caption = when {
        pointedKey != null -> does[pointedKey]?.joinToString(" · ") { "${it.cap}: ${it.title}" } ?: "${capLabel(pointedKey!!)} isn't used"
        pointedRow != null -> {
            val caps = does.values.flatten().filter { it.title == pointedRow }.map { it.cap }
            val fixed = FIXED_KEYS.firstOrNull { it.action == pointedRow }
            when {
                caps.isNotEmpty() -> "${caps.joinToString(" or ")}: $pointedRow"
                fixed != null && fixed.caps.isNotEmpty() -> "${fixed.caps.joinToString(if (isMac) "" else "+")}: $pointedRow"
                fixed != null -> listOfNotNull(pointedRow, fixed.note).joinToString(": ")
                else -> "$pointedRow: no key yet"
            }
        }
        else -> "Keys lit do something in the player. Point at one, or at a row, to see which."
    }

    Column {
        SectionLabel("The keys", Modifier.padding(start = 2.dp, bottom = 10.dp))
        BoxWithConstraints(
            Modifier.fillMaxWidth().heightIn(min = 240.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.card)
                .padding(horizontal = 14.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            // the keys shrink to fit the panel, at most 26 dp: eleven keys and ten gaps across the top row
            val k = minOf(26.dp, maxWidth / 12.95f)
            val gap = k / 5.2f
            fun hover(key: String, on: Boolean) {
                if (on) pointedKey = key else if (pointedKey == key) pointedKey = null
            }
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                ROWS.forEachIndexed { i, row ->
                    Row(Modifier.padding(start = k * (listOf(0f, 0.54f, 1.08f)[i])), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { key -> BoardKey(key, k, k, does[key], key in lit) { hover(key, it) } }
                    }
                }
                Row(
                    Modifier.width(k * 11 + gap * 10).padding(start = k * 2.15f, end = k * 0.4f),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    BoardKey("Space", k * 5.8f, k, does["Space"], "Space" in lit) { hover("Space", it) }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        BoardKey("Up", k, k, does["Up"], "Up" in lit) { hover("Up", it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            listOf("Left", "Down", "Right").forEach { key -> BoardKey(key, k, k, does[key], key in lit) { hover(key, it) } }
                        }
                    }
                }
            }
        }
        Text(
            caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 2,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 12.dp),
        )
    }
}

/** A key's name as its cap shows it. */
private fun capLabel(key: String) = when (key) {
    "Escape" -> "esc"
    "Space" -> ""
    "Left" -> "←"
    "Right" -> "→"
    "Up" -> "↑"
    "Down" -> "↓"
    else -> key
}

/** One key of the board: tinted if it does something, a dot if only with a modifier, lit if pointed at. */
@Composable
private fun BoardKey(key: String, width: Dp, k: Dp, jobs: List<Job>?, lit: Boolean, onHover: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { onHover(hovered) }
    val bound = jobs != null
    val text by animateColorAsState(
        when {
            lit -> scheme.background
            bound -> scheme.primary
            else -> scheme.onSurfaceVariant.copy(alpha = 0.7f)
        },
        tween(150), label = "text",
    )
    val fill by animateColorAsState(
        when {
            lit -> scheme.primary
            bound -> scheme.primary.copy(alpha = 0.14f).compositeOn(scheme.card)
            else -> scheme.control
        },
        tween(150), label = "fill",
    )
    val edge = when {
        lit -> scheme.primary
        bound -> scheme.primary.copy(alpha = 0.35f)
        else -> scheme.controlLine
    }
    val lift by animateDpAsState(if (lit) (-1).dp else 0.dp, tween(150), label = "lift")
    val fontSize = with(LocalDensity.current) { (k * 0.42f).toSp() }
    Box(
        Modifier.offset(y = lift).size(width, k).clip(RoundedCornerShape(6.dp)).background(fill)
            .border(1.dp, edge, RoundedCornerShape(6.dp)).hoverable(interaction),
        contentAlignment = Alignment.Center,
    ) {
        Text(capLabel(key), fontSize = fontSize, lineHeight = fontSize, fontWeight = FontWeight.Medium, color = text, maxLines = 1)
        if (jobs != null && jobs.all { it.withModifier }) {
            Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(4.dp).clip(CircleShape).background(text.copy(alpha = 0.7f)))
        }
    }
}

private fun Color.compositeOn(background: Color): Color = Color(
    red = red * alpha + background.red * (1 - alpha),
    green = green * alpha + background.green * (1 - alpha),
    blue = blue * alpha + background.blue * (1 - alpha),
)
