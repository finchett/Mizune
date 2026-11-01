package app.mizune.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.mizune.ui.AppTheme
import app.mizune.anki.CardFormats
import app.mizune.anki.CardSource
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.prefs.Preferences
import javax.imageio.ImageIO

/** Every step of the welcome renders, light and dark; MIZUNE_TEST_SHOTS keeps a picture of each. */
@OptIn(ExperimentalTestApi::class)
class OnboardingTest {
    private val shots = System.getenv("MIZUNE_TEST_SHOTS")?.let(::File)?.apply { mkdirs() }

    @Test
    fun everyStepRenders() {
        val dir = Files.createTempDirectory("mizune-onboarding").toFile()
        File(dir, "Videos/Show").mkdirs()
        listOf("Episode 1.mkv", "Episode 2.mkv").forEach { File(dir, "Videos/Show/$it").writeText("") }
        val node = "app/mizune-test-onboarding"
        Preferences.userRoot().node(node).removeNode()
        val settings = Settings(node).apply { updateAnkiUrl("http://127.0.0.1:9") }
        // nobody who'd chosen a library before is welcomed again
        assertTrue(!settings.onboarded)
        val app = DesktopApp(File(dir, ".data").apply { mkdirs() }, File(dir, ".cache"), settings)
        val headlines = mapOf(
            OnboardingStep.Welcome to "Welcome to Mizune",
            OnboardingStep.Look to "Pick a look",
            OnboardingStep.Peek to "Peek in your language",
            OnboardingStep.Videos to "Where do your videos live?",
            OnboardingStep.Dictionaries to "Choose your dictionaries",
            OnboardingStep.Anki to "Make flashcards with Anki",
            OnboardingStep.Done to "You're all set!",
        )
        try {
            runDesktopComposeUiTest(width = 1100, height = 720) {
                // the mascot bobs for ever, so time moves only when told to
                mainClock.autoAdvance = false
                var step by mutableStateOf(OnboardingStep.Welcome)
                var theme by mutableStateOf(AppTheme.Celadon)
                setContent {
                    DesktopTheme(theme) {
                        key(step) { Onboarding(app, onFinish = {}, start = step) }
                    }
                }
                fun shoot(name: String) {
                    mainClock.advanceTimeBy(900)
                    shots?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "onboarding-$name.png")) }
                }
                OnboardingStep.entries.forEach {
                    step = it
                    if (it == OnboardingStep.Videos) settings.updateLibraryRoot(File(dir, "Videos").path)
                    mainClock.advanceTimeBy(600)
                    assertTrue(it.name, onAllNodesWithText(headlines.getValue(it)).fetchSemanticsNodes().isNotEmpty())
                    shoot(it.name.lowercase())
                }
                // the peek step: a language with no OCR model says so; no peeking turns it off
                step = OnboardingStep.Peek
                mainClock.advanceTimeBy(600)
                assertTrue(onAllNodesWithText("Japanese").fetchSemanticsNodes().isEmpty())
                onNodeWithText("Thai").performClick()
                mainClock.advanceTimeBy(300)
                assertEquals("th", settings.peekLanguage)
                assertTrue(onAllNodesWithText("Thai ones need to be in the video", substring = true).fetchSemanticsNodes().isNotEmpty())
                assertTrue(onAllNodesWithText("can only be read in English and Japanese", substring = true).fetchSemanticsNodes().isNotEmpty())
                shoot("peek-thai")
                onNodeWithText("No peeking").performClick()
                mainClock.advanceTimeBy(300)
                assertNull(settings.peekLanguage)
                assertTrue(settings.peekLanguageChosen)
                onNodeWithText("English").performClick()
                theme = AppTheme.Jade
                step = OnboardingStep.Peek
                mainClock.advanceTimeBy(600)
                shoot("peek-dark")
                step = OnboardingStep.Dictionaries
                mainClock.advanceTimeBy(600)
                shoot("dictionaries-dark")
                step = OnboardingStep.Done
                mainClock.advanceTimeBy(100)
                shoot("done-dark")
            }
        } finally {
            Preferences.userRoot().node(node).removeNode()
            dir.deleteRecursively()
        }
    }

    @Test
    fun ankiStepSetsUpCards() {
        // the mpvacious note type is set up without asking; only the deck is picked
        ankiStep(listOf("Basic", "Japanese sentences"), listOf("Default", "Mining")) { settings ->
            assertTrue(onAllNodesWithText("Note type").fetchSemanticsNodes().isEmpty())
            assertEquals("Japanese sentences", settings.ankiNoteType)
            onNodeWithText("Choose…").performClick()
            mainClock.advanceTimeBy(300)
            onNodeWithText("Mining").performClick()
            mainClock.advanceTimeBy(300)
            assertEquals("Mining", settings.ankiDeck)
            val target = settings.cardTarget()
            assertEquals(CardSource.SentenceBold, target.format.fields["SentKanji"])
            shoot("anki-deck")
        }
        // a collection without it: the note type is asked for too
        ankiStep(listOf("Basic", "Vocab"), listOf("Default")) { settings ->
            assertNull(settings.ankiNoteType)
            onNodeWithText("Note type").assertExists()
            onAllNodesWithText("Choose…")[0].performClick()
            mainClock.advanceTimeBy(300)
            onNodeWithText("Vocab").performClick()
            mainClock.advanceTimeBy(300)
            waitFor { settings.ankiFormat != null }
            onNodeWithText("Choose…").performClick()
            mainClock.advanceTimeBy(300)
            onNodeWithText("Default").performClick()
            mainClock.advanceTimeBy(300)
            assertEquals("Default", settings.cardTarget().deck)
            shoot("anki-note-type")
        }
    }

    /** The Anki step against a stand-in AnkiConnect with [noteTypes] and [decks], cards turned on. */
    private fun ankiStep(noteTypes: List<String>, decks: List<String>, check: ComposeUiTest.(Settings) -> Unit) {
        val dir = Files.createTempDirectory("mizune-onboarding-anki").toFile()
        val node = "app/mizune-test-onboarding-anki"
        Preferences.userRoot().node(node).removeNode()
        val server = fakeAnki(noteTypes, decks)
        try {
            val settings = Settings(node).apply { updateAnkiUrl("http://127.0.0.1:${server.address.port}") }
            val app = DesktopApp(File(dir, ".data").apply { mkdirs() }, File(dir, ".cache"), settings)
            runDesktopComposeUiTest(width = 1100, height = 720) {
                mainClock.autoAdvance = false
                setContent { DesktopTheme(AppTheme.Celadon) { Onboarding(app, onFinish = {}, start = OnboardingStep.Anki) } }
                waitFor { onAllNodesWithText("Anki is open and ready").fetchSemanticsNodes().isNotEmpty() }
                // no deck to pick until cards are on
                assertTrue(onAllNodesWithText("Deck").fetchSemanticsNodes().isEmpty())
                settings.updateAnkiEnabled(true)
                waitFor { onAllNodesWithText("Deck").fetchSemanticsNodes().isNotEmpty() }
                check(settings)
            }
        } finally {
            server.stop(0)
            Preferences.userRoot().node(node).removeNode()
            dir.deleteRecursively()
        }
    }

    private fun ComposeUiTest.waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            mainClock.advanceTimeBy(100)
            Thread.sleep(20)
        }
    }

    private fun ComposeUiTest.shoot(name: String) {
        mainClock.advanceTimeBy(900)
        shots?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "onboarding-$name.png")) }
    }

    /** Answers the few AnkiConnect calls the welcome makes. */
    private fun fakeAnki(noteTypes: List<String>, decks: List<String>): HttpServer {
        val fields = mapOf(
            "Japanese sentences" to CardFormats.JapaneseSentencesPlus.fields.keys.toList(),
            "Vocab" to listOf("Word", "Meaning", "Sentence"),
        )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                val request = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                val result: Any = when (request.getString("action")) {
                    "version" -> 6
                    "deckNames" -> JSONArray(decks)
                    "modelNames" -> JSONArray(noteTypes)
                    "modelFieldNames" -> JSONArray(fields[request.getJSONObject("params").getString("modelName")].orEmpty())
                    else -> JSONObject.NULL
                }
                val body = JSONObject().put("result", result).put("error", JSONObject.NULL).toString().toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally { exchange.close() }
        }
        server.start()
        return server
    }
}
