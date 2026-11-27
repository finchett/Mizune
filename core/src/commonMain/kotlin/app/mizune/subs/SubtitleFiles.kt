package app.mizune.subs

/** Platform-free parts of subtitle loading: sidecar parsing, track choice, the embedded-track cache. */
object SubtitleFiles {
    val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")
    private val MATROSKA_EXTENSIONS = setOf("mkv", "mka", "webm")
    private val MP4_EXTENSIONS = setOf("mp4", "m4v", "mov")

    /**
     * A language users can pick, by its English [name] and its own [native] one, with the tags
     * subtitle files and Matroska tracks use for it (a region or script after them is ignored).
     */
    data class Language(val code: String, val name: String, val native: String, val tags: Set<String>)

    val LANGUAGES = listOf(
        Language("ar", "Arabic", "العربية", setOf("ar", "ara")),
        Language("zh", "Chinese", "中文", setOf("zh", "zho", "chi", "zh-cn", "zh-tw", "zh-hans", "zh-hant", "chs", "cht")),
        Language("nl", "Dutch", "Nederlands", setOf("nl", "nld", "dut")),
        Language("en", "English", "English", setOf("en", "eng", "en-us", "en-gb")),
        Language("fr", "French", "Français", setOf("fr", "fre", "fra", "fr-fr")),
        Language("de", "German", "Deutsch", setOf("de", "ger", "deu", "de-de")),
        Language("hi", "Hindi", "हिन्दी", setOf("hi", "hin")),
        Language("id", "Indonesian", "Indonesia", setOf("id", "ind", "in")),
        Language("it", "Italian", "Italiano", setOf("it", "ita")),
        Language("ja", "Japanese", "日本語", setOf("ja", "jpn", "jp", "ja-jp")),
        Language("ko", "Korean", "한국어", setOf("ko", "kor", "ko-kr")),
        Language("pl", "Polish", "Polski", setOf("pl", "pol")),
        Language("pt", "Portuguese", "Português", setOf("pt", "por", "pt-br", "pt-pt")),
        Language("ru", "Russian", "Русский", setOf("ru", "rus")),
        Language("es", "Spanish", "Español", setOf("es", "spa", "es-es", "es-419", "es-mx")),
        Language("th", "Thai", "ไทย", setOf("th", "tha")),
        Language("tr", "Turkish", "Türkçe", setOf("tr", "tur")),
        Language("uk", "Ukrainian", "Українська", setOf("uk", "ukr")),
        Language("vi", "Vietnamese", "Tiếng Việt", setOf("vi", "vie")),
    )

    fun language(code: String): Language? = LANGUAGES.firstOrNull { it.code == code }

    /** mpv's `alang` for [code]: every tag an audio track in that language might carry. */
    fun mpvLanguages(code: String): String = (language(code)?.tags ?: setOf(code)).joinToString(",")

    /**
     * The language code a tag like "jpn", "ja-JP" or "fr-CA" stands for, or the tag itself if
     * unknown. Null for no tag, and for the placeholders files carry instead of one ("und", "mul", ...).
     */
    fun codeForTag(tag: String?): String? {
        val lower = tag?.trim()?.lowercase()?.replace('_', '-')
            ?.takeUnless { it.isEmpty() || it.substringBefore('-') in NOT_A_LANGUAGE } ?: return null
        // BCP 47: the language comes first, then any script and region ("zh-Hant-TW", "es-419")
        return LANGUAGES.firstOrNull { lower in it.tags }?.code
            ?: LANGUAGES.firstOrNull { lower.substringBefore('-') in it.tags }?.code
            ?: lower
    }

    /** ISO 639 codes for "undetermined", "multiple", "no linguistic content" and the like. */
    private val NOT_A_LANGUAGE = setOf("und", "mul", "mis", "zxx", "unk", "qaa")

    /** A language tag at the end of a sidecar's name: "ja", "jpn", "pt-BR", "zh-Hant". */
    private val SIDECAR_TAG = Regex("[a-z]{2,3}([-_][a-z0-9]{2,8})*", RegexOption.IGNORE_CASE)

    /** Whether [track] is in [code], by its text when its tag is clearly wrong (see [LanguageDetector]). */
    fun isLanguage(track: SubtitleTrack, code: String): Boolean =
        effectiveLanguage(track) == code

    fun effectiveLanguage(track: SubtitleTrack): String? =
        LanguageDetector.effectiveLanguage(track, codeForTag(track.language))

    fun isJapanese(track: SubtitleTrack) = isLanguage(track, "ja")
    fun isEnglish(track: SubtitleTrack) = isLanguage(track, "en")

    fun isMatroska(fileName: String) = fileName.substringAfterLast('.', "").lowercase() in MATROSKA_EXTENSIONS

    fun isMp4(fileName: String) = fileName.substringAfterLast('.', "").lowercase() in MP4_EXTENSIONS

    /** Whether the text tracks inside [fileName] can be read: Matroska and WebM, MP4 and QuickTime. */
    fun hasReadableTracks(fileName: String) = isMatroska(fileName) || isMp4(fileName)

    /** True when [fileName] is a subtitle file named after the video [videoName]. */
    fun isSidecarFor(videoName: String, fileName: String): Boolean {
        val base = videoName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in SUBTITLE_EXTENSIONS && fileName.startsWith("$base.")
    }

    fun parseSidecar(fileName: String, content: String): SubtitleTrack? {
        val cues = when (fileName.substringAfterLast('.').lowercase()) {
            "ass", "ssa" -> AssParser.parse(content)
            "ttml", "dfxp", "xml" -> TtmlParser.parse(content)
            else -> SrtParser.parse(content)
        }
        if (cues.isEmpty()) return null
        // "Episode 1.ja.srt" -> "ja", "Episode 1.pt-BR.srt" -> "pt-BR"
        val language = fileName.substringBeforeLast('.').substringAfterLast('.', "").takeIf { it.matches(SIDECAR_TAG) }
        return SubtitleTrack(fileName, language, cues)
    }

    /**
     * The sidecar files, then the embedded tracks in languages no sidecar covers: a file put beside
     * the video is there on purpose, so it wins for its language, and the video's own tracks fill in
     * the rest (e.g. English inside the video for Japanese from a file).
     */
    fun withEmbedded(sidecars: List<SubtitleTrack>, embedded: List<SubtitleTrack>): List<SubtitleTrack> {
        val covered = sidecars.mapNotNull(::effectiveLanguage).toSet()
        return sidecars + embedded.filter { effectiveLanguage(it) !in covered }
    }

    /** Track in the language being studied; falls back to the first track. */
    fun pickPrimary(tracks: List<SubtitleTrack>, target: String = "ja"): SubtitleTrack? =
        // the fullest matching track: dialogue over signs-and-songs
        tracks.filter { isLanguage(it, target) }.maxByOrNull { it.cues.size } ?: tracks.firstOrNull()

    /** Full track in the [peek] language (null for none), skipping signs-only tracks. */
    fun pickSecondary(tracks: List<SubtitleTrack>, primary: SubtitleTrack?, peek: String? = "en"): SubtitleTrack? {
        if (peek == null) return null
        val candidates = tracks.filter { it !== primary && isLanguage(it, peek) }
        return candidates.firstOrNull { !it.name.contains("sign", ignoreCase = true) }
            ?: candidates.maxByOrNull { it.cues.size }
    }
}
