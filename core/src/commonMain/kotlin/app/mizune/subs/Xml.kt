package app.mizune.subs

/**
 * Just enough XML for subtitle files, the same on every platform (a browser's worker has no
 * DOMParser): elements with namespaces, attributes, text and CDATA, the five named entities and
 * character references. Subtitles come from the network, so a DOCTYPE is refused, and with it
 * any entity it would declare.
 */
internal object Xml {
    const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"

    sealed class Node {
        var parent: Element? = null
            internal set
    }

    class Text(val value: String) : Node()

    class Element(
        /** The name as written, prefix and all. */
        val name: String,
        val namespace: String?,
        /** By name as written; [namespaces] has each one's namespace. */
        private val attributes: Map<String, String>,
        private val namespaces: Map<String, String?>,
    ) : Node() {
        val children = mutableListOf<Node>()

        val localName: String get() = name.substringAfter(':')

        /** The attribute named [name] as written, or "". */
        fun attribute(name: String): String = attributes[name].orEmpty()

        /** The attribute [localName] in [namespace], or "". */
        fun attribute(namespace: String, localName: String): String =
            attributes.entries.firstOrNull { (name, _) -> name.substringAfter(':') == localName && namespaces[name] == namespace }?.value.orEmpty()

        /** Every element inside this one, in document order. */
        fun descendants(): Sequence<Element> = children.asSequence().filterIsInstance<Element>().flatMap { sequenceOf(it) + it.descendants() }

        fun ancestors(): Sequence<Element> = generateSequence(parent) { it.parent }
    }

    /** The document's root element, or null if [text] isn't well-formed XML this reads. */
    fun parse(text: String): Element? = try {
        Reader(text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')).document()
    } catch (_: Malformed) {
        null
    }

    private class Malformed : Exception()

    private class Reader(val s: String) {
        var i = 0

        fun document(): Element {
            misc()
            if (s.startsWith("<!DOCTYPE", i)) throw Malformed()
            val root = element(mapOf("xml" to XML_NAMESPACE))
            misc()
            if (i != s.length) throw Malformed()
            return root
        }

        /** Whitespace, comments and processing instructions, which may come around the root. */
        fun misc() {
            while (true) {
                while (i < s.length && s[i].isWhitespace()) i++
                when {
                    s.startsWith("<?", i) -> skipPast("?>")
                    s.startsWith("<!--", i) -> skipPast("-->")
                    else -> return
                }
            }
        }

        fun skipPast(end: String) {
            val at = s.indexOf(end, i)
            if (at < 0) throw Malformed()
            i = at + end.length
        }

        fun element(scope: Map<String, String?>): Element {
            expect('<')
            val name = name()
            val raw = linkedMapOf<String, String>()
            while (true) {
                val spaced = space()
                when {
                    s.startsWith("/>", i) || s.startsWith(">", i) -> break
                    !spaced -> throw Malformed()
                }
                val attribute = name()
                space()
                expect('=')
                space()
                val quote = s.getOrNull(i)?.takeIf { it == '"' || it == '\'' } ?: throw Malformed()
                i++
                val end = s.indexOf(quote, i).takeIf { it >= 0 } ?: throw Malformed()
                if (raw.put(attribute, unescape(s.substring(i, end)).replace(Regex("[\t\n]"), " ")) != null) throw Malformed()
                i = end + 1
            }
            // this element's own declarations count for its name and attributes too
            val inScope = scope.toMutableMap()
            for ((attribute, value) in raw) {
                when {
                    attribute == "xmlns" -> inScope[""] = value.ifEmpty { null }
                    attribute.startsWith("xmlns:") -> inScope[attribute.removePrefix("xmlns:")] = value
                }
            }
            fun namespaceOf(qualified: String, isAttribute: Boolean): String? {
                val prefix = qualified.substringBefore(':', "")
                if (prefix.isEmpty()) return if (isAttribute) null else inScope[""]
                return inScope[prefix] ?: throw Malformed()
            }
            val attributes = raw.filterKeys { it != "xmlns" && !it.startsWith("xmlns:") }
            val element = Element(name, namespaceOf(name, false), attributes, attributes.mapValues { (k, _) -> namespaceOf(k, true) })
            if (s.startsWith("/>", i)) {
                i += 2
                return element
            }
            expect('>')
            content(element, inScope)
            return element
        }

        fun content(into: Element, scope: Map<String, String?>) {
            val text = StringBuilder()
            fun flush() {
                if (text.isNotEmpty()) into.children += Text(text.toString()).also { it.parent = into }
                text.clear()
            }
            while (true) {
                if (i >= s.length) throw Malformed()
                when {
                    s.startsWith("</", i) -> {
                        i += 2
                        if (name() != into.name) throw Malformed()
                        space()
                        expect('>')
                        flush()
                        return
                    }
                    s.startsWith("<!--", i) -> skipPast("-->")
                    s.startsWith("<?", i) -> skipPast("?>")
                    s.startsWith("<![CDATA[", i) -> {
                        val end = s.indexOf("]]>", i + 9).takeIf { it >= 0 } ?: throw Malformed()
                        text.append(s, i + 9, end)
                        i = end + 3
                    }
                    s[i] == '<' -> {
                        flush()
                        into.children += element(scope).also { it.parent = into }
                    }
                    else -> {
                        val end = s.indexOf('<', i).takeIf { it >= 0 } ?: s.length
                        text.append(unescape(s.substring(i, end)))
                        i = end
                    }
                }
            }
        }

        fun name(): String {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "_:-.")) i++
            if (i == start || s[start].isDigit() || s[start] in "-.") throw Malformed()
            return s.substring(start, i)
        }

        fun space(): Boolean {
            val start = i
            while (i < s.length && s[i].isWhitespace()) i++
            return i > start
        }

        fun expect(c: Char) {
            if (s.getOrNull(i) != c) throw Malformed()
            i++
        }

        fun unescape(text: String): String {
            if ('&' !in text) return text
            val out = StringBuilder(text.length)
            var at = 0
            while (at < text.length) {
                val c = text[at]
                if (c != '&') {
                    out.append(c)
                    at++
                    continue
                }
                val end = text.indexOf(';', at).takeIf { it > at } ?: throw Malformed()
                val entity = text.substring(at + 1, end)
                when {
                    entity == "lt" -> out.append('<')
                    entity == "gt" -> out.append('>')
                    entity == "amp" -> out.append('&')
                    entity == "quot" -> out.append('"')
                    entity == "apos" -> out.append('\'')
                    entity.startsWith("#x") -> out.appendCodePoint(entity.substring(2).toIntOrNull(16))
                    entity.startsWith("#") -> out.appendCodePoint(entity.substring(1).toIntOrNull())
                    // nothing declares any other: there's no DOCTYPE
                    else -> throw Malformed()
                }
                at = end + 1
            }
            return out.toString()
        }

        fun StringBuilder.appendCodePoint(code: Int?) {
            if (code == null || code !in 1..0x10FFFF || code in 0xD800..0xDFFF) throw Malformed()
            if (code < 0x10000) append(code.toChar())
            else {
                val v = code - 0x10000
                append((0xD800 + (v shr 10)).toChar())
                append((0xDC00 + (v and 0x3FF)).toChar())
            }
        }
    }
}
