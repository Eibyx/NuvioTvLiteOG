package com.nuvio.tv.core.cloudstorage

import com.nuvio.tv.core.tracking.TrackingExternalIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The owner's TeraBox case: Kai found it through AniList's romaji, the TV did not. */
class CloudTitleAliasesTest {
    private val folder = "/ANIME/Fantasy Bishoujo Juniku Ojisan to BD"
    private val names = listOf(
        "$folder/[Group] Fantasy Bishoujo Juniku Ojisan to - 01 [BD 1080p].mkv",
        "$folder/Fantasy Bishoujo Juniku Ojisan to 01 [1080p].mkv",
        "$folder/01.mkv"
    )

    private fun file(path: String) = CloudFile(path, path, path.substringAfterLast('/'), 1, 0, path.substringBeforeLast('/'))

    private fun aniListHttp(failing: () -> Boolean = { false }) = FakeCloudHttp { request ->
        when {
            failing() -> json("", 503)
            request.url.contains("arm.haglund.dev") -> json("""[{"anilist":131518,"thetvdb-season":1}]""")
            request.url.contains("graphql.anilist.co") -> json(
                """{"data":{"Media":{"title":{"romaji":"Fantasy Bishoujo Juniku Oji-san to","english":"Life With an Ordinary Guy Who Reincarnated Into a Total Fantasy Knockout","native":"異世界美少女受肉おじさんと"},"synonyms":["Fabiniku"]}}}"""
            )
            else -> json("{}", 404)
        }
    }

    @Test
    fun aniListRomajiMatchesEvenWhenTheCatalogueOnlyHasTheEnglishTitle() = runTest {
        val aliases = CloudTitleAliases(aniListHttp()).aliases(
            "series:tt17263022",
            listOf("Life with an Ordinary Guy who Reincarnated into a Total Fantasy Knockout"),
            TrackingExternalIds(imdb = "tt17263022")
        )
        assertTrue(aliases.any { it.title == "Fantasy Bishoujo Juniku Oji-san to" })
        val request = CloudMatchRequest(
            isSeries = true, contentIds = setOf("tt17263022"), aliases = aliases, season = 1, episode = 1, absoluteEpisode = 1
        )
        names.forEach { path ->
            assertEquals(1, CloudMatcher.match(listOf(file(path)), request).size, path)
        }
    }

    @Test
    fun aFailedAniListAnswerIsRetriedNextTime() = runTest {
        var down = true
        val http = aniListHttp { down }
        val aliases = CloudTitleAliases(http)
        val ids = TrackingExternalIds(imdb = "tt17263022")
        val first = aliases.aliases("k", listOf("Life with an Ordinary Guy"), ids)
        assertTrue(first.none { it.title.startsWith("Fantasy") })
        down = false
        val second = aliases.aliases("k", listOf("Life with an Ordinary Guy"), ids)
        assertTrue(second.any { it.title.startsWith("Fantasy") })
    }

    @Test
    fun armIsAskedWithTheEndpointOfEachIdKind() = runTest {
        val http = aniListHttp()
        // AIOmetadata titles carry TVDB ids; Xperience IMDb/TMDB ones.
        CloudTitleAliases(http).aliases("a", listOf("x"), TrackingExternalIds(tvdb = "405227"))
        CloudTitleAliases(http).aliases("b", listOf("x"), TrackingExternalIds(imdb = "tt14669108"))
        CloudTitleAliases(http).aliases("c", listOf("x"), TrackingExternalIds(tmdb = 125712))
        CloudTitleAliases(http).aliases("d", listOf("x"), TrackingExternalIds(mal = 48997))
        val armPaths = http.requests.map { it.url }.filter { "arm.haglund.dev" in it }.map { it.substringAfter("/api/v2").substringBefore('&') }
        assertEquals(listOf("/thetvdb?id=405227", "/imdb?id=tt14669108", "/themoviedb?id=125712", "/ids?source=myanimelist"), armPaths)
        // Never the /ids form ARM refuses for IMDb or TMDB.
        assertTrue(http.requests.none { "source=imdb" in it.url || "source=themoviedb" in it.url })
    }
}
