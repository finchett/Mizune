package app.mizune.dictionary

/**
 * Yomitan dictionaries worth having beside the bundled JMdict, downloaded from where their makers
 * publish them. Once installed, each is known by the start of its title.
 */
object DictionaryCatalog {
    data class Entry(
        val id: String,
        val name: String,
        val description: String,
        val url: String,
        /** About how big the download is, to say before it starts. */
        val megabytes: Int,
        val titlePrefix: String,
        val recommended: Boolean,
    )

    val JITENDEX = Entry(
        id = "jitendex",
        name = "Jitendex",
        description = "Fuller English definitions, with example sentences. Takes over from the built-in JMdict.",
        url = "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip",
        megabytes = 39,
        titlePrefix = "Jitendex",
        recommended = true,
    )
    val JPDB = Entry(
        id = "jpdb",
        name = "JPDB frequency",
        description = "How common each word is, counted across anime, dramas, novels and games.",
        url = "https://github.com/Kuuuube/yomitan-dictionaries/releases/download/yomitan-permalink/JPDB_v2.2_Frequency_Kana.zip",
        megabytes = 6,
        titlePrefix = "JPDB",
        recommended = true,
    )
    val JMNEDICT = Entry(
        id = "jmnedict",
        name = "JMnedict",
        description = "Names of people and places, for the ones a regular dictionary doesn't have.",
        url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMnedict.zip",
        megabytes = 11,
        titlePrefix = "JMnedict",
        recommended = false,
    )
    val ENTRIES = listOf(JITENDEX, JPDB, JMNEDICT)

    /** The bundled JMdict's title starts so; Jitendex turns it off. */
    const val JMDICT_PREFIX = "JMdict"

    fun installed(entry: Entry, dictionaries: List<DictionaryInfo>): DictionaryInfo? =
        dictionaries.firstOrNull { it.title.startsWith(entry.titlePrefix) }
}
