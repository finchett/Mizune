package app.mizune.anki

import org.w3c.xhr.XMLHttpRequest

/**
 * A synchronous request, which browsers still allow in a worker, where the extension runs this.
 * An extension may reach AnkiConnect from there with the host permission for its address.
 */
actual fun defaultAnkiTransport(): AnkiTransport = AnkiTransport { url, body ->
    val request = XMLHttpRequest()
    try {
        request.open("POST", url, false)
        request.setRequestHeader("Content-Type", "application/json; charset=utf-8")
        request.send(body)
    } catch (e: Throwable) {
        // the browser says only that the request failed: nothing listening, or blocked
        throw AnkiException("Anki isn't running, or AnkiConnect isn't installed", e)
    }
    if (request.status.toInt() !in 200..299) throw AnkiException("Couldn't reach Anki: ${request.status} ${request.statusText}")
    request.responseText
}
