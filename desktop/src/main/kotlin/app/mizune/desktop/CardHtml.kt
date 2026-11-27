package app.mizune.desktop

import androidx.compose.ui.graphics.Color
import java.util.IdentityHashMap
import kotlin.math.roundToInt

// What a card's preview needs of HTML and CSS, without a browser: a forgiving HTML parser, a CSS
// parser, and the cascade, so each element of a card has its computed style. Drawn by CardView.kt.
// Scripts don't run. Selectors: tags, classes, ids, attributes, descendant, child and sibling
// combinators, :root, :first-child, :last-child, :only-child and :not(); anything else (:hover,
// ::before) leaves its rule out. Values may use custom properties through var().

internal sealed class HtmlNode {
    var parent: HtmlElement? = null
}

internal class HtmlText(var text: String) : HtmlNode()

internal class HtmlElement(val tag: String, val attrs: Map<String, String> = emptyMap()) : HtmlNode() {
    val children = mutableListOf<HtmlNode>()
    val classes: Set<String> = attrs["class"]?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    val id: String? get() = attrs["id"]

    fun add(node: HtmlNode) {
        node.parent = this
        children += node
    }

    val elements: List<HtmlElement> get() = children.filterIsInstance<HtmlElement>()

    /** The element before this one among its parent's, if any. */
    val previousElement: HtmlElement?
        get() {
            val siblings = parent?.elements ?: return null
            return siblings.getOrNull(siblings.indexOfFirst { it === this } - 1)
        }

    fun descendants(): Sequence<HtmlElement> = sequence {
        for (child in elements) {
            yield(child)
            yieldAll(child.descendants())
        }
    }

    /** All the text in it, as written. */
    val text: String
        get() = buildString {
            fun walk(node: HtmlNode) {
                when (node) {
                    is HtmlText -> append(node.text)
                    is HtmlElement -> node.children.forEach(::walk)
                }
            }
            walk(this@HtmlElement)
        }
}

internal object Html {
    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
    private val RAW = setOf("script", "style", "textarea", "title")
    /** Tags that end an open `<p>`. */
    private val ENDS_P = setOf(
        "address", "article", "aside", "blockquote", "div", "dl", "fieldset", "footer", "form", "h1", "h2", "h3", "h4",
        "h5", "h6", "header", "hr", "main", "nav", "ol", "p", "pre", "section", "table", "ul",
    )

    /** [html] parsed into [into], as a browser would take it in, mistakes and all. */
    fun parse(html: String, into: HtmlElement) {
        var current = into
        var i = 0
        val n = html.length
        fun text(s: String) {
            if (s.isEmpty()) return
            val last = current.children.lastOrNull()
            if (last is HtmlText) last.text += decode(s) else current.add(HtmlText(decode(s)))
        }
        fun closest(tag: String, stopAt: Set<String> = emptySet()): HtmlElement? {
            var e: HtmlElement? = current
            while (e != null && e !== into) {
                if (e.tag == tag) return e
                if (e.tag in stopAt) return null
                e = e.parent
            }
            return null
        }
        fun close(e: HtmlElement) {
            current = e.parent ?: into
        }
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                text(html.substring(i))
                break
            }
            text(html.substring(i, lt))
            when {
                html.startsWith("<!--", lt) -> {
                    val end = html.indexOf("-->", lt + 4)
                    i = if (end < 0) n else end + 3
                }
                html.startsWith("</", lt) -> {
                    val end = html.indexOf('>', lt).let { if (it < 0) n else it }
                    val name = html.substring(lt + 2, end).trim().lowercase()
                    closest(name)?.let(::close)
                    i = end + 1
                }
                lt + 1 < n && (html[lt + 1] == '!' || html[lt + 1] == '?') -> {
                    val end = html.indexOf('>', lt)
                    i = if (end < 0) n else end + 1
                }
                lt + 1 < n && html[lt + 1].isLetter() -> {
                    var j = lt + 1
                    while (j < n && (html[j].isLetterOrDigit() || html[j] == '-' || html[j] == ':' || html[j] == '_')) j++
                    val tag = html.substring(lt + 1, j).lowercase()
                    val attrs = linkedMapOf<String, String>()
                    var selfClosing = false
                    while (j < n) {
                        while (j < n && html[j].isWhitespace()) j++
                        if (j >= n) break
                        if (html[j] == '>') { j++; break }
                        if (html.startsWith("/>", j)) { selfClosing = true; j += 2; break }
                        if (html[j] == '/') { j++; continue }
                        val start = j
                        while (j < n && !html[j].isWhitespace() && html[j] != '=' && html[j] != '>' && !html.startsWith("/>", j)) j++
                        val name = html.substring(start, j).lowercase()
                        while (j < n && html[j].isWhitespace()) j++
                        var value = ""
                        if (j < n && html[j] == '=') {
                            j++
                            while (j < n && html[j].isWhitespace()) j++
                            if (j < n && (html[j] == '"' || html[j] == '\'')) {
                                val quote = html[j]
                                val end = html.indexOf(quote, j + 1).let { if (it < 0) n else it }
                                value = html.substring(j + 1, end)
                                j = end + 1
                            } else {
                                val from = j
                                while (j < n && !html[j].isWhitespace() && html[j] != '>') j++
                                value = html.substring(from, j)
                            }
                        }
                        if (name.isNotEmpty() && name !in attrs) attrs[name] = decode(value)
                    }
                    // what a new tag closes by being there
                    if (tag in ENDS_P) closest("p")?.let(::close)
                    if (tag == "li") closest("li", setOf("ul", "ol"))?.let(::close)
                    if (tag == "td" || tag == "th") (closest("td", setOf("tr", "table")) ?: closest("th", setOf("tr", "table")))?.let(::close)
                    if (tag == "tr") closest("tr", setOf("table"))?.let(::close)
                    if (tag == "rt" || tag == "rp") (closest("rt", setOf("ruby")) ?: closest("rp", setOf("ruby")))?.let(::close)
                    val element = HtmlElement(tag, attrs)
                    current.add(element)
                    if (tag in RAW) {
                        val end = Regex("</$tag\\s*>", RegexOption.IGNORE_CASE).find(html, j)
                        element.add(HtmlText(html.substring(j, end?.range?.first ?: n)))
                        i = end?.range?.last?.plus(1) ?: n
                        continue
                    }
                    if (tag !in VOID && !selfClosing) current = element
                    i = j
                }
                else -> {
                    text("<")
                    i = lt + 1
                }
            }
        }
    }

    private val NAMED = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "ensp" to " ",
        "emsp" to " ", "thinsp" to " ", "hellip" to "…", "mdash" to "—", "ndash" to "–", "laquo" to "«",
        "raquo" to "»", "middot" to "·", "times" to "×", "rarr" to "→", "larr" to "←", "uarr" to "↑", "darr" to "↓",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "bull" to "•", "copy" to "©", "deg" to "°",
        "zwj" to "‍", "zwnj" to "‌", "shy" to "­",
    )
    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")

    fun decode(text: String): String = if ('&' !in text) text else ENTITY.replace(text) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> NAMED[e]
        } ?: m.value
    }
}

