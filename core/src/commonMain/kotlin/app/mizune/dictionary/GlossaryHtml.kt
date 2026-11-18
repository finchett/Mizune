package app.mizune.dictionary

import app.mizune.obj
import app.mizune.parseJson
import app.mizune.string
import app.mizune.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A Yomitan glossary as HTML for an Anki field. Structured content keeps its elements and
 * inline styles, and its data attributes become `data-sc-*` as in Yomitan, so note types
 * styled for Yomitan's cards style these the same way. Images are dropped.
 */
object GlossaryHtml {

    fun render(glossaryJson: String): String {
        val items = parseJson(glossaryJson) as? JsonArray ?: return escape(glossaryJson)
        val plain = mutableListOf<String>()
        val structured = StringBuilder()
        for (item in items) {
            when {
                item.isText() -> plain.add(escape((item as JsonPrimitive).content))
                item is JsonObject && item.string("type") == "text" -> plain.add(escape(item.string("text")))
                item is JsonObject && item.string("type") == "structured-content" -> node(item["content"], structured)
            }
        }
        // plain glosses are short synonyms ("first day", "opening day"): one line reads best
        return listOf(plain.joinToString("; "), structured.toString()).filter { it.isNotEmpty() }.joinToString("<br>")
    }

    /**
     * The glosses of one definition as plain text: its strings, or in structured content the
     * items of lists marked as glossaries (Jitendex and similar), else its first line.
     */
    fun glosses(glossaryJson: String): List<String> {
        val items = parseJson(glossaryJson) as? JsonArray ?: return listOf(glossaryJson)
        val out = mutableListOf<String>()
        for (item in items) {
            when {
                item.isText() -> out.add((item as JsonPrimitive).content)
                item is JsonObject && item.string("type") == "text" -> out.add(item.string("text"))
                item is JsonObject && item.string("type") == "structured-content" -> {
                    val found = mutableListOf<String>()
                    glossaryItems(item["content"], inside = false, found)
                    if (found.isEmpty()) plain(item["content"]).lines().firstOrNull { it.isNotBlank() }?.let(found::add)
                    out.addAll(found)
                }
            }
        }
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun glossaryItems(value: JsonElement?, inside: Boolean, out: MutableList<String>) {
        when (value) {
            is JsonArray -> for (item in value) glossaryItems(item, inside, out)
            is JsonObject -> {
                val marked = value.obj("data")?.string("content") == "glossary"
                if ((inside || marked) && value.string("tag") == "li") {
                    out.add(plain(value["content"]))
                } else if (marked && value.string("tag") !in setOf("ul", "ol")) {
                    // a glossary that isn't a list is a single gloss
                    out.add(plain(value["content"]))
                } else {
                    glossaryItems(value["content"], inside || marked, out)
                }
            }
            else -> Unit
        }
    }

    /** Text only, leaving out readings in ruby (<rt>). */
    private fun plain(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.content
        is JsonArray -> value.joinToString("") { plain(it) }
        is JsonObject -> when (value.string("tag")) {
            "rt", "rp", "img", "image" -> ""
            "br", "div", "li", "p" -> plain(value["content"]) + "\n"
            else -> plain(value["content"])
        }
    }

    private fun node(value: JsonElement?, out: StringBuilder) {
        when (value) {
            null, JsonNull -> Unit
            is JsonPrimitive -> out.append(escape(value.content))
            is JsonArray -> for (item in value) node(item, out)
            is JsonObject -> element(value, out)
        }
    }

    private fun element(element: JsonObject, out: StringBuilder) {
        val tag = element.string("tag")
        when (tag) {
            "br" -> out.append("<br>")
            "img", "image" -> Unit
            in TAGS -> {
                // links in dictionaries point into Yomitan's own search, which Anki can't open
                val name = if (tag == "a") "span" else tag
                out.append('<').append(name)
                attributes(element, out)
                out.append('>')
                node(element["content"], out)
                out.append("</").append(name).append('>')
            }
            else -> node(element["content"], out)
        }
    }

    private fun attributes(element: JsonObject, out: StringBuilder) {
        element.obj("data")?.let { data ->
            for (key in data.keys) {
                out.append(" data-sc-").append(key.replace(Regex("[^A-Za-z0-9-]"), ""))
                    .append("=\"").append(escape(data.string(key))).append('"')
            }
        }
        val style = element.obj("style")
        if (style != null) {
            val css = style.keys.filter { it in STYLES }.joinToString(";") { key ->
                val property = key.replace(Regex("[A-Z]")) { "-" + it.value.lowercase() }
                "$property:${style[key].text()}"
            }
            if (css.isNotEmpty()) out.append(" style=\"").append(escape(css)).append('"')
        }
        for (key in listOf("lang", "title", "colSpan", "rowSpan")) {
            if (key in element) out.append(' ').append(key.lowercase()).append("=\"").append(escape(element.string(key))).append('"')
        }
    }

    private fun JsonElement?.isText() = this is JsonPrimitive && isString

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\n' -> append("<br>")
            else -> append(c)
        }
    }

    private val TAGS = setOf(
        "span", "div", "ol", "ul", "li", "ruby", "rt", "rp", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
        "details", "summary", "a", "b", "i", "em", "strong", "sub", "sup",
    )

    private val STYLES = setOf(
        "fontStyle", "fontWeight", "fontSize", "color", "backgroundColor", "textDecorationLine", "textDecorationStyle",
        "textDecorationColor", "verticalAlign", "textAlign", "marginTop", "marginLeft", "marginRight", "marginBottom",
        "paddingTop", "paddingLeft", "paddingRight", "paddingBottom", "listStyleType", "borderStyle", "borderWidth",
        "borderColor", "borderRadius", "whiteSpace", "wordBreak",
    )
}
