package app.mizune.dictionary

/** A dictionary zip as each platform has it: an InputStream on the JVM, the file's bytes in a browser. */
expect abstract class ZipSource

/** Calls [entry] with the path and a reader of each file in [source], in the zip's order. */
internal expect fun readZip(source: ZipSource, entry: (path: String, read: () -> ByteArray) -> Unit)
