package app.mizune.desktop

import app.mizune.anki.AnkiBackend
import app.mizune.anki.AnkiConnect
import app.mizune.anki.CardMedia
import app.mizune.anki.FileCardMedia
import app.mizune.anki.MinedWord
import app.mizune.browser.ExtensionEngine
import app.mizune.browser.ExtensionEngine.Attachments
import app.mizune.dictionary.DictionaryDatabase
import app.mizune.dictionary.JdbcSql
import app.mizune.ui.AppTheme
import app.mizune.ui.ThemeFamily
import app.mizune.ui.ThemeMode
import app.mizune.ui.retuned
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Mizune for the browser extension: Chrome and Firefox start the app with their own arguments
 * (see [isLaunchedByBrowser]) and talk to it over stdin and stdout, one JSON message at a time,
 * each after its length as a 32-bit integer in the machine's byte order. Lookups and cards use
 * the same dictionaries and Anki setup as the app.
 */
class BrowserHost(
    private val dataDir: File = AppDirs.data,
    private val cacheDir: File = AppDirs.cache,
    private val settings: () -> Settings = { Settings() },
    private val anki: (Settings) -> AnkiBackend = { AnkiConnect(it.ankiUrl) },
    private val media: (Settings, Attachments) -> CardMedia = { s, a -> BrowserMedia(s, a, File(AppDirs.cache, "browser")) },
) {
    private val database = DictionaryDatabase { JdbcSql("jdbc:sqlite:" + File(dataDir, "dictionaries.db").path) }

    /** The extension's own engine, set up from the app's settings and with its video tools for card media. */
    private val engine = ExtensionEngine(database, { AppSetup(settings()) }, ::logError)

    private inner class AppSetup(private val s: Settings) : ExtensionEngine.Setup {
        override val ankiEnabled get() = s.ankiEnabled
        override val targetLanguage get() = s.targetLanguage
        override fun cardTarget() = s.cardTarget()
        override fun anki() = anki(s)
        override fun media(attachments: Attachments) = media(s, attachments)
        override fun hello() = mapOf(
            "version" to APP_VERSION,
            "theme" to mapOf(
                "mode" to s.themeMode.name.lowercase(),
                "family" to s.themeFamily.name,
                "light" to s.themeFamily.light?.let(::colors),
                "dark" to s.themeFamily.dark?.let(::colors),
            ),
            "lineSize" to s.subtitleSize,
            "peekLanguage" to s.peekLanguage,
            "copyLines" to s.copyLines,
            "pauseOnLookup" to s.pauseOnLookup,
            "autoPause" to s.autoPause,
            "audioPadding" to s.ankiAudioPadding,
            "keys" to BROWSER_ACTIONS.associate { action -> action.name to s.keyMap.keysFor(action).map { it.name } },
        )
    }

    /** Reads messages until the browser closes the pipe. Lookups answer at once; cards queue behind each other. */
    fun serve(input: InputStream, output: OutputStream) {
        val reader = DataInputStream(input.buffered())
        val cards = Executors.newSingleThreadExecutor { Thread(it, "browser-cards").apply { isDaemon = true } }
        fun reply(message: JSONObject) {
            val answer = JSONObject(engine.handle(message.toString()))
            synchronized(output) { write(output, answer) }
        }
        while (true) {
            val message = try {
                read(reader)
            } catch (_: EOFException) {
                break
            }
            when (message.optString("type")) {
                "updateSettings" -> synchronized(output) { write(output, update(message)) }
                in ExtensionEngine.SLOW -> cards.execute { reply(message) }
                else -> reply(message)
            }
        }
        cards.shutdown()
    }

    fun handle(message: JSONObject): JSONObject =
        if (message.optString("type") == "updateSettings") update(message) else JSONObject(engine.handle(message.toString()))

    /**
     * The extension's options page changing the settings it shares with the app, when it's set
     * to: they're the app's own from then on, and the app reads them again as it comes forward.
     * Answers with "hello", as the settings now are.
     */
    private fun update(message: JSONObject): JSONObject {
        val changes = message.optJSONObject("settings") ?: JSONObject()
        val s = settings()
        changes.optString("themeFamily").takeIf { it.isNotEmpty() }?.let { name ->
            ThemeFamily.entries.firstOrNull { it.name == name }?.let(s::updateThemeFamily)
        }
        changes.optString("themeMode").takeIf { it.isNotEmpty() }?.let { mode ->
            ThemeMode.entries.firstOrNull { it.name.equals(mode, ignoreCase = true) }?.let(s::updateThemeMode)
        }
        if (changes.has("lineSize")) s.updateSubtitleSize(changes.getDouble("lineSize").toFloat().coerceIn(14f, 80f))
        if (changes.has("peekLanguage")) s.updatePeekLanguage(changes.optString("peekLanguage").ifEmpty { null })
        if (changes.has("pauseOnLookup")) s.updatePauseOnLookup(changes.getBoolean("pauseOnLookup"))
        if (changes.has("copyLines")) s.updateCopyLines(changes.getBoolean("copyLines"))
        if (changes.has("autoPause")) s.updateAutoPause(changes.getBoolean("autoPause"))
        changes.optJSONObject("keys")?.let { keys ->
            var map = s.keyMap
            for (action in BROWSER_ACTIONS) {
                val names = keys.optJSONArray(action.name) ?: continue
                repeat(map.keysFor(action).size) { map = map.unbind(action, 0) }
                for (i in 0 until names.length()) {
                    val binding = KeyBinding.parse(names.optString(i)) ?: continue
                    if (binding.fixed != null) continue
                    map = map.bind(action, map.keysFor(action).size, binding).first
                }
            }
            s.updateKeyMap(map)
        }
        runCatching { java.util.prefs.Preferences.userRoot().flush() }
        return JSONObject(engine.handle(JSONObject().put("type", "hello").put("id", message.opt("id")).toString()))
    }

    /** Into the app's own errors.log, as DesktopApp writes it. */
    private fun logError(context: String, error: Throwable) {
        runCatching {
            val file = File(dataDir, "errors.log")
            if (file.length() > 256 * 1024) file.delete()
            file.appendText("${java.util.Date()} $context\n${error.stackTraceToString()}\n")
        }
    }

    companion object {
        /** [theme]'s colours as the app draws with them (retuned), for the extension to draw with too. */
        fun colors(theme: AppTheme): Map<String, String> {
            val scheme = theme.scheme.retuned()
            fun hex(color: androidx.compose.ui.graphics.Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)
            return mapOf(
                "primary" to hex(scheme.primary),
                "onPrimary" to hex(scheme.onPrimary),
                "secondary" to hex(scheme.secondary),
                "tertiary" to hex(scheme.tertiary),
                "background" to hex(scheme.background),
                "surface" to hex(scheme.surface),
                "surfaceVariant" to hex(scheme.surfaceVariant),
                "onSurface" to hex(scheme.onSurface),
                "onSurfaceVariant" to hex(scheme.onSurfaceVariant),
                "error" to hex(scheme.error),
            )
        }

        /** The extension's ID in Chrome and other Chromium browsers, fixed by the key in its manifest. */
        const val CHROMIUM_ID = "jflnhljpbghcbndkpaaajcblakcndogh"

        /** The extension's ID in Firefox. */
        const val GECKO_ID = "extension@mizune.app"

        /** The player's keys that also work in the browser; the site keeps the rest (play, full screen). */
        val BROWSER_ACTIONS = listOf(
            PlayerAction.PreviousLine, PlayerAction.NextLine, PlayerAction.PreviousLineStop,
            PlayerAction.NextLineStop, PlayerAction.ReplayLine, PlayerAction.SubtitlesEarlier,
            PlayerAction.SubtitlesLater, PlayerAction.StopEveryLine, PlayerAction.Panel, PlayerAction.AddCard,
            PlayerAction.Peek, PlayerAction.FillFit, PlayerAction.SeekBack, PlayerAction.SeekForward,
        )

        /**
         * Whether the browser started the app: Chrome passes the extension's origin (and on
         * Windows a --parent-window), Firefox the host manifest's path and the add-on's ID.
         */
        fun isLaunchedByBrowser(args: Array<String>): Boolean =
            args.any { it.startsWith("chrome-extension://") } ||
                (args.size >= 2 && args[1] == GECKO_ID && args[0].endsWith(".json"))

        /** Runs as the browser's host until it goes away. Stdout carries only messages from here on. */
        fun main() {
            val out = FileOutputStream(FileDescriptor.out)
            System.setOut(System.err)
            if (System.getProperty("os.name").lowercase().contains("mac")) unpackLibraries(File(AppDirs.cache, "browser-libraries"))
            BrowserHost().serve(System.`in`, out)
        }

        /**
         * On macOS a browser starts its hosts with quarantine on, so every file they write is
         * marked as downloaded, and macOS won't load a marked library that isn't notarized.
         * sqlite-jdbc and JNA unpack theirs into the temp folder as they start; here they're
         * unpacked first, the mark taken off, and both pointed at them.
         */
        internal fun unpackLibraries(dir: File) {
            val arch = if (System.getProperty("os.arch") == "aarch64") "aarch64" else "x86_64"
            val libraries = listOf(
                "org/sqlite/native/Mac/$arch/libsqlitejdbc.dylib",
                "com/sun/jna/darwin-${arch.replace('_', '-')}/libjnidispatch.jnilib",
            )
            runCatching {
                dir.mkdirs()
                val files = libraries.map { path ->
                    val bytes = BrowserHost::class.java.classLoader.getResourceAsStream(path)!!.use { it.readBytes() }
                    File(dir, path.substringAfterLast('/')).apply { if (!isFile || !readBytes().contentEquals(bytes)) writeBytes(bytes) }
                }
                // xattr fails for a file that has no mark, which is fine
                ProcessBuilder(listOf("/usr/bin/xattr", "-d", "com.apple.quarantine") + files.map { it.path })
                    .redirectErrorStream(true).start().run { inputStream.readBytes(); waitFor() }
                System.setProperty("org.sqlite.lib.path", dir.path)
                System.setProperty("org.sqlite.lib.name", "libsqlitejdbc.dylib")
                System.setProperty("jna.boot.library.path", dir.path)
            }.onFailure { System.err.println("Couldn't unpack the native libraries: $it") }
        }

        internal fun read(input: DataInputStream): JSONObject {
            val header = ByteArray(4)
            input.readFully(header)
            val length = ByteBuffer.wrap(header).order(ByteOrder.nativeOrder()).int
            require(length in 0..(64 shl 20)) { "message of $length bytes" }
            val body = ByteArray(length)
            input.readFully(body)
            return JSONObject(String(body, Charsets.UTF_8))
        }

        internal fun write(output: OutputStream, message: JSONObject) {
            val body = message.toString().toByteArray(Charsets.UTF_8)
            output.write(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(body.size).array())
            output.write(body)
            output.flush()
        }
    }
}

