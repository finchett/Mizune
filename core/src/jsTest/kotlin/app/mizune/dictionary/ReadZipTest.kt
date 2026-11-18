package app.mizune.dictionary

import org.khronos.webgl.Uint8Array
import kotlin.test.Test
import kotlin.test.assertEquals

@JsModule("fflate")
@JsNonModule
private external object FflateZip {
    fun zipSync(files: dynamic): Uint8Array
    fun strToU8(text: String): Uint8Array
}

class ReadZipTest {
    @Test
    fun readsEachFileInTheZipsOrder() {
        val files: dynamic = js("({})")
        files["index.json"] = FflateZip.strToU8("""{"title":"Test"}""")
        files["folder/"] = Uint8Array(0)
        files["folder/term_bank_1.json"] = FflateZip.strToU8("""[["猫","ねこ"]]""")
        val read = mutableListOf<Pair<String, String>>()
        readZip(ZipBytes(FflateZip.zipSync(files))) { path, bytes -> read += path to bytes().decodeToString() }
        assertEquals(listOf("index.json" to """{"title":"Test"}""", "folder/term_bank_1.json" to """[["猫","ねこ"]]"""), read)
    }
}
