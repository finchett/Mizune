package app.mizune.browser

import app.mizune.anki.AnkiBackend
import app.mizune.anki.AnkiException
import app.mizune.anki.CardMedia
import app.mizune.anki.CardSource
import app.mizune.anki.CardTarget
import app.mizune.anki.CardWriter
import app.mizune.anki.MinedWord
import app.mizune.boolean
import app.mizune.dictionary.DictionaryDatabase
import app.mizune.dictionary.DictionaryLookup
import app.mizune.dictionary.LookupResult
import app.mizune.jsonOf
import app.mizune.long
import app.mizune.number
import app.mizune.obj
import app.mizune.parseJson
import app.mizune.string
import app.mizune.subs.SubtitleEncoding
import app.mizune.subs.SubtitleFiles
import app.mizune.toJson
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64

/**
 * What the browser extension asks for (lookups, subtitle files, cards), answered the same way
 * by the Mizune app, when it's installed, and by the extension itself when it isn't. Messages
 * are JSON objects with a "type", in and out; an "id" comes back as it went.
 */
class ExtensionEngine(
    private val database: DictionaryDatabase,
    /**
     * How this runtime is set up: the app's own settings, or the extension's, which it sends as
     * a message's "settings" object (here as JSON, "{}" if there are none).
     */
    private val setup: (settings: String) -> Setup,
    private val logError: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val lookup = DictionaryLookup(database)

    /** Where the engine runs: the app's settings and video tools, or the extension's. */
    interface Setup {
        val ankiEnabled: Boolean

        /** The language being learned, a hint for reading subtitle files. */
        val targetLanguage: String

        /** Where cards go, or throws an [AnkiException] saying what still has to be set up. */
        fun cardTarget(): CardTarget

        fun anki(): AnkiBackend

        /** A card's media from what the page recorded. */
        fun media(attachments: Attachments): CardMedia

        /** The rest of what "hello" says: version, theme, keys and the player settings. */
        fun hello(): Map<String, Any?>
    }

    /** What the page sent for a card: the line's audio as the browser recorded it, and a still. */
    class Attachments(val audio: ByteArray?, val audioType: String?, val image: ByteArray?, val imageType: String?)

    /** Answers [message], a JSON object, with another. */
    fun handle(message: String): String {
        val json = parseJson(message) as? JsonObject ?: return failure("Mizune couldn't read that message").toString()
        val reply = try {
            handle(json)
        } catch (e: Exception) {
            logError("browser ${json.string("type")}", e)
            failure("Mizune couldn't do that: ${e.message}")
        }
        val id = json["id"]
        return (if (id != null) JsonObject(reply + ("id" to id)) else reply).toString()
    }

    private fun setup(message: JsonObject): Setup = setup((message["settings"] as? JsonObject ?: JsonObject(emptyMap())).toString())

    private fun handle(message: JsonObject): JsonObject = when (val type = message.string("type")) {
        "hello" -> hello(setup(message))
        "lookup" -> lookup(message.string("text"), message.int("start"), message.int("length"))
        "lookupLine" -> lookupLine(message.string("text"))
        "subtitles" -> subtitles(message, setup(message))
        "checkCard" -> checkCard(message, setup(message))
        "addCard" -> addCard(message, setup(message))
        "removeCard" -> removeCard(message["noteId"].long() ?: 0, setup(message))
        else -> failure("Mizune doesn't know \"$type\"; it may need updating")
    }

    private fun hello(setup: Setup): JsonObject = jsonOf(
        "type" to "hello",
        "protocol" to PROTOCOL,
        "dictionaries" to database.dictionaries().count { it.enabled },
        "anki" to setup.ankiEnabled,
    ).let { JsonObject(it + setup.hello().mapValues { (_, v) -> toJson(v) }) }

    /** A word at [start], or with [length] just the text dragged across. */
    private fun find(text: String, start: Int, length: Int): LookupResult? =
        if (length > 0) lookup.lookup(text, start, length) else lookup.lookup(text, start)

    /**
     * The word the study panel shows for a new line, as the app picks it: the first of the line's
     * likely places (the first kanji, or the next best) that has an entry.
     */
    private fun lookupLine(text: String): JsonObject {
        val positions = DictionaryLookup.defaultLookupPositions(text).take(16)
        val start = positions.firstOrNull { position -> lookup.lookup(text, position)?.entries?.isNotEmpty() == true }
        return lookup(text, start ?: positions.firstOrNull() ?: 0, 0)
    }

    private fun lookup(text: String, start: Int, length: Int): JsonObject {
        val result = find(text, start, length)
            ?: return jsonOf("type" to "lookup", "start" to start, "matchLength" to 0, "entries" to emptyList<Any>())
        // a native message from the app can be at most 1 MB; long dictionaries can come near that
        var entries = result.entries
        while (true) {
            val reply = jsonOf("type" to "lookup", "start" to start, "matchLength" to result.matchLength, "entries" to entriesJson(result.copy(entries = entries)))
            if (reply.toString().length < 900_000 || entries.size <= 1) return reply
            entries = entries.dropLast(1)
        }
    }

    private fun entriesJson(result: LookupResult) = result.entries.map { entry ->
        jsonOf(
            "expression" to entry.expression,
            "reading" to entry.reading,
            "length" to entry.sourceLength,
            "reasons" to entry.reasons,
            "pitch" to entry.pitchPositions,
            "frequencies" to entry.frequencies.map { mapOf("dictionary" to it.dictionaryTitle, "display" to it.display) },
            "definitions" to entry.definitions.map {
                mapOf("dictionary" to it.dictionaryTitle, "glossary" to it.glossaryJson, "definitionTags" to it.definitionTags, "termTags" to it.termTags)
            },
        )
    }

    /** A subtitle file the page found or the user dropped on it: its bytes, decoded and parsed as the app would. */
    private fun subtitles(message: JsonObject, setup: Setup): JsonObject {
        val name = message.string("name")
        val bytes = Base64.decode(message.string("data"))
        val text = SubtitleEncoding.decode(bytes, name, listOf(setup.targetLanguage))
        val track = SubtitleFiles.parseSidecar(name, text)
            ?: return failure("There are no lines in $name that Mizune can read")
        return jsonOf("type" to "subtitles", "cues" to track.cues.map { mapOf("start" to it.start, "end" to it.end, "text" to it.text) })
    }

    /** The word a card message names, where it'll go, and its entry's own form. */
    private class Card(val word: MinedWord, val target: CardTarget, val expression: String)

    private class Refused(val reply: JsonObject) : Exception()

    /** The [Card] the message names; throws [Refused] with the failure to answer with. */
    private fun card(message: JsonObject, setup: Setup): Card {
        if (!setup.ankiEnabled) throw Refused(failure("Turn on Anki cards in Mizune's settings first"))
        val target = try {
            setup.cardTarget().let { if (message["anyway"].boolean() == true) it.copy(allowDuplicates = true) else it }
        } catch (e: AnkiException) {
            throw Refused(failure(e.message ?: "Anki isn't set up in Mizune yet"))
        }
        val line = message.obj("line") ?: throw Refused(failure("The card has no line"))
        val sentence = line.string("text")
        val start = message.int("start")
        val result = find(sentence, start, message.int("length")) ?: throw Refused(failure("Nothing to look up there"))
        val expression = message.string("expression")
        val reading = message.string("reading")
        val entry = result.entries.firstOrNull { it.expression == expression && (reading.isEmpty() || it.reading == reading) }
            ?: throw Refused(failure("$expression isn't in the dictionaries any more"))
        val word = MinedWord(
            sentence = sentence,
            wordStart = start,
            wordLength = entry.sourceLength,
            entry = entry,
            translation = message.string("translation").ifEmpty { null },
            videoName = message.string("title").ifEmpty { "Browser" },
            start = line["start"].number() ?: 0.0,
            end = line["end"].number() ?: 0.0,
        )
        return Card(word, target, entry.expression)
    }

    private fun duplicate(e: CardWriter.Duplicate, card: Card) = jsonOf(
        "type" to "duplicate",
        "noteId" to e.noteId,
        "message" to "${card.expression} with this sentence is in Anki already",
    )

    /** Runs [block] for the card [message] names, answering Anki's refusals as the page expects them. */
    private fun withCard(message: JsonObject, setup: Setup, block: (Card) -> JsonObject): JsonObject {
        val card = try {
            card(message, setup)
        } catch (e: Refused) {
            return e.reply
        }
        return try {
            block(card)
        } catch (e: CardWriter.Duplicate) {
            duplicate(e, card)
        } catch (e: AnkiException) {
            failure(e.message ?: "Anki refused the card")
        }
    }

    /**
     * Whether the card can go in, before the page spends time recording for it, and which media
     * its note type has a field for.
     */
    private fun checkCard(message: JsonObject, setup: Setup) = withCard(message, setup) { card ->
        CardWriter(setup.anki()).check(card.word, card.target)
        val sources = card.target.format.fields.values.toSet()
        jsonOf("type" to "canAdd", "audio" to (CardSource.SentenceAudio in sources), "image" to (CardSource.Screenshot in sources))
    }

    private fun addCard(message: JsonObject, setup: Setup) = withCard(message, setup) { card ->
        val attachments = Attachments(
            audio = message.string("audio").ifEmpty { null }?.let { Base64.decode(it) },
            audioType = message.string("audioType").ifEmpty { null },
            image = message.string("image").ifEmpty { null }?.let { Base64.decode(it) },
            imageType = message.string("imageType").ifEmpty { null },
        )
        val added = CardWriter(setup.anki()).add(card.word, card.target, setup.media(attachments))
        val missing = added.missing.joinToString(" or ").ifEmpty { null }
        jsonOf("type" to "added", "noteId" to added.noteId, "message" to "Added ${card.expression} to Anki" + (missing?.let { " (no $it)" } ?: ""))
    }

    private fun removeCard(noteId: Long, setup: Setup): JsonObject = try {
        CardWriter(setup.anki()).remove(noteId)
        jsonOf("type" to "removed", "message" to "Removed the card from Anki")
    } catch (e: AnkiException) {
        failure(e.message ?: "Anki refused")
    }

    private fun JsonObject.int(key: String): Int = this[key].long()?.toInt() ?: 0

    companion object {
        /** Raised when the messages change in a way an older extension or app can't follow. */
        const val PROTOCOL = 1

        /** Card work waits for Anki and video tools; the app answers lookups beside it. */
        val SLOW = setOf("checkCard", "addCard", "removeCard")

        fun failure(message: String): JsonObject = jsonOf("type" to "error", "message" to message)
    }
}
