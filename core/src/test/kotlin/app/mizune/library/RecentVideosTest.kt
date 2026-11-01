package app.mizune.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentVideosTest {
    @Test
    fun playedGoesFirstOnce() {
        assertEquals(listOf("b", "a", "c"), RecentVideos.played("b", listOf("a", "b", "c")))
        assertEquals(listOf("a"), RecentVideos.played("a", emptyList()))
    }

    @Test
    fun keepsTwelve() {
        val recent = (1..12).map { "v$it" }
        val after = RecentVideos.played("new", recent)
        assertEquals(RecentVideos.KEPT, after.size)
        assertEquals("new", after.first())
        assertEquals("v11", after.last())
    }

    @Test
    fun titlesAnEpisodeAmongItsSiblings() {
        val siblings = listOf(
            "[Made-Up Subs] Paper Lanterns - 01 [1080p].mkv",
            "[Made-Up Subs] Paper Lanterns - 02 [1080p].mkv",
        )
        assertEquals("Episode 2", RecentVideos.title(siblings[1], siblings, tidy = true))
        assertEquals("[Made-Up Subs] Paper Lanterns - 02 [1080p]", RecentVideos.title(siblings[1], siblings, tidy = false))
        assertEquals("Lesson 4", RecentVideos.title("Lesson 4.mp4", emptyList(), tidy = true))
    }

    @Test
    fun aFilmAloneIsNamedOnce() {
        assertNull(RecentVideos.show("Quiet Harbour", "Quiet Harbour"))
        assertNull(RecentVideos.show(null, "Lesson 4"))
        assertEquals("Paper Lanterns", RecentVideos.show("Paper Lanterns", "Episode 2"))
    }

    @Test
    fun timeLeft() {
        assertEquals("Under a minute left", RecentVideos.timeLeft(20.0))
        assertEquals("12 min left", RecentVideos.timeLeft(12 * 60.0))
        assertEquals("1 h 5 min left", RecentVideos.timeLeft(65 * 60.0))
    }

    @Test
    fun theCreditsCountAsWatched() {
        val episode = 24 * 60.0
        assertTrue(RecentVideos.finished(episode - 120, episode))
        assertFalse(RecentVideos.finished(episode - 180, episode))
        // a film's last tenth would be its ending too, so five minutes at most
        val film = 120 * 60.0
        assertTrue(RecentVideos.finished(film - 240, film))
        assertFalse(RecentVideos.finished(film - 360, film))
        assertFalse(RecentVideos.finished(10.0, 0.0))
    }

    @Test
    fun aLookIsntWatching() {
        val episode = 24 * 60.0
        assertTrue(RecentVideos.skimmed(45.0, episode))
        assertFalse(RecentVideos.skimmed(75.0, episode))
        // half a minute of a one-minute clip is more than a look
        assertFalse(RecentVideos.skimmed(30.0, 60.0))
        assertTrue(RecentVideos.skimmed(10.0, 60.0))
        // a sitting played before the length was known isn't thrown away
        assertFalse(RecentVideos.skimmed(10.0, 0.0))
    }

}
