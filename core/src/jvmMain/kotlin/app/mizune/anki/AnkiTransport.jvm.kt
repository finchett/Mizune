package app.mizune.anki

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URI

actual fun defaultAnkiTransport(): AnkiTransport = AnkiTransport { url, body ->
    try {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000
        // adding a note can run other add-ons' hooks (furigana, pitch), which take a moment
        connection.readTimeout = 30_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(body.toByteArray()) }
        connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: ConnectException) {
        throw AnkiException("Anki isn't running, or AnkiConnect isn't installed", e)
    } catch (e: IOException) {
        throw AnkiException("Couldn't reach Anki: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw AnkiException("Not a valid address: $url", e)
    }
}
