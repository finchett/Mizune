package io.github.immersionplayer.library

import org.junit.Assert.assertEquals
import org.junit.Test

class TitlesTest {
    private fun clean(name: String) = Titles.clean(name)

    @Test
    fun dropsReleaseTagsFromFolderNames() {
        assertEquals("Kumo-Ten!", clean("[SomeSubs] Kumo-Ten! (1080p BD 10bit) [ABCD1234]"))
        assertEquals("Kumo-Ten! (2009)", clean("[Group] Kumo-Ten! (2009) [BD 1080p x265 FLAC]"))
        assertEquals("Tabibito", clean("[OtherSubs] Tabibito (Complete) [1080p]"))
        assertEquals("Tooi no Tabibito Season 1", clean("Tooi.no.Tabibito.S01.1080p.WEB-DL.H.264-GROUP"))
        assertEquals("Ame the Band!", clean("Ame the Band! [BDRip 1080p HEVC Dual-Audio]"))
        assertEquals("Hibike! Euphonium 2", clean("Hibike!_Euphonium_2_[Hi10P]"))
        assertEquals("Mushishi", clean("【字幕组】Mushishi【1080p】"))
        assertEquals("Yuru Camp (Season 2)", clean("[Judas] Yuru Camp (Season 2) [1080p][HEVC x265 10bit][Eng-Subs]"))
        assertEquals("Weathering With You 2019", clean("Weathering.With.You.2019.1080p.BluRay.x264-GROUP"))
        assertEquals("Steins;Gate", clean("Steins;Gate (BD 1080p Dual Audio)"))
    }

    @Test
    fun episodesWithTheirOwnTags() {
        val names = listOf(
            "[Some-raws] Kawa no Hotori - 01 [1080p][Multiple Subtitle][ABCD1234].mkv",
            "[Some-raws] Kawa no Hotori - 02 END [1080p][Multiple Subtitle][ABCD1235].mkv",
        )
        assertEquals(listOf("Episode 1", "Episode 2"), Titles.episodes(names))
    }

    @Test
    fun leavesPlainNamesAlone() {
        assertEquals("Lectures", clean("Lectures"))
        assertEquals("Season 1", clean("Season 1"))
        assertEquals("Dr. Stone", clean("Dr. Stone"))
        assertEquals("Charlotte's Web", clean("Charlotte's Web"))
        assertEquals("ゆるキャン△", clean("ゆるキャン△"))
        assertEquals("Lesson 10", clean("Lesson 10.mkv"))
    }

    @Test
    fun cleansEpisodeFiles() {
        assertEquals("Kumo-Ten! - 01", clean("[SomeSubs] Kumo-Ten! - 01 (1080p bd1230) [5E6F7A8B].mkv"))
        assertEquals("Kumo-Ten! - 01", clean("[SomeSubs] Kumo-Ten! - 01v2 [1080p].mkv"))
        assertEquals("Tabibito S01E05 The Long Road", clean("Tabibito.S01E05.The.Long.Road.1080p.WEB.H264-GROUP.mkv"))
    }

    @Test
    fun aNameMadeOnlyOfBracketsKeepsItsTitle() {
        assertEquals("Ame the Band! 01", clean("[Neko fansub][Ame the Band!][01][1080p][CHS].mp4"))
    }

    @Test
    fun episodesDropTheSeriesTheyShare() {
        val names = listOf(
            "[SomeSubs] Kumo-Ten! - 01 (1080p BD) [5E6F7A8B].mkv",
            "[SomeSubs] Kumo-Ten! - 02 (1080p BD) [1B2C3D4E].mkv",
            "[SomeSubs] Kumo-Ten! - 10 (1080p BD) [AA00BB11].mkv",
        )
        assertEquals(listOf("Episode 1", "Episode 2", "Episode 10"), Titles.episodes(names))
    }

    @Test
    fun episodeTitlesAreKept() {
        val names = listOf(
            "Tabibito.S01E05.The.Long.Road.1080p.WEB.H264-GROUP.mkv",
            "Tabibito.S01E06.A.Quiet.Town.1080p.WEB.H264-GROUP.mkv",
        )
        assertEquals(
            listOf("Episode 5 · The Long Road", "Episode 6 · A Quiet Town"),
            Titles.episodes(names),
        )
    }

    @Test
    fun episodesFromSeveralSeasonsSayWhichSeason() {
        val names = listOf("Show S01E12.mkv", "Show S02E01.mkv")
        assertEquals(listOf("S1 E12", "S2 E1"), Titles.episodes(names))
    }

    @Test
    fun japaneseEpisodeNumbers() {
        val names = listOf("けいおん！ 第1話.mkv", "けいおん！ 第2話.mkv")
        assertEquals(listOf("Episode 1", "Episode 2"), Titles.episodes(names))
    }

    @Test
    fun differentVideosKeepTheirOwnTitles() {
        val names = listOf("Lesson 1.mkv", "Lesson 2.mkv", "Lesson 10.mkv")
        assertEquals(listOf("Lesson 1", "Lesson 2", "Lesson 10"), Titles.episodes(names))
        assertEquals(listOf("Your Name (2016)"), Titles.episodes(listOf("Your Name (2016) [BD 1080p].mkv")))
        assertEquals(
            listOf("Paper Lanterns", "Moss Garden"),
            Titles.episodes(listOf("Paper Lanterns [1080p].mkv", "Moss Garden [1080p].mkv")),
        )
    }
}
