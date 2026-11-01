package app.mizune.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.mizune.App
import app.mizune.R
import app.mizune.anki.AnkiDroid
import app.mizune.anki.AnkiException
import app.mizune.anki.CardFormats
import app.mizune.subs.SubtitleFiles
import app.mizune.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The first launch, as on the desktop: a hello, then the few things worth choosing before the first
 * video (a look, the peek language, the video folder, Anki), then a thank-you. Everything here can
 * be changed later in Settings, so every step can be skipped.
 */
@Composable
fun Onboarding(app: App, onFinish: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(OnboardingStep.Welcome) }
    // Prefs aren't observed, so the footer's words follow these, set by the steps
    var folderChosen by remember { mutableStateOf(app.prefs.libraryTreeUri != null) }
    var ankiOn by remember { mutableStateOf(app.prefs.ankiEnabled) }
    fun go(to: OnboardingStep) { step = to }
    BackHandler(enabled = step != OnboardingStep.Welcome) { go(OnboardingStep.entries[step.ordinal - 1]) }

    // a Surface, so text without a colour of its own takes the theme's
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(top = 12.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StepDots(step)
                AnimatedContent(
                    targetState = step,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val sign = if (forward) 1 else -1
                        (fadeIn(tween(260, delayMillis = 60)) + slideInHorizontally(tween(340)) { sign * it / 8 }) togetherWith
                            (fadeOut(tween(160)) + slideOutHorizontally(tween(340)) { -sign * it / 8 })
                    },
                    label = "onboarding",
                ) { shown ->
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                        Column(
                            Modifier.widthIn(max = 560.dp).padding(horizontal = 24.dp, vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            when (shown) {
                                OnboardingStep.Welcome -> WelcomeStep(onStart = { go(OnboardingStep.Look) })
                                OnboardingStep.Look -> LookStep(app)
                                OnboardingStep.Peek -> PeekStep(app)
                                OnboardingStep.Videos -> VideosStep(app) { folderChosen = true }
                                OnboardingStep.Anki -> AnkiStep(app) { ankiOn = it }
                                OnboardingStep.Done -> DoneStep(app)
                            }
                        }
                    }
                }
                if (step != OnboardingStep.Welcome) {
                    Footer(
                        onBack = { go(OnboardingStep.entries[step.ordinal - 1]) },
                        next = when (step) {
                            OnboardingStep.Videos -> if (folderChosen) "Continue" else "Skip for now"
                            OnboardingStep.Anki -> if (ankiOn) "Continue" else "Maybe later"
                            OnboardingStep.Done -> "Start watching"
                            else -> "Continue"
                        },
                        onNext = { if (step == OnboardingStep.Done) onFinish() else go(OnboardingStep.entries[step.ordinal + 1]) },
                    )
                }
            }
            if (step == OnboardingStep.Done) Confetti(Modifier.fillMaxSize())
        }
    }
}

enum class OnboardingStep { Welcome, Look, Peek, Videos, Anki, Done }

// ---- the steps ----