// ---- CSS ----------------------------------------------------------------------------------

internal class Declaration(val property: String, val value: String, val important: Boolean)

/** One part of a selector: a tag, an id, classes, attributes and pseudo-classes, all of them to match. */
internal class Compound(
    val tag: String?,
    val ids: List<String>,
    val classes: List<String>,
    /** An attribute, and the value it's to have (null: any). */
    val attrs: List<Pair<String, String?>>,
    val pseudos: List<String>,
    val not: List<Compound>,
) {
    val specificity: Int
        get() = ids.size * 10_000 + (classes.size + attrs.size + pseudos.size) * 100 + (if (tag != null) 1 else 0) +
            not.sumOf { it.specificity }

    fun matches(e: HtmlElement): Boolean {
        if (tag != null && tag != e.tag) return false
        if (ids.any { it != e.id }) return false
        if (!e.classes.containsAll(classes)) return false
        for ((name, value) in attrs) {
            val has = e.attrs[name] ?: return false
            if (value != null && has != value) return false
        }
        for (pseudo in pseudos) {
            val siblings = e.parent?.elements
            val ok = when (pseudo) {
                "root" -> e.tag == "html"
                "first-child" -> siblings?.firstOrNull() === e
                "last-child" -> siblings?.lastOrNull() === e
                "only-child" -> siblings?.size == 1
                "empty" -> e.children.isEmpty()
                else -> false
            }
            if (!ok) return false
        }
        return not.none { it.matches(e) }
    }
}

/** A selector, right to left: the element's own part, then each combinator and the part it leads to. */
internal class Selector(private val parts: List<Compound>, private val combinators: List<Char>) {
    val specificity = parts.sumOf { it.specificity }

    fun matches(e: HtmlElement): Boolean = matchFrom(e, 0)

    private fun matchFrom(e: HtmlElement, index: Int): Boolean {
        if (!parts[index].matches(e)) return false
        if (index == parts.lastIndex) return true
        return when (combinators[index]) {
            '>' -> e.parent?.let { matchFrom(it, index + 1) } == true
            '+' -> e.previousElement?.let { matchFrom(it, index + 1) } == true
            '~' -> generateSequence(e.previousElement) { it.previousElement }.any { matchFrom(it, index + 1) }
            else -> generateSequence(e.parent) { it.parent }.any { matchFrom(it, index + 1) }
        }
    }
}

internal class Rule(val selector: Selector, val declarations: List<Declaration>, val origin: Int, val order: Int)

internal object Css {
    /** The rules of [css]; [origin] 0 for the browser's own, 1 for the page's, which win over them. */
    fun parse(css: String, origin: Int, startOrder: Int = 0): List<Rule> {
        val text = css.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        val rules = mutableListOf<Rule>()
        var order = startOrder
        var i = 0
        while (i < text.length) {
            while (i < text.length && (text[i].isWhitespace() || text[i] == ';' || text[i] == '}')) i++
            if (i >= text.length) break
            val brace = text.indexOf('{', i)
            if (text[i] == '@') {
                val semi = text.indexOf(';', i)
                if (brace < 0 || (semi in 0 until brace)) {
                    i = if (semi < 0) text.length else semi + 1
                    continue
                }
                // a block of its own (@media, @font-face, @keyframes): left out
                i = blockEnd(text, brace)
                continue
            }
            if (brace < 0) break
            val prelude = text.substring(i, brace).trim()
            val end = blockEnd(text, brace)
            val body = text.substring(brace + 1, (end - 1).coerceAtLeast(brace + 1))
            val declarations = declarations(body)
            for (selector in splitTop(prelude, ',')) {
                parseSelector(selector.trim())?.let { rules += Rule(it, declarations, origin, order++) }
            }
            i = end
        }
        return rules
    }

