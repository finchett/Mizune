package app.mizune.media

import app.mizune.subs.SubtitleFiles

/** An audio track as mpv lists it; [id] is what mpv's `aid` takes. */
data class AudioTrack(val id: Int, val language: String?, val title: String?, val codec: String?) {

    /** "Japanese · Main · aac": the language by name where known, then whatever else the file says. */
    val label: String
        get() = listOfNotNull(languageName, title, codec).distinct().joinToString(" · ").ifEmpty { "Track $id" }

    /** What a list calls it: the language by name where known, else the file's title for it, else its number. */
    val name: String
        get() = languageName ?: title ?: "Track $id"

    /**
     * What else tells it apart, beside [name]: its title without the language it starts with
     * ("Japanese: Stereo" gives "Stereo"), then its codec in capitals. Null when there's nothing.
     */
    val detail: String?
        get() {
            val rest = title?.let { title -> languageName?.let { withoutLead(title, it) } ?: title }?.takeUnless { it == name }
            return listOfNotNull(rest, codec?.uppercase()).distinct().joinToString(" · ").ifEmpty { null }
        }

    private val languageName: String?
        get() = language?.let { tag -> SubtitleFiles.codeForTag(tag)?.let(SubtitleFiles::language)?.name ?: tag }

    companion object {
        /** [text] without [lead] at its start, and the punctuation after it; null if that's all there is. */
        internal fun withoutLead(text: String, lead: String): String? {
            if (!text.startsWith(lead, ignoreCase = true)) return text
            return text.drop(lead.length).trimStart(' ', ':', '-', '–', '·', ',', '/').ifEmpty { null }
        }

        /** The audio tracks in mpv's `track-list`, read through [property] (mpv's property as a string). */
        fun fromMpv(property: (String) -> String?): List<AudioTrack> {
            val count = property("track-list/count")?.toIntOrNull() ?: return emptyList()
            return (0 until count).mapNotNull { i ->
                if (property("track-list/$i/type") != "audio") return@mapNotNull null
                val id = property("track-list/$i/id")?.toIntOrNull() ?: return@mapNotNull null
                AudioTrack(
                    id = id,
                    language = property("track-list/$i/lang")?.takeIf { it.isNotBlank() },
                    title = property("track-list/$i/title")?.takeIf { it.isNotBlank() },
                    codec = property("track-list/$i/codec")?.takeIf { it.isNotBlank() },
                )
            }
        }
    }
}
