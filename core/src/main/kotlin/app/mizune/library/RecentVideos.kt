package app.mizune.library

import kotlin.math.roundToInt

/** The videos played last, for Continue watching at the top of the library, kept and named the same on the phone and the desktop. */
object RecentVideos {
    /** More than one is kept, since the last may have been finished or moved by the time the library looks. */
    const val KEPT = 12

    /** [recent] after playing [video]: it goes first, newest first, and it's in the list once. */
    fun played(video: String, recent: List<String>): List<String> =
        (listOf(video) + recent.filter { it != video }).take(KEPT)

    /**
     * How a video called [name] is named under Continue watching, among the names of the videos in its folder,
     * [siblings]: "Episode 3" and the like with [tidy] names (see [Titles.episodes]), its file name otherwise.
     */
    fun title(name: String, siblings: List<String>, tidy: Boolean): String {
        val index = siblings.indexOf(name)
        return if (tidy && index >= 0) Titles.episodes(siblings)[index] else name.substringBeforeLast('.')
    }

    /** The show name under Continue watching, from its folder's [folderTitle], or null at the top of the library or for a film alone in its folder, which isn't named twice. */
    fun show(folderTitle: String?, title: String): String? = folderTitle?.takeIf { it != title }

    fun timeLeft(seconds: Double): String {
        val minutes = (seconds / 60).roundToInt()
        return when {
            minutes < 1 -> "Under a minute left"
            minutes < 60 -> "$minutes min left"
            else -> "${minutes / 60} h ${minutes % 60} min left"
        }
    }
}
