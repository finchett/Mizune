package app.mizune.browser

import org.khronos.webgl.Uint8Array
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@JsModule("@sqlite.org/sqlite-wasm")
private external fun sqlite3InitModule(): Promise<dynamic>

@JsModule("fflate")
private external object EngineZip {
    fun zipSync(files: dynamic): Uint8Array
    fun strToU8(text: String): Uint8Array
}

/** The extension's own engine over the real SQLite WebAssembly, in memory. */
class LocalEngineTest {
    private fun dictionary(): Uint8Array {
        val files: dynamic = js("({})")
        files["index.json"] = EngineZip.strToU8("""{"title":"Test","revision":"1","format":3}""")
        files["term_bank_1.json"] = EngineZip.strToU8("""[["食べる","たべる","v1","v1",0,["to eat"],1,""],["猫","ねこ","n","",0,["cat"],2,""]]""")
        files["term_meta_bank_1.json"] = EngineZip.strToU8("""[["食べる","freq",120],["猫","freq",{"value":9,"displayValue":"9★"}]]""")
        return EngineZip.zipSync(files)
    }

    @Test
    fun importsLooksUpAndManagesDictionaries(): Promise<Unit> = sqlite3InitModule().then<Unit> { sqlite3: dynamic ->
        val db = js("new sqlite3.oo1.DB(':memory:')")
        val engine = LocalEngine(db)
        val progress = mutableListOf<String>()
        val imported = JSON.parse<dynamic>(engine.importDictionary("test.zip", dictionary()) { progress += it })
        assertEquals("dictionaries", imported.type)
        assertEquals("Test", imported.imported.title)
        assertTrue(progress.isNotEmpty())

        val hello = JSON.parse<dynamic>(engine.handle("""{"type":"hello","id":"7","settings":{"anki":false,"lineSize":40}}"""))
        assertEquals("7", hello.id)
        assertEquals(1, hello.dictionaries)
        assertEquals(40, hello.lineSize)
        assertEquals(true, hello.local)

        val lookup = JSON.parse<dynamic>(engine.handle("""{"type":"lookup","text":"食べさせられなかった","start":0}"""))
        assertEquals(10, lookup.matchLength)
        assertEquals("食べる", lookup.entries[0].expression)
        assertEquals("120", lookup.entries[0].frequencies[0].display)

        val cat = JSON.parse<dynamic>(engine.handle("""{"type":"lookup","text":"猫だ","start":0}"""))
        assertEquals("9★", cat.entries[0].frequencies[0].display)

        // the panel's word for a line: the first kanji that has an entry, past the kana before it
        val line = JSON.parse<dynamic>(engine.handle("""{"type":"lookupLine","text":"ほら、猫だ"}"""))
        assertEquals(3, line.start)
        assertEquals("猫", line.entries[0].expression)
        val none = JSON.parse<dynamic>(engine.handle("""{"type":"lookupLine","text":"♪～"}"""))
        assertEquals(0, none.entries.length)

        val list = JSON.parse<dynamic>(engine.handle("""{"type":"dictionaries"}"""))
        val id = list.dictionaries[0].id
        engine.handle("""{"type":"enableDictionary","id":$id,"enabled":false}""")
        assertEquals(0, JSON.parse<dynamic>(engine.handle("""{"type":"hello"}""")).dictionaries)
        assertEquals(0, JSON.parse<dynamic>(engine.handle("""{"type":"deleteDictionary","id":$id}""")).dictionaries.length)

        val refused = JSON.parse<dynamic>(engine.handle("""{"type":"addCard","settings":{"anki":false}}"""))
        assertEquals("error", refused.type)
        Unit
    }

    @Test
    fun subtitlesAreReadAsTheAppReadsThem(): Promise<Unit> = sqlite3InitModule().then<Unit> { sqlite3: dynamic ->
        val engine = LocalEngine(js("new sqlite3.oo1.DB(':memory:')"))
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="1s" end="2s">はい</p></body></tt>"""
        val data = kotlin.io.encoding.Base64.encode(ttml.encodeToByteArray())
        val reply = JSON.parse<dynamic>(engine.handle("""{"type":"subtitles","name":"x.ja.ttml","data":"$data"}"""))
        assertEquals("はい", reply.cues[0].text)
        assertEquals(1, reply.cues[0].start)
        Unit
    }
}
