package app.mizune.subs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XmlTest {
    @Test
    fun namespacesTextAndReferences() {
        val root = Xml.parse(
            """
            <?xml version="1.0"?>
            <!-- before -->
            <a:root xmlns:a="urn:a" xmlns="urn:default" a:x='1' y="&quot;2&quot;">
              <child>one &amp; two &#x732B;&#29356;<![CDATA[<raw>]]><!-- skipped --></child>
              <inner xmlns=""><plain/></inner>
            </a:root>
            """.trimIndent(),
        )!!
        assertEquals("urn:a", root.namespace)
        assertEquals("root", root.localName)
        assertEquals("1", root.attribute("urn:a", "x"))
        assertEquals("\"2\"", root.attribute("y"))
        val (child, inner, plain) = root.descendants().toList()
        assertEquals("urn:default", child.namespace)
        assertEquals("one & two 猫犬<raw>", (child.children.single() as Xml.Text).value)
        assertNull(inner.namespace)
        assertNull(plain.namespace)
        assertEquals(listOf(inner, root), plain.ancestors().toList())
    }

    @Test
    fun refusesWhatIsNotWellFormed() {
        for (text in listOf("", "text", "<a>", "<a></b>", "<a x=1/>", "<a x='1' x='2'/>", "<a>&nbsp;</a>", "<p:a/>", "<a/><b/>", "<!DOCTYPE a><a/>")) {
            assertNull(Xml.parse(text), text)
        }
    }
}
