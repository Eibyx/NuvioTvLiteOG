package com.nuvio.tv.core.cloudstorage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CloudReleaseParserTest {
    private fun parse(name: String, vararg folders: String) = CloudReleaseParser.parse(name, folders.toList())

    @Test
    fun seasonEpisodeTag() {
        val release = parse("Anime.S02E12.mkv")
        assertEquals("Anime", release.title)
        assertEquals(2, release.season)
        assertEquals(12, release.episode)
        assertEquals("mkv", release.container)
    }

    @Test
    fun fansubDashNumberIsAnEpisodeWithoutSeason() {
        val release = parse("[Fansub] Anime - 12.mkv")
        assertEquals("Anime", release.title)
        assertEquals("Fansub", release.group)
        assertNull(release.season)
        assertEquals(12, release.episode)
    }

    @Test
    fun crossedNotation() {
        val release = parse("Anime 2x12.mp4")
        assertEquals("Anime", release.title)
        assertEquals(2, release.season)
        assertEquals(12, release.episode)
    }

    @Test
    fun spanishWords() {
        val release = parse("Anime Temporada 2 Episodio 12.mkv")
        assertEquals("Anime", release.title)
        assertEquals(2, release.season)
        assertEquals(12, release.episode)
        val dashed = parse("Clevatess - Temporada 2 - Episodio 12.mkv")
        assertEquals("Clevatess", dashed.title)
        assertEquals(2, dashed.season)
        assertEquals(12, dashed.episode)
    }

    @Test
    fun kotouraSanFromTheRealLibrary() {
        val release = parse("[Kashikoi Subs] Kotoura san 01 BD [1920x1080 AAC 448Kbps].mp4", "ANIME", "Kotoura san BD 1080")
        assertEquals("Kotoura san", release.title)
        assertEquals("Kashikoi Subs", release.group)
        assertEquals(1, release.episode)
        assertNull(release.season)
        assertEquals("1080p", release.resolution)
        assertEquals("AAC", release.audio)
        assertEquals("BD", release.source)
        assertEquals("mp4", release.container)
    }

    @Test
    fun clevatessVariants() {
        val dotted = parse("Clevatess.S02E12.1080p.mkv")
        assertEquals("Clevatess", dotted.title)
        assertEquals(2 to 12, dotted.season to dotted.episode)
        assertEquals("1080p", dotted.resolution)
        val fansub = parse("[Fansub] Clevatess - 12 [HEVC].mkv")
        assertEquals("Clevatess", fansub.title)
        assertEquals(12, fansub.episode)
        assertEquals("HEVC", fansub.videoCodec)
    }

    @Test
    fun resolutionYearBitrateAndVersionAreNotEpisodes() {
        val movie = parse("Your.Name.2016.1080p.BluRay.x264.DTS-5.1.mkv")
        assertEquals("Your Name", movie.title)
        assertEquals(2016, movie.year)
        assertNull(movie.episode)
        assertEquals("1080p", movie.resolution)
        assertEquals("H.264", movie.videoCodec)
        val withYear = parse("Kimi no Na wa (2016) [1080p].mkv")
        assertEquals("Kimi no Na wa", withYear.title)
        assertEquals(2016, withYear.year)
        assertNull(withYear.episode)
        val version = parse("[Group] Show - 05v2 [ABCD1234].mkv")
        assertEquals("Show", version.title)
        assertEquals(5, version.episode)
    }

    @Test
    fun folderGivesSeasonAndTitle() {
        val release = parse("Episode 03.mkv", "Series", "Clevatess", "Season 2")
        assertEquals("Clevatess", release.title)
        assertEquals(2, release.season)
        assertEquals(3, release.episode)
    }

    @Test
    fun numbersInsideTitlesBeforeTheDash() {
        val release = parse("[SubsPlease] Mob Psycho 100 III - 03 (1080p) [ABCDEF12].mkv")
        assertEquals("Mob Psycho 100 III", release.title)
        assertEquals(3, release.episode)
        assertEquals("1080p", release.resolution)
    }
}
