package com.nuvio.tv.core.cloudstorage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OVA, OAD, SP, specials, extras, recaps and movies only match TMDB season 0; Blu-ray extras
 * never match. New in the TV port (to bring back to NuvioMobileKai).
 */
class CloudSpecialsTest {
    private fun file(path: String) = CloudFile(
        id = path,
        path = path,
        name = path.substringAfterLast('/'),
        sizeBytes = 1,
        modifiedEpochSec = 0,
        parentPath = path.substringBeforeLast('/')
    )

    private fun parse(path: String): ParsedRelease {
        val f = file(path)
        return CloudReleaseParser.parse(f.name, f.folderNames())
    }

    private fun series(season: Int, episode: Int, vararg aliases: CloudTitleAlias) = CloudMatchRequest(
        isSeries = true,
        contentIds = setOf("tt0000001"),
        aliases = aliases.toList(),
        season = season,
        episode = episode
    )

    @Test
    fun specialKeywordsInFileNames() {
        listOf(
            "/Anime/[Group] Hellsing Ultimate OVA 03 [1080p].mkv",
            "/Anime/[Group] Hellsing Ultimate - OVA03 [BD 1080p].mkv",
            "/Anime/[Group] Kaguya-sama - SP01 [1080p].mkv",
            "/Anime/Show OAD 2 [720p].mkv",
            "/Anime/[Fansub] Show - Special 1 [1080p].mkv",
            "/Anime/[Fansub] Show - Recap [1080p].mkv",
            "/Anime/[Group] Show [OVA] - 01 [1080p].mkv"
        ).forEach { path ->
            val release = parse(path)
            assertTrue(release.isSpecial, path)
            assertFalse(release.isExtra, path)
        }
        assertEquals("Hellsing Ultimate", parse("/Anime/[Group] Hellsing Ultimate OVA 03 [1080p].mkv").title)
        assertEquals(3, parse("/Anime/[Group] Hellsing Ultimate - OVA03 [BD 1080p].mkv").episode)
        assertEquals(1, parse("/Anime/[Group] Kaguya-sama - SP01 [1080p].mkv").episode)
    }

    @Test
    fun specialFolders() {
        assertTrue(parse("/Anime/Show/Specials/[Group] Show - 01 [1080p].mkv").isSpecial)
        assertTrue(parse("/Anime/Show/OVA/01.mkv").isSpecial)
        assertTrue(parse("/Anime/Hellsing Ultimate OVA/[Group] Hellsing Ultimate - 05.mkv").isSpecial)
        assertFalse(parse("/Anime/Show/Season 1/[Group] Show - 01 [1080p].mkv").isSpecial)
    }

    @Test
    fun titlesThatContainKeywordsStayTitles() {
        val specialA = parse("/Anime/[Group] Special A - 01 [720p].mkv")
        assertFalse(specialA.isSpecial)
        assertEquals("Special A", specialA.title)
        val trailerPark = parse("/TV/Trailer Park Boys S01E01 720p.mkv")
        assertFalse(trailerPark.isExtra)
        assertEquals("Trailer Park Boys", trailerPark.title)
    }

    @Test
    fun blurayExtras() {
        listOf(
            "/Anime/[Group] Show - NCOP1 [BD 1080p].mkv",
            "/Anime/[Group] Show - NCED [BD 1080p].mkv",
            "/Anime/[Group] Show [NCOP 2][BD 1080p].mkv",
            "/Anime/[Group] Show - PV1 [BD 1080p].mkv",
            "/Anime/[Group] Show - CM02 [BD 1080p].mkv",
            "/Anime/[Group] Show - Menu 1 [BD 1080p].mkv",
            "/Anime/[Group] Show - Preview 03 [BD 1080p].mkv",
            "/Anime/[Group] Show - Trailer [BD 1080p].mkv"
        ).forEach { path -> assertTrue(parse(path).isExtra, path) }
    }

    @Test
    fun specialsOnlyMatchSeasonZero() {
        val files = listOf(
            file("/Anime/[Group] Hellsing Ultimate OVA 01 [1080p].mkv"),
            file("/Anime/[Group] Hellsing Ultimate - 01 [1080p].mkv")
        )
        val alias = CloudTitleAlias("Hellsing Ultimate")
        assertEquals(
            listOf("/Anime/[Group] Hellsing Ultimate - 01 [1080p].mkv"),
            CloudMatcher.match(files, series(1, 1, alias)).map { it.file.id }
        )
        assertEquals(
            listOf("/Anime/[Group] Hellsing Ultimate OVA 01 [1080p].mkv"),
            CloudMatcher.match(files, series(0, 1, alias)).map { it.file.id }
        )
    }

    @Test
    fun specialsInASeasonFolderStillOnlyMatchSeasonZero() {
        val files = listOf(file("/Anime/Show/Season 2/Specials/[Group] Show - 01.mkv"))
        val alias = CloudTitleAlias("Show")
        assertTrue(CloudMatcher.match(files, series(2, 1, alias)).isEmpty())
        assertEquals(1, CloudMatcher.match(files, series(0, 1, alias)).size)
    }

    @Test
    fun extrasNeverMatch() {
        val files = listOf(file("/Anime/[Group] Show - NCOP1 [BD 1080p].mkv"), file("/Anime/[Group] Show - NCED1 [BD 1080p].mkv"))
        val alias = CloudTitleAlias("Show")
        assertTrue(CloudMatcher.match(files, series(1, 1, alias)).isEmpty())
        assertTrue(CloudMatcher.match(files, series(0, 1, alias)).isEmpty())
    }

    @Test
    fun aniListAliasOfAnotherSeasonDoesNotMatch() {
        // Hellsing: The Dawn is TMDB season 0 of Hellsing Ultimate: never Hellsing Ultimate S1E1.
        val files = listOf(file("/Anime/[Group] Hellsing The Dawn - 01 [1080p].mkv"))
        val request = series(1, 1, CloudTitleAlias("Hellsing Ultimate"), CloudTitleAlias("Hellsing: The Dawn", seasonHint = 0))
        assertTrue(CloudMatcher.match(files, request).isEmpty())
        val specials = series(0, 1, CloudTitleAlias("Hellsing Ultimate"), CloudTitleAlias("Hellsing: The Dawn", seasonHint = 0))
        assertEquals(1, CloudMatcher.match(files, specials).size)
    }

    @Test
    fun moviesKeepTheirKeyword() {
        val files = listOf(file("/Movies/Gintama The Movie (2010) [1080p].mkv"))
        val request = CloudMatchRequest(
            isSeries = false,
            contentIds = setOf("tt0000002"),
            aliases = listOf(CloudTitleAlias("Gintama: The Movie")),
            year = 2010
        )
        assertEquals(1, CloudMatcher.match(files, request).size)
    }

    @Test
    fun fansubNamesWithoutKeywordsStillMatch() {
        val files = listOf(
            file("/Anime/[SubsPlease] Frieren - 12 (1080p) [ABCD1234].mkv"),
            file("/Anime/Frieren/Temporada 1/Frieren Episodio 12.mkv"),
            file("/Anime/Frieren 1x12 [WEB 1080p].mkv"),
            file("/Anime/Frieren/Season 1/12.mkv")
        )
        val matches = CloudMatcher.match(files, series(1, 12, CloudTitleAlias("Frieren")))
        assertEquals(4, matches.size)
    }
}