    /** Just past the `}` that closes the block opened at [open]. */
    private fun blockEnd(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i + 1
                '"', '\'' -> i = text.indexOf(text[i], i + 1).let { if (it < 0) text.length else it }
            }
            i++
        }
        return text.length
    }

    /** [s] split at [separator] where it's not inside brackets or quotes. */
    fun splitTop(s: String, separator: Char): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        for ((i, c) in s.withIndex()) {
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> depth--
                c == separator && depth == 0 -> {
                    out += s.substring(start, i)
                    start = i + 1
                }
            }
        }
        out += s.substring(start)
        return out
    }

    fun declarations(body: String): List<Declaration> = splitTop(body, ';').mapNotNull { part ->
        val colon = part.indexOf(':')
        if (colon < 0) return@mapNotNull null
        val property = part.substring(0, colon).trim().let { if (it.startsWith("--")) it else it.lowercase() }
        var value = part.substring(colon + 1).trim()
        val important = value.endsWith("!important", ignoreCase = true)
        if (important) value = value.dropLast("!important".length).trim()
        if (property.isEmpty() || value.isEmpty() && !property.startsWith("--")) null else Declaration(property, value, important)
    }

    /** [s] as a selector, or null if it uses what the preview can't match. */
    fun parseSelector(s: String): Selector? {
        if (s.isEmpty() || "::" in s) return null
        val parts = mutableListOf<Compound>()
        val combinators = mutableListOf<Char>()
        var i = 0
        var pending: Char? = null
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> {
                    if (pending == null && parts.isNotEmpty()) pending = ' '
                    i++
                }
                c == '>' || c == '+' || c == '~' -> {
                    pending = c
                    i++
                }
                else -> {
                    val (compound, next) = parseCompound(s, i) ?: return null
                    if (parts.isNotEmpty()) combinators += pending ?: ' '
                    pending = null
                    parts += compound
                    i = next
                }
            }
        }
        if (parts.isEmpty()) return null
        return Selector(parts.reversed(), combinators.reversed())
    }

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '-' || c == '_' || c == '\\'

    private fun parseCompound(s: String, from: Int): Pair<Compound, Int>? {
        var i = from
        var tag: String? = null
        val ids = mutableListOf<String>()
        val classes = mutableListOf<String>()
        val attrs = mutableListOf<Pair<String, String?>>()
        val pseudos = mutableListOf<String>()
        val not = mutableListOf<Compound>()
        fun name(): String {
            val start = i
            while (i < s.length && isNameChar(s[i])) i++
            return s.substring(start, i).replace("\\", "")
        }
        if (i < s.length && s[i] == '*') i++
        else if (i < s.length && isNameChar(s[i])) tag = name().lowercase()
        while (i < s.length) {
            when (s[i]) {
                '.' -> { i++; classes += name() }
                '#' -> { i++; ids += name() }
                '[' -> {
                    val end = s.indexOf(']', i).takeIf { it > 0 } ?: return null
                    val inside = s.substring(i + 1, end)
                    val eq = inside.indexOf('=')
                    if (eq < 0) attrs += inside.trim().lowercase() to null
                    else {
                        // only an exact value is matched; ~= |= ^= and the rest are taken as there being one
                        val op = inside.getOrNull(eq - 1)
                        val key = inside.substring(0, if (op != null && op in "~|^$*") eq - 1 else eq).trim().lowercase()
                        val value = inside.substring(eq + 1).trim().trim('"', '\'')
                        attrs += key to if (op != null && op in "~|^$*") null else value
                    }
                    i = end + 1
                }
                ':' -> {
                    i++
                    val pseudo = name().lowercase()
                    if (pseudo == "not" && i < s.length && s[i] == '(') {
                        val end = s.indexOf(')', i).takeIf { it > 0 } ?: return null
                        val inner = parseCompound(s.substring(i + 1, end).trim(), 0)?.first ?: return null
                        not += inner
                        i = end + 1
                    } else {
                        if (i < s.length && s[i] == '(') return null
                        if (pseudo !in setOf("root", "first-child", "last-child", "only-child", "empty")) return null
                        pseudos += pseudo
                    }
                }
                else -> break
            }
        }
        if (i == from) return null
        return Compound(tag, ids, classes, attrs, pseudos, not) to i
    }
}

// ---- computed style -----------------------------------------------------------------------

/** A CSS length: in px, a share of what it's measured against, or auto. */
internal sealed class Length {
    data class Px(val px: Float) : Length()
    data class Percent(val percent: Float) : Length()
    data object Auto : Length()

    fun resolve(of: Float): Float = when (this) {
        is Px -> px
        is Percent -> of * percent / 100
        Auto -> 0f
    }
}

internal class Edges(val top: Length, val right: Length, val bottom: Length, val left: Length) {
    companion object {
        val Zero = Edges(Length.Px(0f), Length.Px(0f), Length.Px(0f), Length.Px(0f))
    }
}

internal class Border(val width: Float, val color: Color)

/** What the preview takes of an element's computed style, in CSS px. */
internal class CardStyle(
    val display: String,
    val color: Color,
    val fontSize: Float,
    val fontWeight: Int,
    val italic: Boolean,
    val fontFamily: List<String>,
    val textAlign: String,
    /** Line height in px, or null for the font's own. */
    val lineHeight: Float?,
    val decorations: Set<String>,
    val whiteSpace: String,
    val letterSpacing: Float,
    val verticalAlign: String,
    val hidden: Boolean,
    val vars: Map<String, String>,
    val background: Color?,
    val margin: Edges,
    val padding: Edges,
    val borders: List<Border>,
    val radius: Float,
    val width: Length,
    val maxWidth: Length?,
    val height: Length,
    val opacity: Float,
    val listStyle: String,
    val flexDirection: String,
    val flexWrap: Boolean,
    val justifyContent: String,
    val alignItems: String,
    val gap: Float,
) {
    val inline: Boolean get() = display.startsWith("inline") || display == "ruby" || display == "contents"
}

/** Every element of a card's page with its computed style. */
internal class CardStyles(root: HtmlElement, rules: List<Rule>) {
    private val styles = IdentityHashMap<HtmlElement, CardStyle>()
    private val sorted = rules.sortedWith(compareBy<Rule>({ it.origin }, { it.selector.specificity }, { it.order }))

