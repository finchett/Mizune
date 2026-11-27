package app.mizune.desktop

import app.mizune.anki.CardTemplate

// A card as Anki fills it, for the settings' preview: the note type's own templates and CSS,
// filled with a sample word's fields, as the browser extension does (extension/src/anki-card.ts).
// Only as much of Anki's template language as cards use: fields, sections, FrontSide, and the
// text, furigana, kana, kanji, hint and type filters. CardHtml.kt draws what comes out.

/** A note type's look: its cards in Anki's order, its CSS, and each field's HTML for a sample word. */
internal class CardLook(val templates: List<CardTemplate>, val css: String, val fields: Map<String, String>) {
    /** Whether the first card has scripts, which the preview doesn't run. */
    val hasScripts: Boolean
        get() = templates.firstOrNull()?.let { SCRIPT.containsMatchIn(it.front) || SCRIPT.containsMatchIn(it.back) } == true
}

private val SCRIPT = Regex("<script", RegexOption.IGNORE_CASE)

/** The media a sample card names, which the preview draws its own picture and replay button for. */
internal const val SAMPLE_AUDIO = "mizune-sample.ogg"
internal const val SAMPLE_IMAGE = "mizune-sample.webp"

/** Anki's own look for a new note type ("Basic"), for while there's no note type to ask about. */
internal val PLAIN_LOOK = CardLook(
    templates = listOf(
        CardTemplate(
            "Card 1",
            "{{Sentence}}",
            "{{FrontSide}}\n\n<hr id=answer>\n\n{{furigana:Word}}<br>{{Meaning}}<br>{{Picture}}<br>{{Audio}}",
        ),
    ),
    css = ".card {\n  font-family: arial;\n  font-size: 20px;\n  text-align: center;\n  color: black;\n  background-color: white;\n}\n",
    fields = mapOf(
        "Sentence" to "今日はいい<b>天気</b>ですね",
        "Word" to "天気[てんき]",
        "Meaning" to "weather; the elements",
        "Picture" to "<img alt=\"snapshot\" src=\"$SAMPLE_IMAGE\">",
        "Audio" to "[sound:$SAMPLE_AUDIO]",
    ),
)

/** A side of a card, as the HTML inside Anki's `#qa`. */
internal class CardSide(val name: String, val html: String)

internal object CardTemplates {
    private fun stripHtml(html: String) = html.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ")
    private fun isEmpty(html: String) = stripHtml(html).isBlank() && !Regex("<img", RegexOption.IGNORE_CASE).containsMatchIn(html)

    // Anki's furigana: "天気[てんき]", a space ending the run before it
    private val RUBY = Regex(""" ?([^ >]+?)\[(.+?)\]""")

    private val FILTERS: Map<String, (String, String) -> String> = mapOf(
        "text" to { text, _ -> stripHtml(text) },
        "furigana" to { text, _ -> text.replace(RUBY, "<ruby><rb>$1</rb><rt>$2</rt></ruby>") },
        "kana" to { text, _ -> text.replace(RUBY, "$2") },
        "kanji" to { text, _ -> text.replace(RUBY, "$1") },
        // Anki's own markup, so the card's CSS takes it as it would there; a click shows what it hides
        "hint" to { text, field ->
            if (text.isEmpty()) "" else "<a class=\"hint\" href=\"#\">Show $field</a><div class=\"hint\" style=\"display: none\">$text</div>"
        },
        "type" to { _, _ -> "<input type=\"text\" id=\"typeans\" disabled>" },
        "tts" to { _, _ -> "" },
    )

    private val SECTION = Regex("""\{\{([#^])\s*([^}]+?)\s*\}\}([\s\S]*?)\{\{/\s*\2\s*\}\}""")
    private val FIELD = Regex("""\{\{([^}]+)\}\}""")

    /** [template] filled from [fields], or what Anki fills its own with ([own]), as Anki renders a side. */
    fun render(template: String, fields: Map<String, String>, own: Map<String, String>, frontSide: String): String {
        fun value(name: String) = fields[name] ?: own[name] ?: ""
        var out = template
        // sections, the outermost first; what's kept is gone over again
        while (true) {
            val match = SECTION.find(out) ?: break
            val filled = !isEmpty(value(match.groupValues[2]))
            val keep = if (match.groupValues[1] == "#") filled else !filled
            out = out.substring(0, match.range.first) + (if (keep) match.groupValues[3] else "") + out.substring(match.range.last + 1)
        }
        return FIELD.replace(out) { match ->
            val parts = match.groupValues[1].split(':').map { it.trim() }
            val name = parts.last()
            if (name == "FrontSide") return@replace frontSide
            // filters apply from the field outwards
            var text = value(name)
            for (filter in parts.dropLast(1).reversed()) {
                text = FILTERS[filter.substringBefore(' ')]?.invoke(text, name) ?: text
            }
            text
        }
    }

    /**
     * The first card's two sides: [look]'s templates over its fields, with the sample's sound made
     * into Anki's replay button (drawn by CardHtml.kt as `<mz-replay>`).
     */
    fun sides(look: CardLook, own: Map<String, String>): List<CardSide> {
        val template = look.templates.firstOrNull() ?: return emptyList()
        val all = own + ("Card" to template.name)
        val media = { html: String -> html.replace(Regex("""\[sound:[^\]]*\]"""), "<mz-replay></mz-replay>") }
        val front = render(template.front, look.fields, all, "")
        val back = render(template.back, look.fields, all, front)
        return listOf(CardSide("Front", media(front)), CardSide("Back", media(back)))
    }
}
