package app.mizune.dictionary

import java.util.zip.ZipInputStream

actual typealias ZipSource = java.io.InputStream

internal actual fun readZip(source: ZipSource, entry: (path: String, read: () -> ByteArray) -> Unit) {
    ZipInputStream(source.buffered()).use { zip ->
        while (true) {
            val next = zip.nextEntry ?: break
            entry(next.name) { zip.readBytes() }
        }
    }
}
