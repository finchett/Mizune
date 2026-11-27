package app.mizune.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import app.mizune.ui.rememberSceneTime
import org.jetbrains.skia.FontMgr
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

// A card's page (CardHtml.kt) drawn in Compose, near enough to how Anki's reviewer draws it:
// blocks, lines of text with their spans, furigana over its kanji, lists, simple tables and flex
// rows, borders and backgrounds. The sample's picture is the summer scene, its sound Anki's replay
// button; other pictures in Anki's media folder are left out. Lengths are CSS px, drawn as dp.

private class Ctx(
    val page: CardPage,
    /** The hints shown (their links clicked). */
    val shown: SnapshotStateSet<HtmlElement>,
    val measurer: TextMeasurer,
    val density: Density,
    val sceneTime: State<Float>,
) {
    fun style(e: HtmlElement) = page.styles[e]

    /** [e]'s display, with Anki's hints opened or closed as they've been clicked. */
    fun display(e: HtmlElement): String {
        if ("hint" in e.classes) {
            if (e.tag == "a" && e in shown) return "none"
            val link = e.previousElement
            if (e.tag == "div" && link != null && link.tag == "a" && "hint" in link.classes && link in shown) return "block"
        }
        return style(e).display
    }

    private val blocks = java.util.IdentityHashMap<HtmlElement, Boolean>()

    /** Whether [node] starts a block of its own, rather than going on in the line around it. */
    fun isBlock(node: HtmlNode): Boolean {
        if (node !is HtmlElement) return false
        val d = display(node)
        if (d == "none") return false
        if (node.tag == "img" || node.tag == "input" || node.tag == "hr") return true
        if (node.tag == "ruby" || node.tag == "mz-replay" || node.tag == "br") return false
        if (!d.startsWith("inline") && d != "contents" && d != "ruby" && d != "ruby-text") return true
        // an inline element with a block in it is drawn as a block itself
        return blocks.getOrPut(node) { node.elements.any { isBlock(it) } }
    }
}

/** A card side's page, as wide as it's given. */
@Composable
internal fun CardPageView(page: CardPage, animated: Boolean, modifier: Modifier = Modifier) {
    val shown = remember(page) { mutableStateSetOf<HtmlElement>() }
    val ctx = Ctx(page, shown, rememberTextMeasurer(), LocalDensity.current, rememberSceneTime(paused = !animated))
    val body = page.styles[page.body]
    // the page's background is the whole card's, as a browser paints it
    val background = page.styles[page.root].background ?: body.background ?: if (page.dark) Color(0xFF2C2C2C) else Color.White
    Box(modifier.fillMaxWidth().background(background)) {
        Block(ctx, page.body, paintBackground = false)
    }
}

/** What goes down a block: a line of text and what's inline in it, or a block of its own. */
private sealed class Item {
    class Run(val nodes: List<HtmlNode>) : Item()
    class Child(val element: HtmlElement, val marker: Int) : Item()
}

private fun items(ctx: Ctx, e: HtmlElement): List<Item> {
    val out = mutableListOf<Item>()
    var run = mutableListOf<HtmlNode>()
    var listIndex = 0
    for (child in e.children) {
        if (child is HtmlElement && ctx.display(child) == "none") continue
        if (child is HtmlElement && ctx.isBlock(child)) {
            if (run.isNotEmpty()) out += Item.Run(run)
            run = mutableListOf()
            val listItem = ctx.display(child) == "list-item"
            if (listItem) listIndex++
            out += Item.Child(child, if (listItem) listIndex else 0)
        } else {
            run += child
        }
    }
    if (run.isNotEmpty()) out += Item.Run(run)
    return out
}

