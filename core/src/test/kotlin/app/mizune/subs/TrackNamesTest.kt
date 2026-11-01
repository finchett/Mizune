package app.mizune.subs

import app.mizune.media.AudioTrack
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class TrackNamesTest {
    private fun track(name: String, language: String?, lines: Int = 3) =
        SubtitleTrack(name, language, List(lines) { Cue(it.toDouble(), it + 1.0, "line $it") })

    @Test
    fun aTagRepeatingTheLanguageIsDropped() {
        assertEquals("Dialogue", track("Dialogue (ja)", "jpn").shortName)
        assertEquals("Full [eng]", track("Full [eng]", "ja").shortName)
        assertEquals("Signs (Forced)", track("Signs (Forced)", "en").shortName)
        assertEquals("(ja)", track("(ja)", "ja").shortName)
    }

    @Test
    fun theDetailNamesTheLanguageOnlyWhenTheNameDoesNot() {
        assertEquals("Japanese · 3 lines", track("Dialogue (ja)", "ja").detail)
        assertEquals("120 lines", track("English Full", "en", lines = 120).detail)
        assertEquals("1 line", track("Notes", null, lines = 1).detail)
    }

    @Test
    fun audioTracksAreNamedByLanguageWithTheRestAfter() {
        val japanese = AudioTrack(1, "jpn", "Japanese: Stereo", "ac3")
        assertEquals("Japanese", japanese.name)
        assertEquals("Stereo · AC3", japanese.detail)
        val commentary = AudioTrack(2, "en", "Commentary", "aac")
        assertEquals("English", commentary.name)
        assertEquals("Commentary · AAC", commentary.detail)
        val bare = AudioTrack(3, null, "Japanese", null)
        assertEquals("Japanese", bare.name)
        assertNull(bare.detail)
        assertEquals("Track 4", AudioTrack(4, null, null, null).name)
    }
}
