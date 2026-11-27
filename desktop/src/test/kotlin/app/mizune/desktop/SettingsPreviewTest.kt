package app.mizune.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.mizune.anki.CardFormat
import app.mizune.anki.CardSource
import app.mizune.dictionary.YomitanImporter
import app.mizune.ui.AppTheme
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.prefs.Preferences
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * The preview beside each settings pane, in a light theme and a dark one, with the Anki card
 * drawn from a stand-in AnkiConnect's note type. MIZUNE_TEST_SHOTS keeps a picture of each.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsPreviewTest {
    private val shots = System.getenv("MIZUNE_TEST_SHOTS")?.let(::File)?.apply { mkdirs() }
    private val node = "app/mizune-test-previews"

    /** AnkiConnect with one deck and a made-up note type, whose cards have a script, furigana and a hint. */
    private class FakeAnkiConnect {
        val noteType = "Test sentences"
        val fields = listOf("Sentence", "Word", "Reading", "Meaning", "Picture", "Audio")
        private val front = """<div class="sentence">{{Sentence}}</div><script>document.body.classList.add("ran")</script>"""
        private val back = """{{FrontSide}}
            <hr id=answer>
            <div class="word">{{furigana:Word}}</div>
            {{#Meaning}}<div class="meaning">{{Meaning}}</div>{{/Meaning}}
            <div class="media">{{Picture}}{{Audio}}</div>
            <div class="reading">{{hint:Reading}}</div>"""
        private val css = """
            :root { --accent: #c2410c; }
            .card { font-family: "Hiragino Mincho ProN", "Yu Mincho", serif; font-size: 22px; text-align: center; color: #222; background-color: #fdfbf5; }
            .card.nightMode { color: #eeeeee; background-color: #20242b; }
            .sentence b { color: var(--accent); }
            .word { font-size: 1.6em; margin: 6px 0 10px; }
            .meaning { font-size: 15px; text-align: left; border-left: 3px solid var(--accent); padding-left: 10px; margin: 0 auto 12px; max-width: 320px; }
            .meaning ol { margin: 0; padding-left: 20px; }
            .media { display: flex; justify-content: center; align-items: center; gap: 8px; }
            .media img { max-width: 220px; border-radius: 6px; }
            .reading { font-size: 14px; margin-top: 8px; }
            a.hint { color: var(--accent); }
        """
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val request = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                val result: Any = when (request.getString("action")) {
                    "version" -> 6
                    "deckNames" -> JSONArray(listOf("Mining"))
                    "modelNames" -> JSONArray(listOf("Basic", noteType))
                    "modelFieldNames" -> JSONArray(fields)
                    "modelTemplates" -> JSONObject().put("Card 1", JSONObject().put("Front", front).put("Back", back))
                    "modelStyling" -> JSONObject().put("css", css)
                    else -> error("unexpected ${request.getString("action")}")
                }
                val body = JSONObject().put("result", result).put("error", JSONObject.NULL).toString().toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"
        fun stop() = server.stop(0)
    }

    /** A Yomitan zip with 天気 in it, so the lookup and the card have a word to show. */
    private fun dictionaryZip(title: String, terms: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
            }
            put("index.json", """{"title":"$title","revision":"1","format":3}""")
            if (terms) put("term_bank_1.json", """[["天気","てんき","n","",0,["weather","the elements"],1,""],["今日","きょう","n","",0,["today"],2,""]]""")
            else put("term_meta_bank_1.json", """[["天気","freq",{"value":812,"displayValue":"812"}]]""")
        }
        return out.toByteArray()
    }

    /** Lets the clock and what's off on other threads (AnkiConnect, the dictionaries) catch up. */
    private fun ComposeUiTest.settle(ms: Long = 600, until: () -> Boolean = { true }) {
        val deadline = System.currentTimeMillis() + 10_000
        var waited = 0L
        while (waited < ms || !until()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            mainClock.advanceTimeBy(50)
            Thread.sleep(10)
            waited += 50
        }
    }

    private fun ComposeUiTest.has(text: String, substring: Boolean = false) =
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun eachPaneHasItsPreview() {
        val dir = Files.createTempDirectory("mizune-previews").toFile()
        Preferences.userRoot().node(node).removeNode()
        val anki = FakeAnkiConnect()
        val settings = Settings(node).apply {
            updateLibraryRoot(dir.path)
            updateAnkiEnabled(true)
            updateAnkiUrl(anki.url)
            updateAnkiNoteType(anki.noteType)
            updateAnkiDeck("Mining")
            updateAnkiTags("mizune sentences")
            updateAnkiImageAnimated(false)
            saveCardFormat(
                CardFormat(
                    anki.noteType,
                    mapOf(
                        "Sentence" to CardSource.SentenceBold, "Word" to CardSource.WordFurigana, "Reading" to CardSource.Reading,
                        "Meaning" to CardSource.Definition, "Picture" to CardSource.Screenshot, "Audio" to CardSource.SentenceAudio,
                    ),
                ),
            )
        }
        val app = DesktopApp(File(dir, ".data").apply { mkdirs() }, File(dir, ".cache"), settings)
        YomitanImporter(app.database).import("Sample terms.zip", {}) { dictionaryZip("Sample terms", true).inputStream() }
        YomitanImporter(app.database).import("Sample counts.zip", {}) { dictionaryZip("Sample counts", false).inputStream() }
        try {
            runDesktopComposeUiTest(width = 1440, height = 900) {
                // the scenes move for ever, so time moves only when told to
                mainClock.autoAdvance = false
                var pane by mutableStateOf(SettingsPane.General)
                var theme by mutableStateOf(AppTheme.Celadon)
                setContent {
                    DesktopTheme(theme) { SettingsChrome(app, pane, onPane = { pane = it }, onBack = {}) }
                }
                settle { has("Preview") }
                for (t in listOf(AppTheme.Celadon, AppTheme.Jade)) {
                    theme = t
                    for (entry in SettingsPane.entries) {
                        pane = entry
                        when (entry) {
                            SettingsPane.Dictionaries -> settle { has("Sample terms") && has("Sample counts") }
                            SettingsPane.Anki -> settle { has("Drawn with", substring = true) && has("Front".uppercase()) }
                            else -> settle()
                        }
                        // About has nothing to preview
                        assertTrue(entry.name, has("Preview") == (entry != SettingsPane.About))
                        shots?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "preview-${entry.name.lowercase()}-${t.name.lowercase()}.png")) }
                    }
                }
                // the card's scripts don't run, and its caption says so
                pane = SettingsPane.Anki
                settle { has("Drawn with", substring = true) }
                assertTrue(has("Its scripts don't run in this preview.", substring = true))
                // a hint opens on a click
                onNodeWithText("Show Reading", substring = true).performMouseInput { click(center) }
                settle(300)
                assertTrue(has("てんき", substring = true))
                shots?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "preview-anki-hint.png")) }

                // a keyboard row pointed at lights its keys, and the caption names them
                theme = AppTheme.Celadon
                pane = SettingsPane.Keyboard
                settle()
                onNodeWithText("Play / pause").performMouseInput { moveTo(center) }
                settle(300)
                assertTrue(has("Space: Play / pause"))
                shots?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "preview-keyboard-row.png")) }

                // the player stopped at a line's end, having copied it
                settings.updateAutoPause(true)
                settings.updateCopyLines(true)
                pane = SettingsPane.Playback
                settle(1900)
                shots?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "preview-playback-stopped.png")) }
                // the peek line, held
                pane = SettingsPane.General
                settle(3000)
                shots?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "preview-general-peeking.png")) }
                // with AnkiConnect not answering, Anki's plain look; with cards off, the card greyed
                pane = SettingsPane.Anki
                settings.updateAnkiUrl("http://127.0.0.1:9")
                settle { has("Anki's plain look", substring = true) }
                shots?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "preview-anki-plain.png")) }
                settings.updateAnkiEnabled(false)
                settle { has("Anki cards are off.") }
                shots?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "preview-anki-off.png")) }
            }
            // without the room, no preview
            runDesktopComposeUiTest(width = 1100, height = 760) {
                mainClock.autoAdvance = false
                setContent { DesktopTheme(AppTheme.Celadon) { SettingsChrome(app, SettingsPane.Appearance, onPane = {}, onBack = {}) } }
                settle()
                assertTrue(!has("Preview"))
                // and the size's own preview is back under its slider
                shots?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "preview-narrow.png")) }
            }
        } finally {
            anki.stop()
            Preferences.userRoot().node(node).removeNode()
        }
    }
}
