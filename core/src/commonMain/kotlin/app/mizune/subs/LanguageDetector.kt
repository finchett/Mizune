package app.mizune.subs

/**
 * Guesses a subtitle track's language from its text, for files whose tags are wrong.
 * Each line votes by script (Japanese, Korean, Chinese, Cyrillic, Arabic, Thai, Devanagari, or
 * Latin); Latin-script languages are then told apart by counting a handful of very common words,
 * and Russian from Ukrainian by the letters only one of them has, which is weaker, so tags win there.
 */
object LanguageDetector {

    private val STOPWORDS = mapOf(
        "en" to setOf("the", "you", "and", "is", "it", "to", "what", "that", "this", "i'm", "don't", "of"),
        "es" to setOf("que", "el", "los", "las", "es", "por", "qué", "pero", "una", "está", "y", "no"),
        "fr" to setOf("le", "les", "et", "est", "je", "vous", "pas", "une", "des", "c'est", "qu'il", "tu"),
        "de" to setOf("der", "die", "und", "ist", "nicht", "ich", "das", "du", "ein", "zu", "was", "wir"),
        "pt" to setOf("que", "não", "você", "uma", "os", "é", "eu", "está", "com", "isso", "o", "do"),
        "it" to setOf("che", "non", "il", "è", "sono", "una", "di", "per", "questo", "cosa", "io", "ma"),
        "nl" to setOf("het", "een", "niet", "ik", "je", "dat", "wat", "van", "zijn", "maar", "hij", "ze"),
        "pl" to setOf("nie", "się", "jest", "że", "co", "na", "jak", "mnie", "tak", "czy", "ale", "jestem"),
        "tr" to setOf("bir", "bu", "ne", "ve", "değil", "ben", "sen", "mi", "için", "çok", "var", "şey"),
        "id" to setOf("yang", "tidak", "aku", "kau", "ini", "itu", "dan", "apa", "kamu", "saya", "ada", "akan"),
        "vi" to setOf("không", "là", "của", "và", "có", "tôi", "được", "này", "người", "anh", "em", "một"),
    )

    /** Letters only one of Russian and Ukrainian uses. */
    private const val RUSSIAN_ONLY = "ыэъё"
    private const val UKRAINIAN_ONLY = "іїєґ"

    private const val SAMPLE_CUES = 300
    private const val MIN_LINES = 3
    private const val MAJORITY = 0.6
    private const val LATIN = "latin"
    private const val CYRILLIC = "cyrillic"

    /**
     * The language most of this track's lines are in, or null when there's too little text or no
     * clear majority. Voting per line keeps an English track with Japanese song lyrics English.
     */
    fun detect(track: SubtitleTrack): String? {
        val votes = HashMap<String, Int>()
        val latinText = StringBuilder()
        val cyrillicText = StringBuilder()
        for (cue in track.cues.asSequence().take(SAMPLE_CUES)) {
            val script = scriptOf(cue.text) ?: continue
            votes[script] = (votes[script] ?: 0) + 1
            if (script == LATIN) latinText.append(cue.text).append('\n')
            if (script == CYRILLIC) cyrillicText.append(cue.text).append('\n')
        }
        val total = votes.values.sum()
        if (total < MIN_LINES) return null
        val (winner, count) = votes.maxByOrNull { it.value } ?: return null
        if (count < total * MAJORITY) return null
        return when (winner) {
            LATIN -> latinLanguage(latinText.toString())
            CYRILLIC -> cyrillicLanguage(cyrillicText.toString())
            else -> winner
        }
    }

    /** The language of one piece of text. */
    fun detect(text: String): String? = when (val script = scriptOf(text)) {
        LATIN -> latinLanguage(text)
        CYRILLIC -> cyrillicLanguage(text)
        else -> script
    }

    private fun scriptOf(text: String): String? {
        var kana = 0
        var han = 0
        var hangul = 0
        var cyrillic = 0
        var arabic = 0
        var thai = 0
        var devanagari = 0
        var latin = 0
        for (match in SCRIPTS.findAll(text)) {
            val groups = match.groups
            when {
                groups[1] != null -> kana++
                groups[2] != null -> han++
                groups[3] != null -> hangul++
                groups[4] != null -> cyrillic++
                groups[5] != null -> arabic++
                groups[6] != null -> thai++
                groups[7] != null -> devanagari++
                groups[8] != null -> latin++
            }
        }
        val letters = kana + han + hangul + cyrillic + arabic + thai + devanagari + latin
        if (letters < 2) return null
        return when {
            // Japanese always mixes in kana
            kana > 0 && kana + han >= latin -> "ja"
            hangul * 2 > letters -> "ko"
            han * 2 > letters -> "zh"
            cyrillic * 2 > letters -> CYRILLIC
            arabic * 2 > letters -> "ar"
            thai * 2 > letters -> "th"
            devanagari * 2 > letters -> "hi"
            latin * 2 > letters -> LATIN
            else -> null
        }
    }

    /** One letter of each script that counts, in the groups [scriptOf] reads (sc= is the same on the JVM and in JavaScript). */
    private val SCRIPTS = Regex(
        """([\p{sc=Hiragana}\p{sc=Katakana}])|(\p{sc=Han})|(\p{sc=Hangul})|(\p{sc=Cyrillic})|(\p{sc=Arabic})|""" +
            """(\p{sc=Thai})|(\p{sc=Devanagari})|(\p{sc=Latin})""",
    )

    private fun latinLanguage(text: String): String {
        val words = text.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        val scores = STOPWORDS.mapValues { (_, stop) -> words.count { it in stop } }
        return scores.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: "en"
    }

    private fun cyrillicLanguage(text: String): String {
        val lower = text.lowercase()
        val ukrainian = lower.count { it in UKRAINIAN_ONLY }
        val russian = lower.count { it in RUSSIAN_ONLY }
        return if (ukrainian > russian) "uk" else "ru"
    }

    /**
     * The script a language is written in, as far as telling languages apart goes: Japanese and
     * Chinese count as two, since kana settles which is which. Unknown codes count as Latin.
     */
    private fun scriptFor(code: String): String = when (code) {
        "ja", "zh", "ko", "ar", "th", "hi" -> code
        "ru", "uk" -> CYRILLIC
        else -> LATIN
    }

    /**
     * The language to treat [track] as: its tag, unless the text is clearly in another script
     * (a "jpn" tag on English lines) or Japanese/Chinese are confused (kana decides).
     */
    fun effectiveLanguage(track: SubtitleTrack, tagCode: String?): String? {
        val detected = detect(track) ?: return tagCode
        if (tagCode == null) return detected
        return if (scriptFor(tagCode) != scriptFor(detected)) detected else tagCode
    }
}
