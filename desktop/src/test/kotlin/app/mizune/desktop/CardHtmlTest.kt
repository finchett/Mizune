package app.mizune.desktop

import androidx.compose.ui.graphics.Color
import app.mizune.anki.CardTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card preview's templates, HTML and CSS: what Anki and a browser would make of a card. */
class CardHtmlTest {
    private val fields = mapOf("Expression" to "天気", "Reading" to "天気[てんき]", "Meaning" to "weather", "Empty" to "", "Picture" to "<img src=\"a.webp\">")

    private fun render(template: String, front: String = "") = CardTemplates.render(template, fields, mapOf("Tags" to "mizune"), front)

    @Test
    fun fieldsSectionsAndFrontSide() {
        assertEquals("天気 / weather", render("{{Expression}} / {{Meaning}}"))
        assertEquals("has", render("{{#Meaning}}has{{/Meaning}}{{#Empty}}not{{/Empty}}"))
        assertEquals("none", render("{{^Empty}}none{{/Empty}}{{^Meaning}}not{{/Meaning}}"))
        // a picture alone counts as something there
        assertEquals("pic", render("{{#Picture}}pic{{/Picture}}"))
        assertEquals("front<hr>", render("{{FrontSide}}<hr>", front = "front"))
        assertEquals("mizune", render("{{Tags}}"))
        assertEquals("", render("{{Missing}}"))
    }

    @Test
    fun filters() {
        assertEquals("<ruby><rb>天気</rb><rt>てんき</rt></ruby>", render("{{furigana:Reading}}"))
        assertEquals("てんき", render("{{kana:Reading}}"))
        assertEquals("天気", render("{{kanji:Reading}}"))
        assertEquals("", render("{{text:Picture}}"))
        assertTrue(render("{{hint:Meaning}}").startsWith("<a class=\"hint\" href=\"#\">Show Meaning</a>"))
        assertTrue("typeans" in render("{{type:Meaning}}"))
        // filters go from the field outwards, and one Anki has but the preview doesn't is passed over
        assertEquals("てんき", render("{{kana:nonsense:Reading}}"))
    }

    @Test
    fun sidesMakeTheSoundAReplayButton() {
        val look = CardLook(listOf(CardTemplate("Card 1", "{{Expression}}", "{{FrontSide}}[sound:x.ogg]")), "", fields)
        val (front, back) = CardTemplates.sides(look, emptyMap())
        assertEquals("天気", front.html)
        assertEquals("天気<mz-replay></mz-replay>", back.html)
        assertFalse(look.hasScripts)
        assertTrue(CardLook(listOf(CardTemplate("Card 1", "<script>x()</script>", "")), "", fields).hasScripts)
    }

    @Test
    fun htmlParsesAsABrowserWould() {
        val root = HtmlElement("div")
        Html.parse("<p>one<p>two</p><hr id=answer><ul><li>a<li>b</ul><img src='x.png'/>&amp;&nbsp;&#x5929;<!-- gone --><b>bold", root)
        assertEquals(listOf("p", "p", "hr", "ul", "img", "#text", "b"), root.children.map { (it as? HtmlElement)?.tag ?: "#text" })
        assertEquals("answer", (root.children[2] as HtmlElement).id)
        assertEquals(2, (root.children[3] as HtmlElement).elements.size)
        assertEquals("& 天", (root.children[5] as HtmlText).text)
        assertEquals("bold", (root.children[6] as HtmlElement).text)
        // a script's text is kept as it is, not parsed
        val scripted = HtmlElement("div")
        Html.parse("<script>if (a < b) document.write('<b>')</script>after", scripted)
        assertEquals("if (a < b) document.write('<b>')", (scripted.children[0] as HtmlElement).text)
        assertEquals("after", (scripted.children[1] as HtmlText).text)
    }

    private fun page(html: String, css: String, dark: Boolean = false) = CardPage(html, css, dark)

    private fun CardPage.find(id: String) = root.descendants().first { it.id == id }

    @Test
    fun theCascade() {
        val css = """
            .card { font-size: 20px; color: black; text-align: center; }
            .card.nightMode { color: #eeeeee; }
            p { color: green; }
            .word { color: blue; font-size: 1.5em; }
            #a.word { color: red; }
            .word:hover { color: purple; }
            .loud { color: orange !important; }
            :root { --accent: #c2410c; }
            .accent { color: var(--accent); background: var(--missing, rgba(0, 0, 255, 0.5)); }
            @media (max-width: 100px) { .word { color: pink; } }
        """
        val p = page("""<div id="a" class="word loud">x</div><div id="b" class="word" style="color: #123456">y</div><span id="c">z</span><div id="d" class="accent">w</div>""", css)
        assertEquals(Color(0xFFFFA500), p.styles[p.find("a")].color)
        assertEquals(30f, p.styles[p.find("a")].fontSize)
        assertEquals(Color(0xFF123456), p.styles[p.find("b")].color)
        // inherited from the card, through #qa
        assertEquals(Color.Black, p.styles[p.find("c")].color)
        assertEquals("center", p.styles[p.find("c")].textAlign)
        assertEquals(Color(0xFFC2410C), p.styles[p.find("d")].color)
        assertEquals(Color(0f, 0f, 1f, 128 / 255f), p.styles[p.find("d")].background)
        // and in Anki's dark mode
        val dark = page("<span id=\"c\">z</span>", css, dark = true)
        assertEquals(Color(0xFFEEEEEE), dark.styles[dark.find("c")].color)
    }

    @Test
    fun boxesAndLengths() {
        val p = page(
            """<div id="a">x</div><hr id="h"><p id="p">y</p>""",
            "#a { margin: 4px auto; padding: 1em 2px 3px; border-left: 3px solid red; max-width: 50%; display: flex; justify-content: center; } hr { border: none; border-top: 1px solid #ccc; }",
        )
        val a = p.styles[p.find("a")]
        assertEquals(Length.Auto, a.margin.left)
        assertEquals(Length.Px(4f), a.margin.top)
        assertEquals(Length.Px(16f), a.padding.top)
        assertEquals(Length.Px(2f), a.padding.right)
        assertEquals(listOf(0f, 0f, 0f, 3f), a.borders.map { it.width })
        assertEquals(Color.Red, a.borders[3].color)
        assertEquals(Length.Percent(50f), a.maxWidth)
        assertEquals("flex", a.display)
        assertEquals("center", a.justifyContent)
        assertEquals(listOf(1f, 0f, 0f, 0f), p.styles[p.find("h")].borders.map { it.width })
        assertEquals(Length.Px(16f), p.styles[p.find("p")].margin.top)
        // the reviewer's own margins round the card
        assertEquals(Length.Px(20f), p.styles[p.body].padding.left)
    }

    @Test
    fun colours() {
        assertEquals(Color(0xFF336699), parseColor("#369", Color.Black))
        assertEquals(Color(0x80FF0000), parseColor("#ff000080", Color.Black))
        assertEquals(Color(10, 20, 30), parseColor("rgb(10 20 30)", Color.Black))
        assertEquals(Color.Red, parseColor("currentColor", Color.Red))
        assertEquals(Color(0xFFFFA500), parseColor("Orange", Color.Black))
        assertNull(parseColor("url(x.png)", Color.Black))
    }
}
