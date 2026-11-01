package app.mizune.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.mizune.anki.CardFormats
import app.mizune.anki.CardMedia
import app.mizune.anki.MinedWord
import app.mizune.dictionary.Definition
import app.mizune.dictionary.TermEntry
import app.mizune.ui.AppTheme
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.prefs.Preferences
import javax.imageio.ImageIO

/**
 * A card the deck already has asks first, against a stand-in for AnkiConnect: cancel, add it
 * anyway, or never warn again. MIZUNE_TEST_SHOTS keeps a picture of the dialog.
 */
@OptIn(ExperimentalTestApi::class)
class DuplicateCardTest {
    private val node = "app/mizune-test-duplicates"
    private val shots = System.getenv("MIZUNE_TEST_SHOTS")?.let(::File)?.apply { mkdirs() }

    /** AnkiConnect with one deck and the Sentences+ note type; notes are their first fields, in order. */
    private class FakeAnkiConnect {
        val notes = mutableListOf<String>()
        val first = CardFormats.JapaneseSentencesPlus.fields.keys.first()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val request = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                val params = request.optJSONObject("params") ?: JSONObject()
                val result: Any = synchronized(notes) {
                    when (request.getString("action")) {
                        "deckNames" -> JSONArray(listOf("Mining"))
                        "modelFieldNames" -> JSONArray(CardFormats.JapaneseSentencesPlus.fields.keys.toList())
                        "canAddNotesWithErrorDetail" -> {
                            val note = params.getJSONArray("notes").getJSONObject(0)
                            val taken = note.getJSONObject("fields").getString(first) in notes
                            JSONArray().put(
                                if (taken) JSONObject().put("canAdd", false).put("error", "cannot create note because it is a duplicate")
                                else JSONObject().put("canAdd", true),
                            )
                        }
                        "findNotes" -> JSONArray(notes.indices.filter { "\"$first:${notes[it]}\"" in params.getString("query") }.map { it + 1L })
                        "addNote" -> {
                            val note = params.getJSONObject("note")
                            val value = note.getJSONObject("fields").getString(first)
                            check(value !in notes || note.getJSONObject("options").getBoolean("allowDuplicate")) { "duplicate added" }
                            notes += value
                            notes.size.toLong()
                        }
                        else -> error("unexpected ${request.getString("action")}")
                    }
                }
                val body = JSONObject().put("result", result).put("error", JSONObject.NULL).toString().toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"
        fun count() = synchronized(notes) { notes.size }
        fun stop() = server.stop(0)
    }

    private object NoMedia : CardMedia {
        override fun audio(word: MinedWord, out: File) = false
        override fun image(word: MinedWord, base: File): File? = null
    }

    private val word = MinedWord(
        sentence = "今日は何を食べる", wordStart = 0, wordLength = 2,
        entry = TermEntry("今日", "きょう", 1, emptyList(), listOf(Definition("Test", """["today"]""", "n", "")), emptyList(), emptyList()),
        translation = null, videoName = "Show 01", start = 1.0, end = 3.0,
    )

    @Test
    fun asksThenCancelsAddsAnywayOrStopsAsking() = runDesktopComposeUiTest(width = 900, height = 600) {
        val anki = FakeAnkiConnect()
        Preferences.userRoot().node(node).removeNode()
        val settings = Settings(node).apply {
            updateAnkiUrl(anki.url)
            updateAnkiDeck("Mining")
            updateAnkiNoteType(CardFormats.JapaneseSentencesPlus.noteType)
            saveCardFormat(CardFormats.JapaneseSentencesPlus)
        }
        val dir = Files.createTempDirectory("duplicates").toFile()
        val app = DesktopApp(File(dir, "data").apply { mkdirs() }, File(dir, "cache"), settings)
        try {
            setContent { DesktopTheme(AppTheme.Jade) { DuplicateCardDialog(app) } }
            fun add() = app.anki.add(word, NoMedia)
            fun asked() = app.anki.duplicate.value != null

            add()
            waitUntil(timeoutMillis = 5_000) { anki.count() == 1 }
            assertFalse("a new card isn't asked about", asked())

            add()
            waitUntil(timeoutMillis = 5_000) { asked() }
            onNodeWithText("You already have this").assertExists()
            shots?.let { ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(it, "duplicate-card.png")) }
            onNodeWithText("Cancel").performClick()
            waitForIdle()
            assertFalse(asked())
            assertEquals(1, anki.count())

            add()
            waitUntil(timeoutMillis = 5_000) { asked() }
            onNodeWithText("Add it anyway").performClick()
            waitUntil(timeoutMillis = 5_000) { anki.count() == 2 }
            assertTrue("still warns next time", settings.ankiWarnDuplicates)

            add()
            waitUntil(timeoutMillis = 5_000) { asked() }
            onNodeWithText("Never warn me").performClick()
            waitUntil(timeoutMillis = 5_000) { anki.count() == 3 }
            assertFalse(settings.ankiWarnDuplicates)

            add()
            waitUntil(timeoutMillis = 5_000) { anki.count() == 4 }
            waitForIdle()
            assertEquals(0, onAllNodesWithText("You already have this").fetchSemanticsNodes().size)
        } finally {
            anki.stop()
            Preferences.userRoot().node(node).removeNode()
        }
    }
}
