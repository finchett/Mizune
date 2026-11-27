package app.mizune.desktop

import app.mizune.anki.AnkiBackend
import app.mizune.anki.CardFormats
import app.mizune.anki.CardMedia
import app.mizune.anki.MinedWord
import app.mizune.anki.Picture
import app.mizune.dictionary.DictionaryDatabase
import app.mizune.dictionary.JdbcSql
import app.mizune.dictionary.YomitanImporter
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class BrowserHostTest {
    private val dir: File = Files.createTempDirectory("browser-host").toFile()
    private val node = "app/mizune-test-browser-host"

    @After fun cleanUp() {
        Preferences.userRoot().node(node).removeNode()
        dir.deleteRecursively()
    }

    private class FakeAnki : AnkiBackend {
        val notes = mutableMapOf<Long, Map<String, String>>()
        val media = mutableListOf<String>()
        override fun noteTypes() = listOf("Japanese sentences+")
        override fun fieldNames(noteType: String) = CardFormats.JapaneseSentencesPlus.fields.keys.toList()
        override fun decks() = listOf("Mining")
        override fun findNotes(deck: String, field: String, value: String) = notes.filterValues { it[field] == value }.keys.toList()
        override fun storeMedia(name: String, data: ByteArray): String = name.also { media += it }
        override fun addNote(deck: String, noteType: String, fields: Map<String, String>, tags: List<String>, allowDuplicate: Boolean): Long =
            (notes.size + 1L).also { notes[it] = fields }
        override fun deleteNotes(ids: List<Long>) { ids.forEach(notes::remove) }
    }

    private class Media : CardMedia {
        var audio: ByteArray? = null
        override fun audio(word: MinedWord): ByteArray = "ogg".encodeToByteArray()
        override fun image(word: MinedWord): Picture? = null
    }

    private val anki = FakeAnki()
    private val media = Media()

    private fun host(ankiOn: Boolean = true): BrowserHost {
        val settings = Settings(node)
        if (ankiOn) {
            settings.updateAnkiEnabled(true)
            settings.updateAnkiDeck("Mining")
            settings.updateAnkiNoteType("Japanese sentences+")
            settings.fillCardFormat("Japanese sentences+", CardFormats.JapaneseSentencesPlus.fields.keys.toList())
        }
        // a made-up dictionary of two words
        val database = DictionaryDatabase { JdbcSql("jdbc:sqlite:" + File(dir, "dictionaries.db").path) }
        YomitanImporter(database).import("test.zip", {}) { ByteArrayInputStream(dictionary()) }
        return BrowserHost(
            dataDir = dir,
            cacheDir = File(dir, "cache"),
            settings = { Settings(node) },
            anki = { anki },
            media = { _, attachments -> media.also { it.audio = attachments.audio } },
        )
    }

    private fun dictionary(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            listOf(
                "index.json" to """{"title":"Test","revision":"1","format":3}""",
                "term_bank_1.json" to """[["食べる","たべる","v1","v1",0,["to eat"],1,""],["駅","えき","n","",0,["station"],2,""]]""",
            ).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Sends [messages] through [BrowserHost.serve] as a browser would, and reads back every reply. */
    private fun exchange(host: BrowserHost, vararg messages: JSONObject): List<JSONObject> {
        val input = ByteArrayOutputStream().also { out -> messages.forEach { BrowserHost.write(out, it) } }
        val output = ByteArrayOutputStream()
        host.serve(ByteArrayInputStream(input.toByteArray()), output)
        // cards are answered from their own thread; give it a moment to finish
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val replies = readAll(output.toByteArray())
            if (replies.size == messages.size) return replies
            Thread.sleep(20)
        }
        return readAll(output.toByteArray())
    }

    private fun readAll(bytes: ByteArray): List<JSONObject> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        return buildList { while (input.available() > 0) add(BrowserHost.read(input)) }
    }

    @Test fun recognisesHowBrowsersStartIt() {
        assertTrue(BrowserHost.isLaunchedByBrowser(arrayOf("chrome-extension://${BrowserHost.CHROMIUM_ID}/")))
        assertTrue(BrowserHost.isLaunchedByBrowser(arrayOf("chrome-extension://${BrowserHost.CHROMIUM_ID}/", "--parent-window=0")))
        assertTrue(BrowserHost.isLaunchedByBrowser(arrayOf("/home/a/.mozilla/native-messaging-hosts/app.mizune.host.json", BrowserHost.GECKO_ID)))
        assertFalse(BrowserHost.isLaunchedByBrowser(arrayOf("/videos/Some Show 01.mkv")))
        assertFalse(BrowserHost.isLaunchedByBrowser(emptyArray()))
    }

    @Test fun helloThenLookupAnsweredInOrderWithTheirIds() {
        val replies = exchange(
            host(),
            JSONObject().put("type", "hello").put("id", "1"),
            JSONObject().put("type", "lookup").put("id", "2").put("text", "駅まで食べさせられなかった").put("start", 3),
            JSONObject().put("type", "nonsense").put("id", "3"),
        )
        assertEquals(listOf("1", "2", "3"), replies.map { it.getString("id") })
        val hello = replies[0]
        assertEquals(app.mizune.browser.ExtensionEngine.PROTOCOL, hello.getInt("protocol"))
        assertEquals(1, hello.getInt("dictionaries"))
        assertTrue(hello.getBoolean("anki"))
        assertEquals(listOf("A"), hello.getJSONObject("keys").getJSONArray("AddCard").map { it })
        assertTrue(hello.getJSONObject("theme").getJSONObject("dark").getString("primary").matches(Regex("#[0-9A-F]{6}")))

        val entry = replies[1].getJSONArray("entries").getJSONObject(0)
        assertEquals("食べる", entry.getString("expression"))
        assertEquals("食べさせられなかった".length, replies[1].getInt("matchLength"))
        assertTrue(entry.getJSONArray("definitions").getJSONObject(0).getString("glossary").contains("to eat"))
        assertEquals("error", replies[2].getString("type"))
    }

    @Test fun changesTheSettingsItSharesAndAnswersWithTheirHello() {
        val host = host(ankiOn = false)
        val change = JSONObject()
            .put("id", "7")
            .put("type", "updateSettings")
            .put("settings", JSONObject()
                .put("themeMode", "dark")
                .put("lineSize", 36)
                .put("peekLanguage", "")
                .put("copyLines", true)
                .put("keys", JSONObject().put("Peek", org.json.JSONArray(listOf("P"))).put("FillFit", org.json.JSONArray(listOf("Shift+Z", "Escape")))))
        val reply = exchange(host, change).single()
        assertEquals("7", reply.getString("id"))
        assertEquals("hello", reply.getString("type"))
        assertEquals("dark", reply.getJSONObject("theme").getString("mode"))
        assertEquals(36.0, reply.getDouble("lineSize"), 0.0)
        assertTrue(reply.isNull("peekLanguage"))
        assertTrue(reply.getBoolean("copyLines"))
        // Esc stays fixed, and P moved from wherever it was
        assertEquals(listOf("P"), reply.getJSONObject("keys").getJSONArray("Peek").map { it.toString() })
        assertEquals(listOf("Shift+Z"), reply.getJSONObject("keys").getJSONArray("FillFit").map { it.toString() })

        // the app's own settings, as the app reads them again
        val saved = Settings(node)
        saved.reloadShared()
        assertEquals(app.mizune.ui.ThemeMode.Dark, saved.themeMode)
        assertEquals(36f, saved.subtitleSize)
        assertEquals(null, saved.peekLanguage)
        assertTrue(saved.copyLines)
        assertEquals(listOf("P"), saved.keyMap.keysFor(PlayerAction.Peek).map { it.name })
        // keys the extension doesn't have are left as they were
        assertEquals(listOf("Space"), saved.keyMap.keysFor(PlayerAction.PlayPause).map { it.name })
    }

    @Test fun parsesSubtitleFilesTheAppWould() {
        val srt = "1\n00:00:01,000 --> 00:00:02,500\n駅まで\n\n2\n00:00:03,000 --> 00:00:04,000\n歩こう\n"
        val reply = host().handle(
            JSONObject().put("type", "subtitles").put("name", "Episode 1.ja.srt")
                .put("data", Base64.getEncoder().encodeToString(srt.toByteArray())),
        )
        val cues = reply.getJSONArray("cues")
        assertEquals(2, cues.length())
        assertEquals("駅まで", cues.getJSONObject(0).getString("text"))
        assertEquals(2.5, cues.getJSONObject(0).getDouble("end"), 0.001)
    }

    @Test fun addsACardWithTheRecordingThenCallsTheSecondADuplicate() {
        val host = host()
        val recording = byteArrayOf(1, 2, 3)
        fun card() = JSONObject().put("type", "addCard")
            .put("line", JSONObject().put("text", "駅まで食べさせられなかった").put("start", 12.0).put("end", 14.5))
            .put("start", 3).put("expression", "食べる").put("reading", "たべる")
            .put("title", "Made-up Show: Episode 1")
            .put("audio", Base64.getEncoder().encodeToString(recording)).put("audioType", "audio/webm")

        // checked first, so the page only records for a card that'll go in
        val check = host.handle(card().put("type", "checkCard"))
        assertEquals(check.toString(), "canAdd", check.getString("type"))
        assertTrue(check.getBoolean("audio"))
        assertTrue(check.getBoolean("image"))
        assertTrue(anki.notes.isEmpty())

        val added = host.handle(card())
        assertEquals(added.toString(), "added", added.getString("type"))
        assertTrue(recording.contentEquals(media.audio))
        val note = anki.notes.values.single()
        assertEquals("食べる", note["VocabKanji"])
        assertTrue(note["SentKanji"]!!.contains("<b>食べさせられなかった</b>"))
        assertTrue(anki.media.any { it.endsWith(".ogg") })

        assertEquals("duplicate", host.handle(card().put("type", "checkCard")).getString("type"))
        assertEquals("duplicate", host.handle(card()).getString("type"))
        assertEquals("canAdd", host.handle(card().put("type", "checkCard").put("anyway", true)).getString("type"))
        assertEquals("added", host.handle(card().put("anyway", true)).getString("type"))
    }

    @Test fun saysWhatToSetUpWhenAnkiIsOff() {
        val reply = host(ankiOn = false).handle(
            JSONObject().put("type", "addCard").put("line", JSONObject().put("text", "駅").put("start", 0.0).put("end", 1.0))
                .put("start", 0).put("expression", "駅"),
        )
        assertEquals("error", reply.getString("type"))
        assertTrue(reply.getString("message"), reply.getString("message").contains("Turn on Anki"))
    }

    /** The real conversion, with libmpv: needs ffmpeg to make a WebM like a browser's recording. */
    @Test fun browserMediaBecomesTheAppsOggAndAvif() {
        val ffmpeg = listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg").firstOrNull { File(it).canExecute() }
        assumeTrue(ffmpeg != null && System.getenv("MIZUNE_TEST_MPV") == null)
        val webm = File(dir, "line.webm")
        val made = ProcessBuilder(ffmpeg, "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-c:a", "libopus", webm.path)
            .redirectErrorStream(true).start().waitFor()
        assumeTrue(made == 0 && webm.length() > 0)
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

        val media = BrowserMedia(Settings(node), app.mizune.browser.ExtensionEngine.Attachments(webm.readBytes(), "audio/webm;codecs=opus", png, "image/png"), File(dir, "work"))
        val ogg = File(dir, "card.ogg")
        assertTrue(media.audio(dummyWord, ogg))
        assertTrue(ogg.readBytes().take(64).toByteArray().toString(Charsets.ISO_8859_1).let { it.startsWith("OggS") && "OpusHead" in it })
        val avif = media.image(dummyWord, File(dir, "card"))
        assertTrue(avif != null && avif.readBytes().take(32).toByteArray().toString(Charsets.ISO_8859_1).contains("ftypavif"))
    }

    private val dummyWord = MinedWord(
        "駅", 0, 1,
        app.mizune.dictionary.TermEntry("駅", "えき", 1, emptyList(), emptyList(), emptyList(), emptyList()),
        null, "Made-up Show", 0.0, 1.0,
    )
}
