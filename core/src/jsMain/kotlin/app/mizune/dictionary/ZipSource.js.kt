package app.mizune.dictionary

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

actual abstract class ZipSource

/** A dictionary zip's bytes, as the browser read them from the file the user chose. */
class ZipBytes(val data: Uint8Array) : ZipSource()

/**
 * The zip's files, inflated one at a time: a big dictionary unpacks to a few hundred megabytes,
 * more than is worth holding at once.
 */
internal actual fun readZip(source: ZipSource, entry: (path: String, read: () -> ByteArray) -> Unit) {
    val data = (source as ZipBytes).data
    val names = mutableListOf<String>()
    // the filter sees every file in the zip's own order; nothing is inflated while listing
    val listing: dynamic = js("({})")
    listing.filter = { file: dynamic -> names.add(file.name as String); false }
    Fflate.unzipSync(data, listing)
    for (name in names) {
        if (name.endsWith('/')) continue
        entry(name) {
            val options: dynamic = js("({})")
            options.filter = { file: dynamic -> file.name == name }
            val bytes: Uint8Array = Fflate.unzipSync(data, options)[name]
            Int8Array(bytes.buffer, bytes.byteOffset, bytes.length).unsafeCast<ByteArray>()
        }
    }
}

@JsModule("fflate")
@JsNonModule
@JsName("fflate")
private external object Fflate {
    fun unzipSync(data: Uint8Array, options: dynamic): dynamic
}
