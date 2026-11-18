package app.mizune.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTest {
    @Test
    fun aWatchedVideoCountsWhole() {
        assertEquals(1f, Progress.share(0.0, 1440.0, watched = true))
        assertEquals(0.25f, Progress.share(360.0, 1440.0, watched = false))
        assertEquals(0f, Progress.share(360.0, 0.0, watched = false))
    }

    @Test
    fun foldersSayHowManyAreWatched() {
        assertNull(Progress.folderDetail(0, 0))
        assertEquals("1 video", Progress.folderDetail(1, 0))
        assertEquals("12 videos · 5 watched", Progress.folderDetail(12, 5))
        assertEquals("12 videos · all watched", Progress.folderDetail(12, 12))
    }
}
