package app.mizune.dictionary

import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** Downloads [entry] to [target], telling [onProgress] the fraction done (0 while the size is unknown). */
fun DictionaryCatalog.download(entry: DictionaryCatalog.Entry, target: File, onProgress: (Float) -> Unit) {
    target.parentFile?.mkdirs()
    val part = File(target.path + ".part")
    val connection = URI(entry.url).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 30_000
    try {
        if (connection.responseCode !in 200..299) error("${entry.name}: HTTP ${connection.responseCode}")
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
    } finally {
        connection.disconnect()
    }
    if (!part.renameTo(target)) {
        part.copyTo(target, overwrite = true)
        part.delete()
    }
}
