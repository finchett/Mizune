package app.mizune

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// Lenient reading of JSON from dictionaries, Anki and settings, as org.json's opt* calls had it:
// a missing or mistyped value is a default, never an error.

/** [text] as JSON, or null if it isn't. */
internal fun parseJson(text: String): JsonElement? = try {
    Json.parseToJsonElement(text)
} catch (_: Exception) {
    null
}

/** A string as it is, a number as written, an object or array as JSON; null for JSON's null. */
internal fun JsonElement?.text(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> content
    else -> toString()
}

internal fun JsonElement?.isString(): Boolean = this is JsonPrimitive && isString

internal fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

internal fun JsonElement?.long(): Long? =
    (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

internal fun JsonElement?.boolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.string(key: String, default: String = ""): String = this[key].text() ?: default

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonArray.string(index: Int, default: String = ""): String = getOrNull(index).text() ?: default

internal fun JsonArray.obj(index: Int): JsonObject? = getOrNull(index) as? JsonObject

internal fun JsonArray.array(index: Int): JsonArray? = getOrNull(index) as? JsonArray

internal fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to toJson(v) })

/** Plain Kotlin values (strings, numbers, booleans, lists, maps, JSON) as JSON. */
internal fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
    is Iterable<*> -> JsonArray(value.map(::toJson))
    else -> JsonPrimitive(value.toString())
}
