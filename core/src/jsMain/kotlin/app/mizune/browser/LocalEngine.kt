package app.mizune.browser

import app.mizune.anki.AnkiBackend
import app.mizune.anki.AnkiConnect
import app.mizune.anki.AnkiException
import app.mizune.anki.CardFormat
import app.mizune.anki.CardFormats
import app.mizune.anki.CardSource
import app.mizune.anki.CardMedia
import app.mizune.anki.CardTarget
import app.mizune.anki.MinedWord
import app.mizune.anki.OggOpus
import app.mizune.anki.Picture
import app.mizune.boolean
import app.mizune.dictionary.Definition
import app.mizune.dictionary.DictionaryCatalog
import app.mizune.dictionary.DictionaryDatabase
import app.mizune.dictionary.DictionaryLookup
import app.mizune.dictionary.SqliteWasmSql
import app.mizune.dictionary.TermEntry
import app.mizune.dictionary.YomitanImporter
import app.mizune.dictionary.ZipBytes
import app.mizune.jsonOf
import app.mizune.long
import app.mizune.number
import app.mizune.obj
import app.mizune.parseJson
import app.mizune.string
import kotlinx.serialization.json.JsonObject
import org.khronos.webgl.Uint8Array

/**
 * The extension's engine when the Mizune app isn't installed: the app's [ExtensionEngine] over
 * dictionaries kept in the browser, with Anki reached straight through AnkiConnect. It runs in a
 * worker; [db] is the SQLite database there (see [SqliteWasmSql]).
 *
 * The extension's own settings come with each message, as "settings": anki (on or off),
 * ankiUrl, deck, noteType, cardFormats, tags, warnDuplicates and targetLanguage, and what "hello" passes on
 * to the page (lineSize, peekLanguage, pauseOnLookup, autoPause, copyLines, audioPadding, imageHeight, imageAnimated, keys).
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
class LocalEngine(db: dynamic) {
    private val database = DictionaryDatabase { SqliteWasmSql(db) }
    private val engine = ExtensionEngine(database, { BrowserSetup(parseJson(it) as? JsonObject ?: JsonObject(emptyMap())) })

    init {
        // an import the worker didn't live to finish
        database.deleteIncomplete()
    }

    private class BrowserSetup(private val settings: JsonObject) : ExtensionEngine.Setup {
        override val ankiEnabled get() = settings["anki"].boolean() == true
        override val targetLanguage get() = settings.string("targetLanguage", "ja")

        override fun anki(): AnkiBackend = AnkiConnect(settings.string("ankiUrl").ifEmpty { AnkiConnect.DEFAULT_URL })

        override fun cardTarget(): CardTarget {
            val noteType = settings.string("noteType").ifEmpty { throw AnkiException("Choose a note type in Mizune's options") }
            val deck = settings.string("deck").ifEmpty { throw AnkiException("Choose a deck in Mizune's options") }
            val format = cardFormat(settings, noteType, anki().fieldNames(noteType))
            val tags = settings.string("tags", "mizune").split(' ', '　').filter { it.isNotBlank() }
            return CardTarget(format, deck, tags, allowDuplicates = settings["warnDuplicates"].boolean() == false)
        }

        /** What the page recorded, as it is: the browser's Opus put in Ogg, and its picture. */
        override fun media(attachments: ExtensionEngine.Attachments) = object : CardMedia {
            override fun audio(word: MinedWord): ByteArray? = attachments.audio?.let(OggOpus::from)

            override fun image(word: MinedWord): Picture? {
                val data = attachments.image ?: return null
                val extension = when (attachments.imageType) {
                    "image/jpeg" -> "jpg"
                    "image/png" -> "png"
                    else -> "webp"
                }
                return Picture(extension, data)
            }
        }

        override fun hello(): Map<String, Any?> = buildMap {
            put("version", "browser")
            put("local", true)
            for (key in listOf("lineSize", "audioPadding", "imageHeight")) settings[key].number()?.let { put(key, it) }
            for (key in listOf("pauseOnLookup", "autoPause", "copyLines", "imageAnimated")) settings[key].boolean()?.let { put(key, it) }
            // empty while peeking's off
            if (settings.containsKey("peekLanguage")) put("peekLanguage", settings.string("peekLanguage").ifEmpty { null })
            settings.obj("keys")?.let { put("keys", it) }
        }
    }

    /** Answers a message from the extension, JSON in and out, as the app does. */
    fun handle(message: String): String {
        val json = parseJson(message) as? JsonObject
        val own = json?.let { handleOwn(it) } ?: return engine.handle(message)
        return (json["id"]?.let { JsonObject(own + ("id" to it)) } ?: own).toString()
    }

    /** What only the extension asks its own engine: its dictionaries, and Anki's choices for its options. */
    private fun handleOwn(message: JsonObject): JsonObject? = try {
        val settings = message.obj("settings") ?: JsonObject(emptyMap())
        when (message.string("type")) {
            "dictionaries" -> dictionaries()
            "catalog" -> catalog()
            "enableDictionary" -> {
                database.setEnabled(message["id"].long() ?: 0, message["enabled"].boolean() == true)
                dictionaries()
            }
            "moveDictionary" -> {
                database.move(message["id"].long() ?: 0, (message["direction"].long() ?: 0).toInt())
                dictionaries()
            }
            "deleteDictionary" -> {
                database.delete(message["id"].long() ?: 0)
                dictionaries()
            }
            "anki" -> anki(BrowserSetup(settings).anki() as AnkiConnect, settings, message.string("noteType"), message.string("translation"))
            "ankiPermission" -> jsonOf("type" to "ankiPermission", "granted" to (BrowserSetup(settings).anki() as AnkiConnect).requestPermission())
            else -> null
        }
    } catch (e: AnkiException) {
        ExtensionEngine.failure(e.message ?: "Couldn't reach Anki")
    }

    private fun dictionaries() = jsonOf(
        "type" to "dictionaries",
        "dictionaries" to database.dictionaries().map {
            mapOf("id" to it.id, "title" to it.title, "revision" to it.revision, "terms" to it.termCount, "meta" to it.metaCount, "enabled" to it.enabled)
        },
    )

    /** The dictionaries the app offers to download, and which of them are in. */
    private fun catalog(): JsonObject {
        val installed = database.dictionaries()
        return jsonOf("type" to "catalog", "entries" to DictionaryCatalog.ENTRIES.map {
            mapOf(
                "id" to it.id, "name" to it.name, "description" to it.description, "url" to it.url,
                "megabytes" to it.megabytes, "recommended" to it.recommended,
                "installed" to DictionaryCatalog.installed(it, installed)?.id,
            )
        })
    }

    /**
     * Anki's decks and note types, the note type to start with if none's chosen, and [noteType]'s
     * fields with what fills each, for the options page.
     */
    private fun anki(anki: AnkiConnect, settings: JsonObject, noteType: String, translation: String): JsonObject {
        val noteTypes = anki.noteTypes()
        val fields = noteType.ifEmpty { null }?.let { anki.fieldNames(it) }
        val format = fields?.let { cardFormat(settings, noteType, it) }
        return jsonOf(
            "type" to "anki",
            "version" to anki.version(),
            "decks" to anki.decks(),
            "noteTypes" to noteTypes,
            "pick" to CardFormats.pick(noteTypes),
            "fields" to fields,
            "format" to format?.fields?.mapValues { it.value.name },
            "preset" to fields?.let { names -> CardFormats.known(names) != null },
            "suggested" to fields?.let { names -> CardFormats.forNoteType(noteType, names).fields.mapValues { it.value.name } },
            "sources" to CardSource.entries.map { mapOf("name" to it.name, "label" to it.label) },
            "look" to format?.let { look(anki, it, translation) },
        )
    }

    /**
     * How a card of [format] looks, for the options' preview: the note type's own templates and
     * CSS, and its fields filled as a card's would be, from a word looked up in the dictionaries
     * there are (or a stand-in), with [SAMPLE_AUDIO] and [SAMPLE_IMAGE] for its media. Null if
     * this AnkiConnect can't say.
     */
    private fun look(anki: AnkiConnect, format: CardFormat, translation: String): Map<String, Any?>? = try {
        val found = runCatching { DictionaryLookup(database).lookup(SAMPLE_SENTENCE, SAMPLE_START) }.getOrNull()
            ?.takeIf { it.entries.isNotEmpty() }
        val entry = found?.entries?.first() ?: SAMPLE_ENTRY
        val word = MinedWord(
            SAMPLE_SENTENCE, SAMPLE_START, found?.matchLength ?: 2, entry, translation.ifEmpty { null },
            "Show 01", 83.765, 85.345, audioFile = SAMPLE_AUDIO, imageFile = SAMPLE_IMAGE,
        )
        mapOf(
            "templates" to anki.templates(format.noteType).map { mapOf("name" to it.name, "front" to it.front, "back" to it.back) },
            "css" to anki.styling(format.noteType),
            "fields" to word.fields(format),
        )
    } catch (e: AnkiException) {
        null
    }

    /**
     * Imports a Yomitan dictionary zip, telling [onProgress] how far it's got (JSON: file,
     * terms, meta), and answers with the dictionaries or what went wrong.
     */
    fun importDictionary(name: String, data: Uint8Array, onProgress: (String) -> Unit): String = try {
        val info = YomitanImporter(database).import(name, { onProgress(jsonOf("file" to it.file, "terms" to it.terms, "meta" to it.meta).toString()) }) { ZipBytes(data) }
        jitendexTakesOver(info.id)
        JsonObject(dictionaries() + ("imported" to jsonOf("title" to info.title, "terms" to info.termCount, "meta" to info.metaCount))).toString()
    } catch (e: Exception) {
        ExtensionEngine.failure(e.message ?: "Couldn't import $name").toString()
    }

    /**
     * As in the apps: Jitendex goes first in the results, in place of the JMdict it builds on, which
     * is turned off; and a JMdict that comes after Jitendex starts off.
     */
    private fun jitendexTakesOver(imported: Long) {
        val all = database.dictionaries()
        val title = all.firstOrNull { it.id == imported }?.title ?: return
        if (title.startsWith(DictionaryCatalog.JITENDEX.titlePrefix)) {
            repeat(all.indexOfFirst { it.id == imported }) { database.move(imported, -1) }
            all.filter { it.title.startsWith(DictionaryCatalog.JMDICT_PREFIX) }.forEach { database.setEnabled(it.id, false) }
        } else if (title.startsWith(DictionaryCatalog.JMDICT_PREFIX) && all.any { it.title.startsWith(DictionaryCatalog.JITENDEX.titlePrefix) }) {
            database.setEnabled(imported, false)
        }
    }
}

/**
 * What fills each of [noteType]'s fields: as saved in the options ("cardFormats", by note type)
 * where it still fits, otherwise suggested, as the app fills them.
 */
private fun cardFormat(settings: JsonObject, noteType: String, fieldNames: List<String>): CardFormat {
    val saved = settings.obj("cardFormats")?.obj(noteType)?.let { CardFormats.upgrade(CardFormat.fromJson(noteType, it.toString())) }
    return CardFormats.fill(saved, noteType, fieldNames)
}

private const val SAMPLE_SENTENCE = "今日はいい天気ですね"
/** Where 天気 starts in it. */
private const val SAMPLE_START = 5
/** The media a sample card names, which the preview puts its own pictures in for. */
private const val SAMPLE_AUDIO = "mizune-sample.ogg"
private const val SAMPLE_IMAGE = "mizune-sample.webp"

/** 天気, for a preview when no dictionary has it. */
private val SAMPLE_ENTRY = TermEntry(
    "天気", "てんき", 2, emptyList(),
    listOf(
        Definition("Sample", "[\"weather\",\"the elements\"]", "n", ""),
        Definition("Sample", "[\"fair weather\",\"fine weather\"]", "n", ""),
    ),
    emptyList(), listOf(1),
)
