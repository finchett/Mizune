package app.mizune.subs

/** A language tag in brackets at the end of a track's name: "(ja)", "[eng]", "(pt-BR)". */
private val TRAILING_TAG = Regex("""\s*[(\[]([a-z]{2,3}(?:[-_][a-z0-9]{2,8})*)[)\]]\s*$""", RegexOption.IGNORE_CASE)

/**
 * The track's name for a list of tracks, without a language tag at its end that only repeats
 * its [SubtitleTrack.language]: "Dialogue (ja)" in Japanese is "Dialogue".
 */
val SubtitleTrack.shortName: String
    get() {
        val tag = TRAILING_TAG.find(name) ?: return name
        val same = SubtitleFiles.codeForTag(tag.groupValues[1]) == SubtitleFiles.codeForTag(language)
        return if (same) name.removeRange(tag.range).ifBlank { name } else name
    }

/** What tells it apart beside [shortName]: its language by name, unless the name says it, then how many lines it has. */
val SubtitleTrack.detail: String
    get() {
        val code = SubtitleFiles.codeForTag(language)
        val languageName = code?.let { SubtitleFiles.language(it)?.name ?: it }
            ?.takeUnless { shortName.contains(it, ignoreCase = true) }
        val lines = if (cues.size == 1) "1 line" else "${cues.size} lines"
        return listOfNotNull(languageName, lines).joinToString(" · ")
    }
