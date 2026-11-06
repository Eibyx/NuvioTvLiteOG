package com.nuvio.tv.core.cloudstorage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudMatcherTest {
    private fun file(path: String, size: Long = 1) = CloudFile(
        id = path,
        path = path,
        name = path.substringAfterLast('/'),
        sizeBytes = size,
        modifiedEpochSec = 0,
        parentPath = path.substringBeforeLast('/'),
    )

    private val kotoura = file("/ANIME/Kotoura san BD 1080/[Kashikoi Subs] Kotoura san 01 BD [1920x1080 AAC 448Kbps].mp4", 837_000_000)

    private fun series(season: Int, episode: Int, absolute: Int? = null, vararg aliases: CloudTitleAlias) = CloudMatchRequest(
        isSeries = true,
        contentIds = setOf("tt2621446"),
        aliases = aliases.toList(),
        season = season,
        episode = episode,
        absoluteEpisode = absolute,
    )

    @Test
    fun kotouraSanEpisodeOne() {
        val request = series(1, 1, 1, CloudTitleAlias("Kotoura-san"))
        val matches = CloudMatcher.match(listOf(kotoura, file("/ANIME/Kotoura san BD 1080/[Kashikoi Subs] Kotoura san 02 BD [1920x1080 AAC 448Kbps].mp4")), request)
        assertEquals(listOf(kotoura.id), matches.map { it.file.id })
        assertEquals("1080p", matches.single().release.resolution)
    }

    @Test
    fun anotherTitleDoesNotMatch() {
        val request = series(1, 1, 1, CloudTitleAlias("Kotoura-san no Kareshi"))
        assertTrue(CloudMatcher.match(listOf(kotoura), request).isEmpty())
    }

    @Test
    fun fansubNumberingWithoutSeasonNeedsEvidence() {
        val files = listOf(file("/Anime/[Fansub] Clevatess - 03.mkv"), file("/Anime/[Fansub] Clevatess - 15.mkv"))
        // Season 2 episode 3, 12 episodes in season 1: only the absolute number 15 fits.
        val request = series(2, 3, 15, CloudTitleAlias("Clevatess"))
        assertEquals(listOf("/Anime/[Fansub] Clevatess - 15.mkv"), CloudMatcher.match(files, request).map { it.file.id })
        // Without the absolute number nothing is guessed.
        assertTrue(CloudMatcher.match(files, series(2, 3, null, CloudTitleAlias("Clevatess"))).isEmpty())
        // An AniList entry for season 2 makes the season-relative number valid.
        val hinted = series(2, 3, null, CloudTitleAlias("Clevatess II", seasonHint = 2))
        assertEquals(emptyList(), CloudMatcher.match(files, hinted).map { it.file.id })
        val hintedFiles = listOf(file("/Anime/[Fansub] Clevatess II - 03.mkv"))
        assertEquals(1, CloudMatcher.match(hintedFiles, hinted).size)
    }

    @Test
    fun anAniListEntryForSpecialsDoesNotMatchTheFirstSeason() {
        // TMDB files the OVA Hellsing: The Dawn as specials (season 0) of Hellsing Ultimate.
        val ultimate = file("/ANIME/[RedLineSP] Hellsing Ultimate [BD 1080p]/[RedLineSP] Hellsing Ultimate [BD 1080p] - 01.mkv")
        val dawn = file("/ANIME/[RedLineSP] Hellsing - The Dawn [BD 1080p]/[RedLineSP] The Dawn [BD 1080p] - 01.mkv")
        val request = series(
            1, 1, 1,
            CloudTitleAlias("Hellsing Ultimate"),
            CloudTitleAlias("Hellsing Ultimate", seasonHint = 1),
            CloudTitleAlias("Hellsing: The Dawn", seasonHint = 0),
            CloudTitleAlias("The Dawn", seasonHint = 0),
        )
        assertEquals(listOf(ultimate.id), CloudMatcher.match(listOf(ultimate, dawn), request).map { it.file.id })
    }

    @Test
    fun explicitSeasonMustAgree() {
        val files = listOf(file("/TV/Clevatess.S02E12.1080p.mkv"), file("/TV/Clevatess.S01E12.1080p.mkv"))
        val matches = CloudMatcher.match(files, series(2, 12, 24, CloudTitleAlias("Clevatess")))
        assertEquals(listOf("/TV/Clevatess.S02E12.1080p.mkv"), matches.map { it.file.id })
    }

    @Test
    fun titleFromTheFolderWhenTheFileHasNone() {
        val files = listOf(file("/Kotoura-san/01.mkv"))
        assertEquals(1, CloudMatcher.match(files, series(1, 1, 1, CloudTitleAlias("Kotoura-san"))).size)
    }

    @Test
    fun moviesNeedTheTitleAndAYearThatFits() {
        val files = listOf(file("/Movies/Your.Name.2016.1080p.mkv"), file("/Movies/Your.Name.1980.mkv"), file("/Movies/Your Name - 01.mkv"))
        val request = CloudMatchRequest(isSeries = false, contentIds = setOf("tt5311514"), aliases = listOf(CloudTitleAlias("Your Name.")), year = 2016)
        assertEquals(listOf("/Movies/Your.Name.2016.1080p.mkv"), CloudMatcher.match(files, request).map { it.file.id })
    }

    @Test
    fun manualLinksWinAndAreExact() {
        val odd = file("/Misc/random_capture_0007.mkv")
        val link = CloudManualLink(odd.id, "tt2621446", 1, 7)
        val matches = CloudMatcher.match(listOf(odd), series(1, 7, 7, CloudTitleAlias("Kotoura-san")), listOf(link))
        assertTrue(matches.single().manual)
        assertTrue(CloudMatcher.match(listOf(odd), series(1, 8, 8, CloudTitleAlias("Kotoura-san")), listOf(link)).isEmpty())
    }

    @Test
    fun titleKeysIgnorePunctuationAccentsAndCase() {
        assertTrue("Kotoura-san".titleKey()!!.matches("kotoura san".titleKey()!!))
        assertTrue("Pokémon".titleKey()!!.matches("Pokemon".titleKey()!!))
        assertTrue(!"Kotoura".titleKey()!!.matches("Kotoura san".titleKey()!!))
    }

    @Test
    fun absoluteNumbers() {
        assertEquals(15, absoluteEpisodeNumber(mapOf(1 to 12, 2 to 12), 2, 3))
        assertEquals(3, absoluteEpisodeNumber(mapOf(), 1, 3))
        assertEquals(null, absoluteEpisodeNumber(mapOf(2 to 12), 3, 1))
    }
}