    init {
        compute(root, null)
    }

    operator fun get(element: HtmlElement): CardStyle = styles.getValue(element)

    private fun compute(e: HtmlElement, parent: CardStyle?) {
        val declared = LinkedHashMap<String, String>()
        val important = LinkedHashMap<String, String>()
        for (rule in sorted) {
            if (!rule.selector.matches(e)) continue
            for (d in rule.declarations) (if (d.important) important else declared)[d.property] = d.value
        }
        presentational(e, declared)
        e.attrs["style"]?.let { inline -> for (d in Css.declarations(inline)) (if (d.important) important else declared)[d.property] = d.value }
        declared.putAll(important)
        val style = Computer(declared, parent, e).style()
        styles[e] = style
        for (child in e.elements) compute(child, style)
    }

    /** What old-fashioned attributes say: `<font color size face>`, `align`, `bgcolor`, an image's size. */
    private fun presentational(e: HtmlElement, into: MutableMap<String, String>) {
        val attrs = e.attrs
        if (e.tag == "font") {
            attrs["color"]?.let { into.putIfAbsent("color", it) }
            attrs["face"]?.let { into.putIfAbsent("font-family", it) }
            attrs["size"]?.trim()?.let { size ->
                val sizes = listOf(10, 13, 16, 18, 24, 32, 48)
                val n = when {
                    size.startsWith("+") -> 3 + (size.drop(1).toIntOrNull() ?: 0)
                    size.startsWith("-") -> 3 - (size.drop(1).toIntOrNull() ?: 0)
                    else -> size.toIntOrNull() ?: 3
                }.coerceIn(1, 7)
                into.putIfAbsent("font-size", "${sizes[n - 1]}px")
            }
        }
        attrs["align"]?.let { if (e.tag != "img") into.putIfAbsent("text-align", it.lowercase()) }
        attrs["bgcolor"]?.let { into.putIfAbsent("background-color", it) }
        if (e.tag == "img" || e.tag == "table" || e.tag == "td") {
            attrs["width"]?.let { into.putIfAbsent("width", if (it.all(Char::isDigit)) "${it}px" else it) }
            attrs["height"]?.let { into.putIfAbsent("height", if (it.all(Char::isDigit)) "${it}px" else it) }
        }
    }

    private class Computer(val declared: Map<String, String>, val parent: CardStyle?, val element: HtmlElement) {
        val vars: Map<String, String> = (parent?.vars.orEmpty()) + declared.filterKeys { it.startsWith("--") }

        /** [property]'s value with var() put in, or null if it isn't set (or is set to inherit). */
        fun value(property: String): String? {
            val raw = declared[property] ?: return null
            val v = substitute(raw, 0).trim()
            return v.takeUnless { it == "inherit" || it == "unset" || it.isEmpty() }
        }

        fun isInitial(property: String) = value(property) == "initial"

        private fun substitute(v: String, depth: Int): String {
            if ("var(" !in v || depth > 8) return v
            val start = v.indexOf("var(")
            var depthHere = 0
            var end = start + 4
            while (end < v.length) {
                if (v[end] == '(') depthHere++
                if (v[end] == ')') { if (depthHere == 0) break; depthHere-- }
                end++
            }
            val inside = v.substring(start + 4, end.coerceAtMost(v.length))
            val comma = Css.splitTop(inside, ',')
            val name = comma[0].trim()
            val fallback = if (comma.size > 1) inside.substring(comma[0].length + 1).trim() else ""
            val replacement = vars[name]?.trim() ?: fallback
            return substitute(v.substring(0, start) + replacement + v.substring((end + 1).coerceAtMost(v.length)), depth + 1)
        }

        val parentSize = parent?.fontSize ?: 16f

        val fontSize: Float = run {
            val font = value("font")?.let(::fontShorthandSize)
            val v = value("font-size") ?: font ?: return@run parentSize
            when (v) {
                "initial", "medium" -> 16f
                "xx-small" -> 9f; "x-small" -> 10f; "small" -> 13f; "large" -> 18f; "x-large" -> 24f; "xx-large" -> 32f; "xxx-large" -> 48f
                "smaller" -> parentSize / 1.2f
                "larger" -> parentSize * 1.2f
                else -> length(v, parentSize, parentSize) ?: parentSize
            }
        }

        fun length(v: String, percentOf: Float?, em: Float = fontSize): Float? {
            val t = v.trim().lowercase()
            if (t == "0") return 0f
            val m = Regex("^(-?[0-9]*\\.?[0-9]+)([a-z%]*)$").find(t) ?: return calc(t, percentOf, em)
            val n = m.groupValues[1].toFloat()
            return when (m.groupValues[2]) {
                "px", "" -> n
                "em" -> n * em
                "rem" -> n * 16f
                "pt" -> n * 4 / 3
                "pc" -> n * 16
                "%" -> percentOf?.let { it * n / 100 }
                "vw" -> n * CARD_LAYOUT_WIDTH / 100
                "vh" -> n * 6
                "ex", "ch" -> n * em / 2
                "cm" -> n * 96 / 2.54f
                "mm" -> n * 96 / 25.4f
                "in" -> n * 96
                else -> null
            }
        }

