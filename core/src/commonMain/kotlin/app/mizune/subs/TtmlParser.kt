package app.mizune.subs

import app.mizune.subs.Xml.Element
import app.mizune.subs.Xml.Text

/**
 * TTML subtitles, including the IMSC 1.1 profile Netflix uses for Japanese. Ruby (furigana) is
 * left out, so a line reads as its base text: 漢字 rather than 漢字かんじ.
 */
object TtmlParser {
    private const val TTML = "http://www.w3.org/ns/ttml"
    private const val PARAMETER = "http://www.w3.org/ns/ttml#parameter"
    private const val STYLING = "http://www.w3.org/ns/ttml#styling"

    fun parse(content: String): List<Cue> {
        val root = Xml.parse(content) ?: return emptyList()
        val timing = Timing(
            tickRate = root.attribute(PARAMETER, "tickRate").toDoubleOrNull(),
            frameRate = root.attribute(PARAMETER, "frameRate").toDoubleOrNull() ?: 30.0,
        )
        val rubyTextStyles = rubyTextStyles(root)
        val cues = mutableListOf<Cue>()
        elements(root, "p").forEach { p ->
            val offset = ancestors(p).sumOf { timing.seconds(it.attribute("begin")) ?: 0.0 }
            val begin = timing.seconds(p.attribute("begin")) ?: return@forEach
            val end = timing.seconds(p.attribute("end"))
                ?: timing.seconds(p.attribute("dur"))?.let { begin + it }
                ?: return@forEach
            val text = StringBuilder().also { appendText(p, rubyTextStyles, it) }.toString()
            cues.add(Cue(offset + begin, offset + end, text.lines().joinToString("\n") { it.trim() }))
        }
        return cues.normalized()
    }

    /** Ids of the styles that mark a span as ruby text (the reading), to leave out. */
    private fun rubyTextStyles(root: Element): Set<String> =
        elements(root, "style")
            .filter { it.attribute(STYLING, "ruby") == "text" }
            .map { it.attribute(Xml.XML_NAMESPACE, "id") }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun appendText(element: Element, rubyTextStyles: Set<String>, out: StringBuilder) {
        for (child in element.children) {
            when (child) {
                is Text -> out.append(child.value.replace(Regex("\\s*\n\\s*"), ""))
                is Element -> when {
                    child.localName == "br" -> out.append('\n')
                    isRubyText(child, rubyTextStyles) -> Unit
                    else -> appendText(child, rubyTextStyles, out)
                }
            }
        }
    }

    private fun isRubyText(element: Element, rubyTextStyles: Set<String>): Boolean {
        if (element.attribute(STYLING, "ruby") == "text") return true
        return element.attribute("style").split(' ').any { it in rubyTextStyles }
    }

    private fun elements(root: Element, name: String): List<Element> {
        val list = root.descendants().filter { it.namespace == TTML && it.localName == name }.toList()
        if (list.isNotEmpty()) return list
        // files that leave out the namespace
        return root.descendants().filter { it.name == name }.toList()
    }

    /** The p's enclosing divs and body, whose begin times offset its own. */
    private fun ancestors(element: Element): List<Element> =
        element.ancestors().filter { it.localName == "div" || it.localName == "body" }.toList()

    private class Timing(val tickRate: Double?, val frameRate: Double) {
        private val clock = Regex("""^(\d+):(\d{2}):(\d{2})(?:([.:])(\d+))?$""")
        private val offset = Regex("""^(\d+(?:\.\d+)?)(h|m|s|ms|f|t)$""")

        fun seconds(value: String?): Double? {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return null
            clock.matchEntire(text)?.let { m ->
                val (h, min, s, separator, fraction) = m.destructured
                val base = h.toInt() * 3600.0 + min.toInt() * 60 + s.toInt()
                return when {
                    fraction.isEmpty() -> base
                    separator == ":" -> base + fraction.toInt() / frameRate
                    else -> base + "0.$fraction".toDouble()
                }
            }
            offset.matchEntire(text)?.let { m ->
                val number = m.groupValues[1].toDouble()
                return when (m.groupValues[2]) {
                    "h" -> number * 3600
                    "m" -> number * 60
                    "s" -> number
                    "ms" -> number / 1000
                    "f" -> number / frameRate
                    else -> number / (tickRate ?: 1.0)
                }
            }
            return null
        }
    }
}