@Composable
private fun Block(ctx: Ctx, e: HtmlElement, marker: Int = 0, paintBackground: Boolean = true, shrink: Boolean = false) {
    val s = ctx.style(e)
    val density = ctx.density
    var modifier = Modifier.cssBox(s, density, shrink)
    if (s.opacity < 1f) modifier = modifier.alpha(s.opacity)
    if (s.hidden) modifier = modifier.alpha(0f)
    val shape = if (s.radius > 0) RoundedCornerShape(s.radius.dp) else null
    if (paintBackground && s.background != null) {
        modifier = if (shape != null) modifier.clip(shape).background(s.background) else modifier.background(s.background)
    } else if (shape != null) {
        modifier = modifier.clip(shape)
    }
    modifier = modifier.cssBorders(s, shape)
    // a hint, once shown, hides again on a click, as in Anki
    if (e.tag == "div" && "hint" in e.classes) {
        e.previousElement?.takeIf { it in ctx.shown }?.let { link -> modifier = modifier.clickable(interactionSource = null, indication = null) { ctx.shown -= link } }
    }
    if (marker > 0) modifier = modifier.listMarker(ctx, e, s, marker)
    val p = s.padding
    modifier = modifier.padding(
        start = p.left.resolve(CARD_LAYOUT_WIDTH).dp, top = p.top.resolve(CARD_LAYOUT_WIDTH).dp,
        end = p.right.resolve(CARD_LAYOUT_WIDTH).dp, bottom = p.bottom.resolve(CARD_LAYOUT_WIDTH).dp,
    )
    Box(modifier) {
        when {
            e.tag == "img" -> Picture(ctx, e, fill = !shrink)
            e.tag == "input" -> AnswerBox(s, ctx.page.dark)
            e.tag == "hr" -> Unit
            s.display == "flex" || s.display == "inline-flex" -> Flex(ctx, e, s)
            s.display == "table" -> Table(ctx, e)
            else -> Flow(ctx, e, s, fill = !shrink)
        }
    }
}