        /** A calc() of px and em, near enough; anything else in it, as its first length. */
        private fun calc(t: String, percentOf: Float?, em: Float): Float? {
            if (!t.startsWith("calc(") && !t.startsWith("min(") && !t.startsWith("max(") && !t.startsWith("clamp(")) return null
            val inside = t.substringAfter('(').substringBeforeLast(')')
            if (t.startsWith("calc(")) {
                val tokens = inside.split(Regex("\\s+(?=[+-]\\s)|(?<=\\s[+-])\\s+")).map { it.trim() }
                var total = 0f
                var sign = 1f
                for (token in tokens) when (token) {
                    "+" -> sign = 1f
                    "-" -> sign = -1f
                    else -> total += sign * (length(token, percentOf, em) ?: return length(tokens.first(), percentOf, em))
                }
                return total
            }
            val values = Css.splitTop(inside, ',').mapNotNull { length(it, percentOf, em) }
            return when {
                values.isEmpty() -> null
                t.startsWith("min(") -> values.min()
                t.startsWith("max(") -> values.max()
                else -> values.getOrNull(1) ?: values.first()
            }
        }

        fun lengthValue(v: String?): Length? = when {
            v == null -> null
            v == "auto" || v == "none" || v == "initial" || v == "fit-content" || v == "max-content" || v == "min-content" -> Length.Auto
            v.trim().endsWith("%") -> v.trim().dropLast(1).toFloatOrNull()?.let { Length.Percent(it) }
            else -> length(v, null)?.let { Length.Px(it) }
        }

        fun edges(prefix: String): Edges {
            val all = value(prefix)?.split(Regex("\\s+(?![^(]*\\))"))?.map { lengthValue(it) ?: Length.Px(0f) }
            val sides = when (all?.size) {
                1 -> listOf(all[0], all[0], all[0], all[0])
                2 -> listOf(all[0], all[1], all[0], all[1])
                3 -> listOf(all[0], all[1], all[2], all[1])
                4 -> all
                else -> listOf<Length>(Length.Px(0f), Length.Px(0f), Length.Px(0f), Length.Px(0f))
            }
            val names = listOf("top", "right", "bottom", "left")
            val each = names.mapIndexed { i, side -> lengthValue(value("$prefix-$side")) ?: sides[i] }
            return Edges(each[0], each[1], each[2], each[3])
        }

        val color: Color = run {
            val v = value("color") ?: return@run parent?.color ?: Color.Black
            if (v == "initial") Color.Black else parseColor(v, parent?.color ?: Color.Black) ?: parent?.color ?: Color.Black
        }

        fun borders(): List<Border> {
            val shorthand = value("border")
            return listOf("top", "right", "bottom", "left").map { side ->
                var width = 0f
                var styled = false
                var colour = color
                for (source in listOfNotNull(shorthand, value("border-$side"))) {
                    val parsed = borderParts(source)
                    width = parsed.first ?: if (parsed.second != null) 3f else width
                    parsed.second?.let { styled = it }
                    parsed.third?.let { colour = it }
                }
                value("border-width")?.let { w -> pick(w, side)?.let { length(it, null) }?.let { width = it } }
                value("border-$side-width")?.let { w -> length(w, null)?.let { width = it } }
                value("border-style")?.let { s -> pick(s, side)?.let { styled = it != "none" && it != "hidden" } }
                value("border-$side-style")?.let { s -> styled = s != "none" && s != "hidden" }
                value("border-color")?.let { c -> pick(c, side)?.let { parseColor(it, color) }?.let { colour = it } }
                value("border-$side-color")?.let { c -> parseColor(c, color)?.let { colour = it } }
                Border(if (styled) width else 0f, colour)
            }
        }

        /** One side's value out of a 1–4 value shorthand. */
        private fun pick(v: String, side: String): String? {
            val parts = v.split(Regex("\\s+(?![^(]*\\))"))
            val index = listOf("top", "right", "bottom", "left").indexOf(side)
            return when (parts.size) {
                1 -> parts[0]
                2 -> parts[index % 2]
                3 -> parts[if (index == 3) 1 else index]
                else -> parts.getOrNull(index)
            }
        }

        /** A border shorthand's width, whether it's drawn, and colour, each if it says. */
        private fun borderParts(v: String): Triple<Float?, Boolean?, Color?> {
            if (v == "none" || v == "0") return Triple(0f, false, null)
            var width: Float? = null
            var styled: Boolean? = null
            var colour: Color? = null
            for (part in v.split(Regex("\\s+(?![^(]*\\))"))) {
                when (part) {
                    "none", "hidden" -> styled = false
                    "solid", "dashed", "dotted", "double", "groove", "ridge", "inset", "outset" -> styled = true
                    "thin" -> width = 1f
                    "medium" -> width = 3f
                    "thick" -> width = 5f
                    else -> length(part, null)?.let { width = it } ?: parseColor(part, color)?.let { colour = it }
                }
            }
            return Triple(width, styled, colour)
        }

