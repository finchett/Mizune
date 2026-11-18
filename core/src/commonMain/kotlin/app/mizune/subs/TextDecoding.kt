package app.mizune.subs

/** Text in the old code pages, as each platform decodes it: java.nio.charset on the JVM, TextDecoder in a browser. */
internal expect object TextDecoding {
    /** The first of [names] that can be decoded here, or null if none can. */
    fun supported(names: List<String>): String?

    /** [bytes] from [offset] on, in [charset]; when [strict], null if they aren't valid in it. */
    fun decode(bytes: ByteArray, offset: Int, charset: String, strict: Boolean): String?
}
