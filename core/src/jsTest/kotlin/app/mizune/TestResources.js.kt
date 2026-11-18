package app.mizune

import org.khronos.webgl.Int8Array

private val fs: dynamic = js("require('fs')")

/** The build copies the resources next to the compiled tests. */
actual fun testResource(path: String): ByteArray {
    val buffer = fs.readFileSync(js("__dirname") as String + "/" + path)
    return Int8Array(buffer.buffer, buffer.byteOffset, buffer.length).unsafeCast<ByteArray>()
}