/**
 * A card's media from what the browser sent: its recording of the line, turned into the same
 * mono Opus as the app's own clips, and its still, made into an AVIF like the app's screenshots.
 */
internal class BrowserMedia(
    private val settings: Settings,
    private val attachments: Attachments,
    private val workDir: File,
) : FileCardMedia(workDir) {
    override fun audio(word: MinedWord, out: File): Boolean {
        val bytes = attachments.audio ?: return false
        val extension = if (attachments.audioType.orEmpty().contains("ogg")) "ogg" else "webm"
        val recording = File(workDir.apply { mkdirs() }, "${out.nameWithoutExtension}.recording.$extension")
        return try {
            recording.writeBytes(bytes)
            // the recording is the line already, padding and all
            VideoClips.audioClip(recording, 0.0, 600.0, null, out)
        } finally {
            recording.delete()
        }
    }

    override fun image(word: MinedWord, base: File): File? {
        val bytes = attachments.image ?: return null
        val still = File(workDir.apply { mkdirs() }, "${base.name}.still.png")
        val out = File(base.path + ".avif")
        return try {
            still.writeBytes(bytes)
            out.takeIf { VideoClips.screenshot(still, 0.0, settings.ankiImageHeight, out) }
        } finally {
            still.delete()
        }
    }
}
