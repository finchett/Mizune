package app.mizune.subs

import org.khronos.webgl.Int8Array

internal actual object TextDecoding {
    actual fun supported(names: List<String>): String? = names.firstOrNull { name ->
        try {
            js("new TextDecoder(name)")
            true
        } catch (_: Throwable) {
            false
        }
    }

    actual fun decode(bytes: ByteArray, offset: Int, charset: String, strict: Boolean): String? {
        // a ByteArray is an Int8Array underneath
        val view = bytes.unsafeCast<Int8Array>().subarray(offset, bytes.size)
        return try {
            val decoder: dynamic = js("new TextDecoder(charset, { fatal: strict })")
            decoder.decode(view) as String
        } catch (_: Throwable) {
            null
        }
    }
}