        fun style(): CardStyle {
            val display = value("display") ?: UA_DISPLAY[element.tag] ?: "inline"
            val family = (value("font-family") ?: value("font")?.let(::fontShorthandFamily))
                ?.let { f -> Css.splitTop(f, ',').map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() } }
                ?: parent?.fontFamily.orEmpty()
            val weight = (value("font-weight") ?: value("font")?.let(::fontShorthandWeight))?.let { w ->
                when (w) {
                    "normal", "initial" -> 400
                    "bold" -> 700
                    "bolder" -> ((parent?.fontWeight ?: 400) + 300).coerceAtMost(900)
                    "lighter" -> ((parent?.fontWeight ?: 400) - 300).coerceAtLeast(100)
                    else -> w.toIntOrNull()
                }
            } ?: parent?.fontWeight ?: 400
            val italic = (value("font-style") ?: value("font")?.takeIf { "italic" in it || "oblique" in it }?.let { "italic" })
                ?.let { it == "italic" || it.startsWith("oblique") } ?: parent?.italic ?: false
            val lineHeight = value("line-height")?.let { v ->
                when {
                    v == "normal" || v == "initial" -> null
                    v.toFloatOrNull() != null -> v.toFloat() * fontSize
                    else -> length(v, fontSize)
                }
            } ?: parent?.lineHeight?.let { inherited ->
                // a unitless line height goes on scaling with each element's size; near enough, as this
                val unitless = parent.vars["--mz-line-height-unitless"]
                if (unitless != null) unitless.toFloat() * fontSize else inherited
            }
            val ownDecorations = (value("text-decoration-line") ?: value("text-decoration"))
                ?.split(Regex("\\s+"))?.filter { it == "underline" || it == "line-through" || it == "overline" }?.toSet()
            val decorations = if (value("text-decoration") == "none" && element.tag == "a") emptySet()
            else (parent?.decorations.orEmpty()) + ownDecorations.orEmpty()
            val background = (value("background-color") ?: value("background"))?.let { v ->
                if (v == "none" || v == "transparent" || v == "initial") null
                else parseColor(v, color) ?: Regex("(#[0-9a-fA-F]{3,8}|rgba?\\([^)]*\\)|hsla?\\([^)]*\\))").find(v)?.let { parseColor(it.value, color) }
            }?.takeIf { it.alpha > 0f }
            val radius = value("border-radius")?.split(Regex("\\s+"))?.firstOrNull()?.let { length(it, null) } ?: 0f
            val ownVars = vars.toMutableMap()
            // a unitless line height is remembered, so what's inside scales it by its own size
            value("line-height")?.let { v ->
                if (v.toFloatOrNull() != null) ownVars["--mz-line-height-unitless"] = v else ownVars.remove("--mz-line-height-unitless")
            }
            val justify = value("justify-content") ?: "flex-start"
            val align = value("align-items") ?: "stretch"
            return CardStyle(
                display = display.split(Regex("\\s+")).let { parts -> if (parts.size > 1 && parts[0] == "inline") "inline-block" else parts.last() },
                color = color,
                fontSize = fontSize,
                fontWeight = weight,
                italic = italic,
                fontFamily = family,
                textAlign = value("text-align")?.let { if (it == "-webkit-center") "center" else it } ?: parent?.textAlign ?: "start",
                lineHeight = lineHeight,
                decorations = decorations,
                whiteSpace = value("white-space") ?: parent?.whiteSpace ?: "normal",
                letterSpacing = value("letter-spacing")?.let { if (it == "normal") 0f else length(it, null) } ?: parent?.letterSpacing ?: 0f,
                verticalAlign = value("vertical-align") ?: "baseline",
                hidden = value("visibility")?.let { it == "hidden" || it == "collapse" } ?: parent?.hidden ?: false,
                vars = ownVars,
                background = background,
                margin = edges("margin"),
                padding = edges("padding"),
                borders = borders(),
                radius = radius,
                width = lengthValue(value("width")) ?: Length.Auto,
                maxWidth = lengthValue(value("max-width"))?.takeIf { it != Length.Auto },
                height = lengthValue(value("height")) ?: Length.Auto,
                opacity = value("opacity")?.let { o -> if (o.endsWith("%")) o.dropLast(1).toFloatOrNull()?.div(100) else o.toFloatOrNull() }?.coerceIn(0f, 1f) ?: 1f,
                listStyle = (value("list-style-type") ?: value("list-style")?.split(Regex("\\s+"))?.firstOrNull { it in LIST_STYLES })
                    ?: parent?.listStyle ?: "disc",
                flexDirection = value("flex-direction") ?: value("flex-flow")?.split(" ")?.firstOrNull()?.takeIf { it.startsWith("row") || it.startsWith("column") } ?: "row",
                flexWrap = (value("flex-wrap") ?: value("flex-flow"))?.contains("wrap") == true && value("flex-wrap") != "nowrap",
                justifyContent = justify,
                alignItems = align,
                gap = (value("gap") ?: value("column-gap") ?: value("row-gap"))?.split(" ")?.firstOrNull()?.let { length(it, null) } ?: 0f,
            )
        }

        private fun fontShorthandSize(font: String): String? =
            font.split(Regex("\\s+")).firstOrNull { Regex("^[0-9.]+(px|em|rem|pt|%)(/.*)?$").matches(it) }?.substringBefore('/')

        private fun fontShorthandFamily(font: String): String? {
            val parts = font.split(Regex("\\s+"))
            val at = parts.indexOfFirst { Regex("^[0-9.]+(px|em|rem|pt|%)(/.*)?$").matches(it) }
            return if (at < 0) null else parts.drop(at + 1).joinToString(" ").takeIf { it.isNotBlank() }
        }

        private fun fontShorthandWeight(font: String): String? =
            font.split(Regex("\\s+")).firstOrNull { it == "bold" || it == "bolder" || it == "lighter" || Regex("^[1-9]00$").matches(it) }
    }

    companion object {
        private val LIST_STYLES = setOf("none", "disc", "circle", "square", "decimal", "lower-alpha", "upper-alpha", "lower-roman", "upper-roman")
    }
}

/** How wide a card's laid out, before it's scaled to fit: about a phone's screen, as narrow as Anki's reviewer gets. */
internal const val CARD_LAYOUT_WIDTH = 420f

private val UA_DISPLAY: Map<String, String> = buildMap {
    for (tag in listOf(
        "html", "body", "div", "p", "ul", "ol", "dl", "dt", "dd", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "blockquote", "pre",
        "center", "section", "article", "header", "footer", "nav", "main", "aside", "form", "figure", "figcaption", "details",
        "summary", "address", "fieldset", "legend",
    )) put(tag, "block")
    put("li", "list-item")
    put("table", "table"); put("tr", "table-row"); put("td", "table-cell"); put("th", "table-cell")
    put("thead", "table-row-group"); put("tbody", "table-row-group"); put("tfoot", "table-row-group")
    for (tag in listOf("head", "script", "style", "title", "meta", "link", "template", "rp", "noscript", "audio", "video", "source")) put(tag, "none")
    put("ruby", "ruby"); put("rt", "ruby-text")
    put("img", "inline-block"); put("input", "inline-block"); put("button", "inline-block")
}

