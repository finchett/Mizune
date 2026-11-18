package app.mizune.anki

import app.mizune.boolean
import app.mizune.jsonOf
import app.mizune.long
import app.mizune.obj
import app.mizune.parseJson
import app.mizune.string
import app.mizune.text
import app.mizune.toJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64

open class AnkiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Sends a request to AnkiConnect and returns its reply. Each platform has its own way of
 * making the request; it reports a refused connection as [AnkiException].
 */
fun interface AnkiTransport {
    fun post(url: String, body: String): String
}

/** The platform's way of reaching AnkiConnect: HttpURLConnection on the JVM, XMLHttpRequest in a browser. */
expect fun defaultAnkiTransport(): AnkiTransport

/**
 * The AnkiConnect add-on's HTTP API (version 6). The same API is served by desktop Anki with
 * AnkiConnect installed and by AnkiConnect Android, so this works against either.
 */
class AnkiConnect(
    private val url: String = DEFAULT_URL,
    private val transport: AnkiTransport = defaultAnkiTransport(),
) : AnkiBackend {

    fun version(): Int = (call("version").long() ?: 0L).toInt()

    override fun decks(): List<String> = strings(call("deckNames"))

    override fun noteTypes(): List<String> = strings(call("modelNames"))

    override fun fieldNames(noteType: String): List<String> =
        strings(call("modelFieldNames", jsonOf("modelName" to noteType)))

    /** Saves a file into Anki's media folder and returns the name Anki stored it under. */
    override fun storeMedia(name: String, data: ByteArray): String =
        call("storeMediaFile", jsonOf("filename" to name, "data" to Base64.encode(data))).text() ?: name

    /** Adds a note and returns its id. Unless [allowDuplicate], Anki refuses duplicates of the first field within the deck. */
    override fun addNote(deck: String, noteType: String, fields: Map<String, String>, tags: List<String>, allowDuplicate: Boolean): Long {
        val note = JsonObject(note(deck, noteType, fields, allowDuplicate) + ("tags" to toJson(tags)))
        return call("addNote", jsonOf("note" to note)).long() ?: throw AnkiException("Unexpected reply from $url")
    }

    /**
     * Why Anki would refuse this note (a duplicate, a missing deck), or null if it would take it,
     * or if this AnkiConnect is too old to say.
     */
    override fun cannotAdd(deck: String, noteType: String, fields: Map<String, String>): String? {
        val result = try {
            call("canAddNotesWithErrorDetail", jsonOf("notes" to listOf(note(deck, noteType, fields))))
        } catch (e: AnkiException) {
            // too old to say: ask the slow way
            if ("unsupported action" in e.message.orEmpty()) return super.cannotAdd(deck, noteType, fields) else throw e
        }
        val detail = (result as? JsonArray)?.obj(0) ?: return null
        return if (detail["canAdd"].boolean() ?: true) null else detail.string("error", "Anki won't add this card")
    }

    /** Ids of the notes in [deck] whose [field] is exactly [value]. */
    override fun findNotes(deck: String, field: String, value: String): List<Long> {
        val query = AnkiBackend.quote("deck:$deck") + " " + AnkiBackend.quote("$field:$value")
        val array = call("findNotes", jsonOf("query" to query)) as? JsonArray ?: return emptyList()
        return array.mapNotNull { it.long() }
    }

    override fun deleteNotes(ids: List<Long>) {
        call("deleteNotes", jsonOf("notes" to ids))
    }

    private fun note(deck: String, model: String, fields: Map<String, String>, allowDuplicate: Boolean = false) = jsonOf(
        "deckName" to deck,
        "modelName" to model,
        "fields" to fields,
        "options" to mapOf("allowDuplicate" to allowDuplicate),
    )

    private fun call(action: String, params: JsonObject? = null): JsonElement? {
        val body = jsonOf("action" to action, "version" to 6).let { if (params != null) JsonObject(it + ("params" to params)) else it }
        val response = transport.post(url, body.toString())
        val json = parseJson(response) as? JsonObject ?: throw AnkiException("Unexpected reply from $url")
        val error = json["error"]
        if (error != null && error != JsonNull) throw AnkiException(error.text().orEmpty())
        return json["result"]
    }

    private fun strings(value: JsonElement?): List<String> =
        (value as? JsonArray)?.mapNotNull { it.text() } ?: emptyList()

    companion object {
        const val DEFAULT_URL = "http://127.0.0.1:8765"
    }
}
