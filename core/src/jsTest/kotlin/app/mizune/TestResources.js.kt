package app.mizune

import org.khronos.webgl.Int8Array

@JsModule("node:fs")
private external object Fs {
    fun readFileSync(path: dynamic): dynamic
}

/** The build copies the resources next to the compiled tests. */
actual fun testResource(path: String): ByteArray {
    val buffer = Fs.readFileSync(js("new URL(path, import.meta.url)"))
    return Int8Array(buffer.buffer, buffer.byteOffset, buffer.length).unsafeCast<ByteArray>()
}