/** The browser's own rules, under everything else. */
internal const val UA_CSS = """
b, strong, th { font-weight: bold; }
i, em, cite, var, dfn, address { font-style: italic; }
u, ins { text-decoration: underline; }
s, strike, del { text-decoration: line-through; }
small { font-size: smaller; }
big { font-size: larger; }
sub { font-size: smaller; vertical-align: sub; }
sup { font-size: smaller; vertical-align: super; }
code, kbd, samp, tt, pre { font-family: monospace; }
pre { white-space: pre; margin: 1em 0; }
a { color: #0000ee; text-decoration: underline; }
h1 { font-size: 2em; margin: 0.67em 0; font-weight: bold; }
h2 { font-size: 1.5em; margin: 0.83em 0; font-weight: bold; }
h3 { font-size: 1.17em; margin: 1em 0; font-weight: bold; }
h4 { margin: 1.33em 0; font-weight: bold; }
h5 { font-size: 0.83em; margin: 1.67em 0; font-weight: bold; }
h6 { font-size: 0.67em; margin: 2.33em 0; font-weight: bold; }
p, dl { margin: 1em 0; }
dd { margin-left: 40px; }
ul, ol { margin: 1em 0; padding-left: 40px; }
ol { list-style-type: decimal; }
ul { list-style-type: disc; }
ul ul { list-style-type: circle; }
li ul, li ol, ul ul, ol ol, ul ol, ol ul { margin: 0; }
blockquote { margin: 1em 40px; }
hr { margin: 0.5em auto; border: 1px inset #9a9a9a; }
center, th { text-align: center; }
rt { font-size: 50%; }
td, th { padding: 1px; }
table { border-spacing: 2px; }
"""

/** Anki's reviewer around a card's CSS: its margins, its colours, pictures that fit. */
internal const val REVIEWER_CSS = """
body { margin: 0; padding: 20px; background-color: #ffffff; color: #000000; }
body.nightMode { background-color: #2c2c2c; color: #fcfcfc; }
.nightMode a { color: #8ab4f8; }
img { max-width: 100%; }
#typeans { width: 100%; }
"""

/** A CSS colour as Compose's, or null if [v] isn't one. */
internal fun parseColor(v: String, current: Color): Color? {
    val t = v.trim().lowercase()
    if (t == "currentcolor") return current
    if (t == "transparent") return Color.Transparent
    NAMED_COLORS[t]?.let { return Color(0xFF000000 or it) }
    if (t.startsWith("#")) {
        val hex = t.drop(1)
        if (!hex.all { it in "0123456789abcdef" }) return null
        val full = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("") + "ff"
            4 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex + "ff"
            8 -> hex
            else -> return null
        }
        val n = full.toLong(16)
        return Color(((n shr 24) and 0xFF).toInt(), ((n shr 16) and 0xFF).toInt(), ((n shr 8) and 0xFF).toInt(), (n and 0xFF).toInt())
    }
    val fn = Regex("^(rgba?|hsla?)\\((.*)\\)$").find(t) ?: return null
    val args = fn.groupValues[2].split(Regex("[,/\\s]+")).filter { it.isNotEmpty() }
    if (args.size < 3) return null
    fun alpha(): Float = args.getOrNull(3)?.let { a -> if (a.endsWith("%")) a.dropLast(1).toFloat() / 100 else a.toFloatOrNull() } ?: 1f
    return runCatching {
        if (fn.groupValues[1].startsWith("rgb")) {
            fun channel(s: String) = if (s.endsWith("%")) s.dropLast(1).toFloat() * 2.55f else s.toFloat()
            Color(channel(args[0]).roundToInt().coerceIn(0, 255), channel(args[1]).roundToInt().coerceIn(0, 255), channel(args[2]).roundToInt().coerceIn(0, 255), (alpha() * 255).roundToInt().coerceIn(0, 255))
        } else {
            val h = args[0].removeSuffix("deg").toFloat()
            val s = args[1].removeSuffix("%").toFloat() / 100
            val l = args[2].removeSuffix("%").toFloat() / 100
            Color.hsl(((h % 360) + 360) % 360, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f), alpha().coerceIn(0f, 1f))
        }
    }.getOrNull()
}