@Composable
private fun Flow(ctx: Ctx, e: HtmlElement, s: CardStyle, fill: Boolean = true) {
    Column(if (fill) Modifier.fillMaxWidth() else Modifier) {
        items(ctx, e).fastForEach { item ->
            when (item) {
                is Item.Run -> Run(ctx, item.nodes, s, if (fill) Modifier.fillMaxWidth() else Modifier)
                is Item.Child -> Block(ctx, item.element, item.marker)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Flex(ctx: Ctx, e: HtmlElement, s: CardStyle) {
    val gap = s.gap.dp
    val list = items(ctx, e)
    // a flex item is as wide as what's in it, unless it's stretched across
    @Composable
    fun item(item: Item, modifier: Modifier, shrink: Boolean = true) = Box(modifier) {
        when (item) {
            is Item.Run -> Run(ctx, item.nodes, s)
            is Item.Child -> Block(ctx, item.element, item.marker, shrink = shrink)
        }
    }
    if (s.flexDirection.startsWith("column")) {
        val align = when (s.alignItems) {
            "center" -> Alignment.CenterHorizontally
            "flex-end", "end" -> Alignment.End
            else -> Alignment.Start
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = align) {
            list.fastForEach { item(it, if (s.alignItems == "stretch") Modifier.fillMaxWidth() else Modifier, shrink = s.alignItems != "stretch") }
        }
        return
    }
    val arrangement = when (s.justifyContent) {
        "center" -> Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
        "flex-end", "end", "right" -> Arrangement.spacedBy(gap, Alignment.End)
        "space-between" -> Arrangement.SpaceBetween
        "space-around" -> Arrangement.SpaceAround
        "space-evenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(gap)
    }
    if (s.flexWrap) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = arrangement, verticalArrangement = Arrangement.spacedBy(gap)) {
            list.fastForEach { item(it, Modifier) }
        }
        return
    }
    val align = when (s.alignItems) {
        "center" -> Alignment.CenterVertically
        "flex-end", "end" -> Alignment.Bottom
        else -> Alignment.Top
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = arrangement, verticalAlignment = align) {
        list.fastForEach { item(it, Modifier.weight(1f, fill = false)) }
    }
}

@Composable
private fun Table(ctx: Ctx, table: HtmlElement) {
    val rows = mutableListOf<HtmlElement>()
    fun collect(e: HtmlElement) {
        for (child in e.elements) when (child.tag) {
            "tr" -> rows += child
            "thead", "tbody", "tfoot" -> collect(child)
        }
    }
    collect(table)
    Column(Modifier.fillMaxWidth()) {
        rows.fastForEach { row ->
            val cells = row.elements.filter { (it.tag == "td" || it.tag == "th") && ctx.display(it) != "none" }
            Row(Modifier.fillMaxWidth()) {
                cells.fastForEach { cell -> Box(Modifier.weight(1f)) { Block(ctx, cell) } }
            }
        }
    }
}

// ---- lines of text ------------------------------------------------------------------------

@Composable
private fun Run(ctx: Ctx, nodes: List<HtmlNode>, block: CardStyle, modifier: Modifier = Modifier) {
    val inline = HashMap<String, InlineTextContent>()
    val text = buildLine(ctx, nodes, block, inline)
    if (text.isBlank() && inline.isEmpty()) return
    BasicText(text, modifier, style = textStyle(block), inlineContent = inline)
}

/** The text of [nodes] with their spans, whitespace collapsed as a browser does. */
private fun buildLine(ctx: Ctx, nodes: List<HtmlNode>, block: CardStyle, inline: MutableMap<String, InlineTextContent>): AnnotatedString {
    var afterSpace = true
    val out = buildAnnotatedString {
        fun text(s: String, style: CardStyle) {
            val t = when (style.whiteSpace) {
                "pre", "pre-wrap", "break-spaces" -> s
                "pre-line" -> s.replace(Regex("[ \\t\\f]+"), " ")
                else -> s.replace(Regex("[ \\t\\n\\r\\f]+"), " ")
            }
            val kept = if (afterSpace && t.startsWith(" ")) t.drop(1) else t
            if (kept.isEmpty()) return
            append(kept)
            afterSpace = kept.endsWith(" ") || kept.endsWith("\n")
        }
        fun walk(node: HtmlNode, style: CardStyle) {
            when (node) {
                is HtmlText -> text(node.text, style)
                is HtmlElement -> {
                    if (ctx.display(node) == "none") return
                    val own = ctx.style(node)
                    when (node.tag) {
                        "br" -> { append("\n"); afterSpace = true }
                        "ruby" -> { ruby(ctx, node, inline); afterSpace = false }
                        "mz-replay" -> { replay(ctx, inline); afterSpace = false }
                        "rt", "rp" -> Unit
                        else -> {
                            val link = when {
                                node.tag == "a" && "hint" in node.classes -> LinkAnnotation.Clickable("hint") { ctx.shown += node }
                                node.tag == "a" && node.attrs["href"]?.let { it.startsWith("http://") || it.startsWith("https://") } == true ->
                                    LinkAnnotation.Url(node.attrs.getValue("href"))
                                else -> null
                            }
                            val children = { pushStyle(spanStyle(own)); node.children.fastForEach { walk(it, own) }; pop() }
                            if (link != null) withLink(link) { children() } else children()
                        }
                    }
                }
            }
        }
        nodes.fastForEach { walk(it, block) }
    }
    // a space at the end of a line isn't drawn
    return if (out.text.endsWith(" ")) out.subSequence(0, out.length - 1) else out
}

/** Furigana: the reading over its kanji, as a piece of the line the size of the two. */
private fun AnnotatedString.Builder.ruby(ctx: Ctx, ruby: HtmlElement, inline: MutableMap<String, InlineTextContent>) {
    val rt = ruby.elements.firstOrNull { it.tag == "rt" }
    val base = buildString {
        fun walk(node: HtmlNode) {
            when (node) {
                is HtmlText -> append(node.text)
                is HtmlElement -> if (node.tag != "rt" && node.tag != "rp") node.children.forEach(::walk)
            }
        }
        ruby.children.forEach(::walk)
    }.trim()
    val reading = rt?.text?.trim().orEmpty()
    val baseStyle = textStyle(ctx.style(ruby))
    val readingStyle = textStyle(rt?.let(ctx::style) ?: ctx.style(ruby)).let { if (rt == null) it.copy(fontSize = it.fontSize * 0.5f) else it }
    val baseSize = ctx.measurer.measure(base, baseStyle, softWrap = false).size
    val readingSize = if (reading.isEmpty()) null else ctx.measurer.measure(reading, readingStyle, softWrap = false).size
    val id = "ruby${inline.size}"
    with(ctx.density) {
        val width = maxOf(baseSize.width, readingSize?.width ?: 0)
        val height = baseSize.height + (readingSize?.height ?: 0)
        inline[id] = InlineTextContent(Placeholder(width.toSp(), height.toSp(), PlaceholderVerticalAlign.TextBottom)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (reading.isNotEmpty()) BasicText(reading, style = readingStyle, softWrap = false)
                BasicText(base, style = baseStyle, softWrap = false)
            }
        }
    }
    appendInlineContent(id, base)
}

/** Anki's replay button, as its reviewer draws one for a sound. */
private fun AnnotatedString.Builder.replay(ctx: Ctx, inline: MutableMap<String, InlineTextContent>) {
    val id = "replay${inline.size}"
    val dark = ctx.page.dark
    inline[id] = InlineTextContent(Placeholder(46.sp, 46.sp, PlaceholderVerticalAlign.TextCenter)) {
        Canvas(Modifier.padding(3.dp).size(40.dp)) {
            val u = size.width / 64f
            drawCircle(if (dark) Color(0xFF363636) else Color.White, 29 * u)
            drawCircle(if (dark) Color(0xFFFCFCFC) else Color(0xFF414141), 29 * u, style = Stroke(1.5f * u))
            val play = Path().apply {
                moveTo(56.502f * u, 32.301f * u); lineTo(19f * u, 52.402f * u); lineTo(19.329f * u, 11.598f * u); close()
            }
            drawPath(play, if (dark) Color(0xFFFCFCFC) else Color(0xFF414141))
        }
    }
    appendInlineContent(id, "▶")
}

private fun spanStyle(s: CardStyle) = SpanStyle(
    color = if (s.hidden) Color.Transparent else s.color,
    fontSize = s.fontSize.sp,
    fontWeight = FontWeight(s.fontWeight.coerceIn(1, 1000)),
    fontStyle = if (s.italic) FontStyle.Italic else FontStyle.Normal,
    fontFamily = fontFamily(s.fontFamily),
    letterSpacing = s.letterSpacing.sp,
    background = s.background ?: Color.Unspecified,
    textDecoration = TextDecoration.combine(
        listOfNotNull(
            TextDecoration.Underline.takeIf { "underline" in s.decorations },
            TextDecoration.LineThrough.takeIf { "line-through" in s.decorations },
        ),
    ),
    baselineShift = when (s.verticalAlign) {
        "super" -> BaselineShift.Superscript
        "sub" -> BaselineShift.Subscript
        else -> null
    },
)

/** A block's text style, for the lines in it. */
private fun textStyle(s: CardStyle) = TextStyle(
    color = if (s.hidden) Color.Transparent else s.color,
    fontSize = s.fontSize.sp,
    fontWeight = FontWeight(s.fontWeight.coerceIn(1, 1000)),
    fontStyle = if (s.italic) FontStyle.Italic else FontStyle.Normal,
    fontFamily = fontFamily(s.fontFamily),
    letterSpacing = s.letterSpacing.sp,
    lineHeight = s.lineHeight?.sp ?: androidx.compose.ui.unit.TextUnit.Unspecified,
    textAlign = when (s.textAlign) {
        "center" -> TextAlign.Center
        "right", "end" -> TextAlign.End
        "justify" -> TextAlign.Justify
        else -> TextAlign.Start
    },
    textDecoration = if (s.decorations.isEmpty()) null else spanStyle(s).textDecoration,
    // cards are Japanese, so kanji are drawn the Japanese way
    localeList = Japanese,
)

private val families = ConcurrentHashMap<List<String>, Any>()
private object NoFamily

/** The first of [names] there's a font for, or the generic family it names. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun fontFamily(names: List<String>): FontFamily? {
    if (names.isEmpty()) return null
    val found = families.getOrPut(names) {
        names.firstNotNullOfOrNull { name ->
            when (name.lowercase()) {
                "serif", "ui-serif" -> FontFamily.Serif
                "sans-serif", "system-ui", "-apple-system", "blinkmacsystemfont", "ui-sans-serif" -> FontFamily.SansSerif
                "monospace", "ui-monospace" -> FontFamily.Monospace
                "cursive" -> FontFamily.Cursive
                else -> if (runCatching { FontMgr.default.matchFamily(name).count() > 0 }.getOrDefault(false)) {
                    FontFamily(
                        SystemFont(name, FontWeight.Normal), SystemFont(name, FontWeight.Bold),
                        SystemFont(name, FontWeight.Normal, FontStyle.Italic), SystemFont(name, FontWeight.Bold, FontStyle.Italic),
                    )
                } else null
            }
        } ?: NoFamily
    }
    return found as? FontFamily
}

// ---- boxes --------------------------------------------------------------------------------

/**
 * A block's margins and width: as wide as it can be unless it says, no wider than its max-width,
 * and with auto margins either side, in the middle.
 */
private fun Modifier.cssBox(s: CardStyle, density: Density, shrink: Boolean = false): Modifier = layout { measurable, constraints ->
    val bounded = constraints.hasBoundedWidth
    val room = if (bounded) constraints.maxWidth.toFloat() else CARD_LAYOUT_WIDTH * density.density
    fun px(l: Length) = (l.resolve(room / density.density) * density.density).roundToInt()
    val m = s.margin
    val left = if (m.left == Length.Auto) 0 else px(m.left)
    val right = if (m.right == Length.Auto) 0 else px(m.right)
    val top = px(m.top)
    val bottom = px(m.bottom)
    val inner = (room.roundToInt() - left - right).coerceAtLeast(0)
    val cap = s.maxWidth?.let { px(it) } ?: Int.MAX_VALUE
    val wanted = if (s.width == Length.Auto) null else px(s.width)
    val height = if (s.height is Length.Px) px(s.height) else null
    val placeable = if (wanted != null || (bounded && !shrink)) {
        val width = minOf(wanted ?: inner, cap, inner).coerceAtLeast(0)
        measurable.measure(Constraints(width, width, height ?: 0, height ?: Constraints.Infinity))
    } else if (bounded) {
        measurable.measure(Constraints(0, minOf(cap, inner), height ?: 0, height ?: Constraints.Infinity))
    } else {
        measurable.measure(Constraints(0, minOf(cap, Constraints.Infinity), height ?: 0, height ?: Constraints.Infinity))
    }
    val free = (inner - placeable.width).coerceAtLeast(0)
    val x = left + when {
        m.left == Length.Auto && m.right == Length.Auto -> free / 2
        m.left == Length.Auto -> free
        else -> 0
    }
    val width = if (bounded && !shrink) constraints.maxWidth else (placeable.width + left + right).coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(width, (placeable.height + top + bottom).coerceAtLeast(0)) { placeable.place(x, top) }
}

/** A block's borders: one rounded line all round, or each side as it's set. */
private fun Modifier.cssBorders(s: CardStyle, shape: RoundedCornerShape?): Modifier {
    val b = s.borders
    if (b.none { it.width > 0 }) return this
    val same = b.all { it.width == b[0].width && it.color == b[0].color }
    if (same && shape != null) return border(b[0].width.dp, b[0].color, shape).padding(b[0].width.dp)
    return drawBehind {
        val (top, right, bottom, left) = b.map { it.width.dp.toPx() }
        if (top > 0) drawRect(b[0].color, Offset.Zero, Size(size.width, top))
        if (right > 0) drawRect(b[1].color, Offset(size.width - right, 0f), Size(right, size.height))
        if (bottom > 0) drawRect(b[2].color, Offset(0f, size.height - bottom), Size(size.width, bottom))
        if (left > 0) drawRect(b[3].color, Offset.Zero, Size(left, size.height))
    }.padding(start = b[3].width.dp, top = b[0].width.dp, end = b[1].width.dp, bottom = b[2].width.dp)
}

/** A list item's number or bullet, out to the left of it as a browser has it. */
private fun Modifier.listMarker(ctx: Ctx, e: HtmlElement, s: CardStyle, n: Int): Modifier {
    val parentStyle = e.parent?.let(ctx::style) ?: s
    val text = when (s.listStyle.takeIf { e.parent?.tag == "ol" || it != "disc" } ?: parentStyle.listStyle) {
        "none" -> return this
        "decimal" -> "$n."
        "circle" -> "◦"
        "square" -> "▪"
        "lower-alpha" -> "${'a' + (n - 1) % 26}."
        "upper-alpha" -> "${'A' + (n - 1) % 26}."
        "lower-roman" -> "${roman(n).lowercase()}."
        "upper-roman" -> "${roman(n)}."
        else -> "•"
    }
    val layout = ctx.measurer.measure(text, textStyle(s).copy(textAlign = TextAlign.Start), softWrap = false)
    return drawWithContent {
        drawContent()
        drawText(layout, topLeft = Offset(-layout.size.width - 6.dp.toPx(), s.padding.top.resolve(CARD_LAYOUT_WIDTH).dp.toPx()))
    }
}

private fun roman(n: Int): String {
    var left = n.coerceIn(1, 3999)
    return buildString {
        for ((value, letters) in listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) {
            while (left >= value) { append(letters); left -= value }
        }
    }
}

// ---- what isn't text ----------------------------------------------------------------------

/** The sample's picture, as the summer scene, 640 by 360 unless the card says, fitting the card. */
@Composable
private fun Picture(ctx: Ctx, e: HtmlElement, fill: Boolean) {
    if (e.attrs["src"] != SAMPLE_IMAGE) return
    val s = ctx.style(e)
    val time = ctx.sceneTime
    val align = when (e.parent?.let(ctx::style)?.textAlign) {
        "center" -> Alignment.TopCenter
        "right", "end" -> Alignment.TopEnd
        else -> Alignment.TopStart
    }
    Box(if (fill) Modifier.fillMaxWidth() else Modifier, contentAlignment = align) {
        Canvas(
            Modifier.layout { measurable, constraints ->
                val d = density
                val room = if (constraints.hasBoundedWidth) constraints.maxWidth else (640 * d).roundToInt()
                val h = (s.height as? Length.Px)?.px
                var width = when (val w = s.width) {
                    is Length.Px -> w.px * d
                    is Length.Percent -> room * w.percent / 100
                    Length.Auto -> if (h != null) h * d * 16 / 9 else 640 * d
                }
                width = minOf(width, room.toFloat())
                val height = if (h != null && s.width != Length.Auto) h * d else width * 9 / 16
                val placeable = measurable.measure(Constraints.fixed(width.roundToInt(), height.roundToInt()))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
        ) { coverWithScene(time.value, Offset.Zero, size) }
    }
}

/** Where a typed answer goes, disabled, as the reviewer's box. */
@Composable
private fun AnswerBox(s: CardStyle, dark: Boolean) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .border(1.dp, if (dark) Color(0xFF8F8F8F) else Color(0xFF767676), RoundedCornerShape(2.dp))
            .background(if (dark) Color(0xFF3B3B3B) else Color.White)
            .padding(vertical = (s.fontSize * 0.3f).dp),
    ) {
        BasicText(" ", style = textStyle(s))
    }
}
