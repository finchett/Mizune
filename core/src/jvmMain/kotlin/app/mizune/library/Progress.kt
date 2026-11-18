package app.mizune.library

/** How far through the videos are, for a folder's card, kept and worded the same on the phone and the desktop. */
object Progress {
    /** How much of a video is behind you, 0 to 1: a watched one counts whole, even when it's being watched again. */
    fun share(position: Double, duration: Double, watched: Boolean): Float = when {
        watched -> 1f
        duration > 0 -> (position / duration).toFloat().coerceIn(0f, 1f)
        else -> 0f
    }

    /** A folder's line under its name: how many videos it has, and how many of those are watched. */
    fun folderDetail(videos: Int, watched: Int): String? {
        val count = if (videos == 1) "1 video" else "$videos videos"
        return when {
            videos == 0 -> null
            watched == 0 -> count
            watched >= videos -> "$count · all watched"
            else -> "$count · $watched watched"
        }
    }
}