private val NAMED_COLORS: Map<String, Long> = mapOf(
    "black" to 0x000000, "white" to 0xffffff, "red" to 0xff0000, "green" to 0x008000, "blue" to 0x0000ff, "yellow" to 0xffff00,
    "gray" to 0x808080, "grey" to 0x808080, "silver" to 0xc0c0c0, "maroon" to 0x800000, "purple" to 0x800080, "fuchsia" to 0xff00ff,
    "magenta" to 0xff00ff, "lime" to 0x00ff00, "olive" to 0x808000, "navy" to 0x000080, "teal" to 0x008080, "aqua" to 0x00ffff,
    "cyan" to 0x00ffff, "orange" to 0xffa500, "darkgray" to 0xa9a9a9, "darkgrey" to 0xa9a9a9, "lightgray" to 0xd3d3d3,
    "lightgrey" to 0xd3d3d3, "dimgray" to 0x696969, "dimgrey" to 0x696969, "gainsboro" to 0xdcdcdc, "whitesmoke" to 0xf5f5f5,
    "darkred" to 0x8b0000, "darkblue" to 0x00008b, "darkgreen" to 0x006400, "darkorange" to 0xff8c00, "crimson" to 0xdc143c,
    "firebrick" to 0xb22222, "tomato" to 0xff6347, "coral" to 0xff7f50, "salmon" to 0xfa8072, "pink" to 0xffc0cb,
    "hotpink" to 0xff69b4, "deeppink" to 0xff1493, "gold" to 0xffd700, "khaki" to 0xf0e68c, "beige" to 0xf5f5dc,
    "ivory" to 0xfffff0, "linen" to 0xfaf0e6, "wheat" to 0xf5deb3, "tan" to 0xd2b48c, "brown" to 0xa52a2a, "chocolate" to 0xd2691e,
    "sienna" to 0xa0522d, "indigo" to 0x4b0082, "violet" to 0xee82ee, "orchid" to 0xda70d6, "plum" to 0xdda0dd,
    "lavender" to 0xe6e6fa, "skyblue" to 0x87ceeb, "lightblue" to 0xadd8e6, "steelblue" to 0x4682b4, "royalblue" to 0x4169e1,
    "dodgerblue" to 0x1e90ff, "deepskyblue" to 0x00bfff, "cornflowerblue" to 0x6495ed, "midnightblue" to 0x191970,
    "slategray" to 0x708090, "slategrey" to 0x708090, "lightslategray" to 0x778899, "darkslategray" to 0x2f4f4f,
    "seagreen" to 0x2e8b57, "forestgreen" to 0x228b22, "limegreen" to 0x32cd32, "lightgreen" to 0x90ee90,
    "olivedrab" to 0x6b8e23, "turquoise" to 0x40e0d0, "darkcyan" to 0x008b8b, "aquamarine" to 0x7fffd4,
    "goldenrod" to 0xdaa520, "darkgoldenrod" to 0xb8860b, "lightyellow" to 0xffffe0, "lightcyan" to 0xe0ffff,
    "mintcream" to 0xf5fffa, "honeydew" to 0xf0fff0, "aliceblue" to 0xf0f8ff, "ghostwhite" to 0xf8f8ff, "snow" to 0xfffafa,
    "seashell" to 0xfff5ee, "oldlace" to 0xfdf5e6, "floralwhite" to 0xfffaf0, "antiquewhite" to 0xfaebd7,
    "mistyrose" to 0xffe4e1, "lightpink" to 0xffb6c1, "palevioletred" to 0xdb7093, "mediumvioletred" to 0xc71585,
    "rebeccapurple" to 0x663399, "darkviolet" to 0x9400d3, "darkmagenta" to 0x8b008b, "slateblue" to 0x6a5acd,
    "darkslateblue" to 0x483d8b, "mediumpurple" to 0x9370db, "lightcoral" to 0xf08080, "indianred" to 0xcd5c5c,
    "rosybrown" to 0xbc8f8f, "peru" to 0xcd853f, "sandybrown" to 0xf4a460, "burlywood" to 0xdeb887, "navajowhite" to 0xffdead,
    "moccasin" to 0xffe4b5, "peachpuff" to 0xffdab9, "papayawhip" to 0xffefd5, "blanchedalmond" to 0xffebcd,
    "cornsilk" to 0xfff8dc, "lemonchiffon" to 0xfffacd, "palegoldenrod" to 0xeee8aa, "darkkhaki" to 0xbdb76b,
    "yellowgreen" to 0x9acd32, "chartreuse" to 0x7fff00, "lawngreen" to 0x7cfc00, "springgreen" to 0x00ff7f,
    "mediumseagreen" to 0x3cb371, "darkseagreen" to 0x8fbc8f, "lightseagreen" to 0x20b2aa, "cadetblue" to 0x5f9ea0,
    "powderblue" to 0xb0e0e6, "lightsteelblue" to 0xb0c4de, "mediumblue" to 0x0000cd, "darkturquoise" to 0x00ced1,
    "mediumturquoise" to 0x48d1cc, "paleturquoise" to 0xafeeee, "orangered" to 0xff4500, "darkolivegreen" to 0x556b2f,
    "darksalmon" to 0xe9967a, "lightsalmon" to 0xffa07a, "thistle" to 0xd8bfd8, "mediumorchid" to 0xba55d3,
    "darkorchid" to 0x9932cc, "blueviolet" to 0x8a2be2, "mediumslateblue" to 0x7b68ee, "lightskyblue" to 0x87cefa,
    "mediumspringgreen" to 0x00fa9a, "mediumaquamarine" to 0x66cdaa, "palegreen" to 0x98fb98, "greenyellow" to 0xadff2f,
    "lavenderblush" to 0xfff0f5, "azure" to 0xf0ffff, "bisque" to 0xffe4c4, "darkslategrey" to 0x2f4f4f, "lightslategrey" to 0x778899,
)

/**
 * A side of a card made into a page as Anki's reviewer has it: `<html><body class="card
 * card1"><div id="qa">`, the side inside, styled by the browser's rules, the reviewer's, the
 * note type's CSS and any `<style>` in the templates.
 */
internal class CardPage(html: String, css: String, val dark: Boolean) {
    val root = HtmlElement("html")
    val body = HtmlElement("body", mapOf("class" to "card card1" + if (dark) " nightMode night_mode" else ""))
    val hasScripts: Boolean
    val styles: CardStyles

    init {
        root.add(body)
        val qa = HtmlElement("div", mapOf("id" to "qa"))
        body.add(qa)
        Html.parse(html, qa)
        hasScripts = root.descendants().any { it.tag == "script" }
        val inPage = root.descendants().filter { it.tag == "style" }.joinToString("\n") { it.text }
        val ua = Css.parse(UA_CSS, origin = 0)
        val author = Css.parse(REVIEWER_CSS, 1) + Css.parse(css, 1, 1_000) + Css.parse(inPage, 1, 100_000)
        styles = CardStyles(root, ua + author)
    }
}