@Composable
private fun ColumnScope.WelcomeStep(onStart: () -> Unit) {
    Mascot(150.dp)
    Spacer(Modifier.height(18.dp))
    Eyebrow("ようこそ")
    Headline("Welcome to Mizune")
    Spacer(Modifier.height(12.dp))
    Body(
        "Watch the shows you love in Japanese, look up any word with a tap, and keep the ones " +
            "you want as flashcards. A minute of setting up and you're watching.",
    )
    Spacer(Modifier.height(28.dp))
    Button(onClick = onStart, shape = RoundedCornerShape(12.dp)) {
        Text("Let's get started", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

@Composable
private fun ColumnScope.LookStep(app: App) {
    Eyebrow("Step 1 of 4")
    Headline("Pick a look")
    Spacer(Modifier.height(10.dp))
    Body("Your eyes, your call. System follows your phone's light and dark mode.")
    Spacer(Modifier.height(24.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ThemeSwatch("System", null, app.appearance.theme == null) { app.appearance.updateTheme(null) }
        AppTheme.entries.forEach { theme ->
            ThemeSwatch(theme.label, theme, app.appearance.theme == theme) { app.appearance.updateTheme(theme) }
        }
    }
}

@Composable
private fun ColumnScope.PeekStep(app: App) {
    val prefs = app.prefs
    var peek by remember { mutableStateOf(prefs.peekLanguage) }
    fun choose(code: String?) { peek = code; prefs.peekLanguage = code }
    Eyebrow("Step 2 of 4")
    Headline("Peek in your language")
    Spacer(Modifier.height(10.dp))
    Body(
        "Stuck on a line? Hold it and it shows in a language you know. Pick yours, or turn peeking " +
            "off if you'd rather not be tempted.",
    )
    Spacer(Modifier.height(22.dp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 480.dp) 4 else 2
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtitleFiles.LANGUAGES.filter { it.code != prefs.targetLanguage }.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { language ->
                        LanguageTile(language.name, language.native.takeIf { it != language.name }, peek == language.code, Modifier.weight(1f)) {
                            choose(language.code)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(4.dp))
            LanguageTile("No peeking", "Holding the line shows nothing. For the strong-willed.", peek == null, Modifier.fillMaxWidth()) {
                choose(null)
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Text(
        peek?.let(::peekLanguageNote) ?: "You can change this any time in Settings › Languages.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ColumnScope.VideosStep(app: App, onChosen: () -> Unit) {
    val context = LocalContext.current
    var folder by remember { mutableStateOf(libraryFolderName(context, app.prefs.libraryTreeUri)) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.prefs.libraryTreeUri = uri.toString()
            app.prefs.libraryPath = null
            folder = libraryFolderName(context, uri.toString())
            onChosen()
        }
    }
    Eyebrow("Step 3 of 4")
    Headline("Where do your videos live?")
    Spacer(Modifier.height(10.dp))
    Body(
        "Pick the folder that holds your shows. Each folder inside becomes a series, and subtitles " +
            "sitting next to a video are found on their own.",
    )
    Spacer(Modifier.height(24.dp))
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().height(160.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.primary.copy(alpha = 0.05f))
            .dashedBorder(if (folder == null) scheme.primary.copy(alpha = 0.45f) else Color.Transparent)
            .border(1.dp, if (folder != null) scheme.primary.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable { pickFolder.launch(null) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FolderIcon(scheme.primary, filled = folder != null)
            if (folder == null) {
                Text("Choose a folder", style = MaterialTheme.typography.titleMedium, color = scheme.primary)
                Text("On this phone or its SD card", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            } else {
                Text(folder.orEmpty(), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Text("Change…", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
            }
        }
    }
}

@Composable
private fun ColumnScope.AnkiStep(app: App, onEnabled: (Boolean) -> Unit) {
    val context = LocalContext.current
    val prefs = app.prefs
    var enabled by remember { mutableStateOf(prefs.ankiEnabled) }
    var noteType by remember { mutableStateOf(prefs.ankiNoteType) }
    var deck by remember { mutableStateOf(prefs.ankiDeck) }
    var attempt by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { attempt++ }
    var collection by remember { mutableStateOf<Result<AnkiCollection>?>(null) }
    LaunchedEffect(attempt) {
        collection = null
        collection = withContext(Dispatchers.IO) {
            runCatching {
                AnkiDroid.unavailable(context)?.let { throw AnkiException(it) }
                val anki = AnkiDroid(context)
                AnkiCollection(anki.noteTypes(), anki.decks())
            }
        }
    }
    // back from AnkiDroid (or anywhere else): look again, as its API may have been turned on meanwhile
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var left = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) left = true
            if (event == Lifecycle.Event.ON_RESUME && left) { left = false; attempt++ }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val connected = collection?.getOrNull()
    // with cards on, what Settings › Anki would set up: the known note type, and a source for each of its fields
    LaunchedEffect(connected, enabled, noteType) {
        if (connected == null || !enabled) return@LaunchedEffect
        val name = noteType
        if (name == null) {
            CardFormats.pick(connected.noteTypes)?.let { noteType = it; prefs.ankiNoteType = it }
            return@LaunchedEffect
        }
        val names = withContext(Dispatchers.IO) { runCatching { AnkiDroid(context).fieldNames(name) }.getOrNull() }
            ?: return@LaunchedEffect
        prefs.fillCardFormat(name, names)
    }
    val scheme = MaterialTheme.colorScheme
    Eyebrow("Step 4 of 4 · Optional")
    Headline("Make flashcards with Anki")
    Spacer(Modifier.height(10.dp))
    Body(
        "Look up a word, tap +, and it becomes an Anki card with the sentence, its audio and a " +
            "still from the scene.",
    )
    Spacer(Modifier.height(22.dp))
    val installed = AnkiDroid.installed(context)
    // a note type is asked for only when the collection has none known field by field
    val askNoteType = connected != null && CardFormats.pick(connected.noteTypes) == null
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val dot = when {
                connected != null -> scheme.primary
                collection != null -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
                else -> scheme.onSurfaceVariant.copy(alpha = 0.25f)
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when {
                        collection == null -> "Looking for AnkiDroid…"
                        connected != null -> "AnkiDroid is ready"
                        !installed -> "AnkiDroid isn't installed"
                        !AnkiDroid.permitted(context) -> "AnkiDroid needs your OK"
                        else -> "AnkiDroid isn't answering"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when {
                        collection == null -> "Cards go straight into AnkiDroid on this phone."
                        connected == null && !installed -> "Cards go into AnkiDroid on this phone. Or set it up later in Settings › Anki."
                        connected == null && !AnkiDroid.permitted(context) -> "Allow Mizune to add cards to it."
                        // AnkiDroid answers other apps only with its API on, and only once it has a collection
                        connected == null -> "In AnkiDroid, turn on Settings › Advanced › Enable AnkiDroid API. If it's new, opening it once sets it up."
                        !enabled -> "Turn cards on to start making them."
                        askNoteType && noteType == null -> "Cards are on. Pick a note type and the deck they go in."
                        deck == null -> "Cards are on. Pick the deck they go in."
                        else -> "Cards are on. Fields and tags are under Settings › Anki."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            when {
                collection == null -> Unit
                connected != null -> Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    prefs.ankiEnabled = it
                    onEnabled(it)
                })
                installed && !AnkiDroid.permitted(context) -> OutlinedButton(onClick = { permission.launch(AnkiDroid.PERMISSION) }) { Text("Allow") }
                installed -> OutlinedButton(onClick = {
                    context.packageManager.getLaunchIntentForPackage(AnkiDroid.PACKAGE)?.let(context::startActivity)
                }) { Text("Open AnkiDroid") }
                else -> TextButton(onClick = { attempt++ }) { Text("Check again") }
            }
        }
        if (connected != null && enabled) {
            if (askNoteType) {
                Divider()
                AnkiChoice("Note type", noteType, connected.noteTypes) { noteType = it; prefs.ankiNoteType = it }
            }
            Divider()
            AnkiChoice("Deck", deck, connected.decks) { deck = it; prefs.ankiDeck = it }
        }
    }
    if (collection != null && !installed) {
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { context.open("https://play.google.com/store/apps/details?id=${AnkiDroid.PACKAGE}") }) {
            Text("Get AnkiDroid ↗", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ColumnScope.DoneStep(app: App) {
    val context = LocalContext.current
    Mascot(130.dp)
    Spacer(Modifier.height(18.dp))
    Eyebrow("準備完了")
    Headline("You're all set!")
    Spacer(Modifier.height(12.dp))
    Body(
        "Thank you for giving Mizune a go. It's young and growing quickly, and new versions " +
            "arrive on their own, with a note on what's new.",
    )
    Spacer(Modifier.height(22.dp))
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Hint("Tap a word", "Look it up")
        Hint("Swipe", "The next or previous line")
        Hint("Hold", "Peek at the translation")
        Hint("+", "Turn the word into an Anki card")
    }
    Spacer(Modifier.height(12.dp))
    var usageStats by remember { mutableStateOf(app.prefs.usageStats) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Send anonymous usage counts", style = MaterialTheme.typography.titleSmall)
            Text(
                "Once a day: a random id for this install, your Android version and the app's. Nothing you watch or look up.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Switch(checked = usageStats, onCheckedChange = { usageStats = it; app.prefs.usageStats = it })
    }
    Spacer(Modifier.height(10.dp))
    TextButton(onClick = { context.open(Updates.ISSUES_PAGE) }) {
        Text("Found a bug, or wish it did something? Tell us on GitHub ↗", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

// ---- pieces ----

@Composable
private fun StepDots(step: OnboardingStep) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OnboardingStep.entries.forEach {
            val width by animateDpAsState(if (it == step) 22.dp else 7.dp, label = "dot")
            Box(
                Modifier.size(width, 7.dp).clip(CircleShape)
                    .background(if (it.ordinal <= step.ordinal) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.22f)),
            )
        }
    }
}

@Composable
private fun Footer(onBack: () -> Unit, next: String, onNext: () -> Unit) {
    Row(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("‹  Back") }
        Spacer(Modifier.weight(1f))
        Button(onClick = onNext, shape = RoundedCornerShape(10.dp)) { Text(next) }
    }
}

/** The app's icon, bobbing gently. */
@Composable
private fun Mascot(size: Dp) {
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "bob",
    )
    Box(
        Modifier.offset(y = (sin(bob * 2 * PI) * 5).dp).size(size)
            .clip(RoundedCornerShape(size * 0.25f))
            .background(colorResource(R.color.ic_launcher_background)),
        contentAlignment = Alignment.Center,
    ) {
        // the launcher foreground draws inside a 72 of 108 dp safe zone, so it is scaled to fill
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, Modifier.size(size * 1.5f))
    }
}

/** A burst of paper confetti in the theme's colours, falling once. */
@Composable
private fun Confetti(modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.primary.copy(alpha = 0.6f))
    val pieces = remember {
        val random = Random(7)
        List(70) {
            Piece(
                x = random.nextFloat(),
                delay = random.nextFloat() * 0.35f,
                drift = (random.nextFloat() - 0.5f) * 0.25f,
                spin = (random.nextFloat() - 0.5f) * 900f,
                color = it % 4,
                wide = random.nextBoolean(),
            )
        }
    }
    val time = remember { Animatable(0f) }
    LaunchedEffect(Unit) { time.animateTo(1f, tween(2600, easing = LinearEasing)) }
    Canvas(modifier) {
        val t = time.value
        pieces.forEach { piece ->
            val local = ((t - piece.delay) / (1f - piece.delay)).coerceIn(0f, 1f)
            if (local <= 0f || local >= 1f) return@forEach
            val x = size.width * (piece.x + piece.drift * local)
            val y = size.height * (-0.1f + 1.15f * local * local)
            val alpha = if (local > 0.75f) (1f - local) / 0.25f else 1f
            val w = (if (piece.wide) 9f else 5f) * density
            val h = 6f * density
            rotate(piece.spin * local, Offset(x, y)) {
                drawRect(colors[piece.color].copy(alpha = alpha * colors[piece.color].alpha), Offset(x - w / 2, y - h / 2), Size(w, h))
            }
        }
    }
}

private class Piece(val x: Float, val delay: Float, val drift: Float, val spin: Float, val color: Int, val wide: Boolean)

@Composable
private fun Eyebrow(text: String) {
    Text(
        text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.4.sp,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Headline(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** A choice as a tile: [title], with a quieter [detail] under it; outlined while [selected]. */
@Composable
private fun LanguageTile(title: String, detail: String?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.height(56.dp)
            .clip(shape)
            .background(if (selected) scheme.primary.copy(alpha = 0.12f) else scheme.surfaceVariant)
            .border(1.5.dp, if (selected) scheme.primary else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) scheme.primary else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One of the Anki step's choices: [title], and a menu of [options] showing [chosen]. */
@Composable
private fun AnkiChoice(title: String, chosen: String?, options: List<String>, onChoose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.widthIn(max = 200.dp)) {
                Text(
                    (chosen ?: "Choose…") + "  ▾",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (chosen == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { onChoose(option); open = false })
                }
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
}

/** A folder, outlined until one's chosen. */
@Composable
private fun FolderIcon(color: Color, filled: Boolean) {
    Canvas(Modifier.size(46.dp, 38.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.4f * density
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, h * 0.18f)
            quadraticTo(0f, 0f, w * 0.08f, 0f)
            lineTo(w * 0.36f, 0f)
            lineTo(w * 0.46f, h * 0.14f)
            lineTo(w * 0.92f, h * 0.14f)
            quadraticTo(w, h * 0.14f, w, h * 0.28f)
            lineTo(w, h * 0.88f)
            quadraticTo(w, h, w * 0.92f, h)
            lineTo(w * 0.08f, h)
            quadraticTo(0f, h, 0f, h * 0.88f)
            close()
        }
        if (filled) drawPath(path, color.copy(alpha = 0.18f))
        drawPath(path, color, style = Stroke(stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawLine(color, Offset(0f, h * 0.3f), Offset(w, h * 0.3f), stroke)
    }
}

private fun Modifier.dashedBorder(color: Color): Modifier = drawBehind {
    if (color.alpha == 0f) return@drawBehind
    drawRoundRect(
        color,
        cornerRadius = CornerRadius(16.dp.toPx()),
        style = Stroke(1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))),
    )
}

private fun android.content.Context.open(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
