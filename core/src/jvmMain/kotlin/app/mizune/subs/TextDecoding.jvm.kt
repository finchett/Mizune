package app.mizune.subs

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal actual object TextDecoding {
    actual fun supported(names: List<String>): String? =
        names.firstOrNull { runCatching { Charset.isSupported(it) }.getOrDefault(false) }

    actual fun decode(bytes: ByteArray, offset: Int, charset: String, strict: Boolean): String? {
        val decoder = Charset.forName(charset).newDecoder().apply {
            val action = if (strict) CodingErrorAction.REPORT else CodingErrorAction.REPLACE
            onMalformedInput(action)
            onUnmappableCharacter(action)
        }
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
