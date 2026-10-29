package com.nuvio.tv.data.anilist

import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import com.nuvio.tv.data.repository.SimklIdResolver
import com.nuvio.tv.domain.model.Video
import io.mockk.coEvery
import io.mockk.mockk
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AniListIdResolverTvTest {
    private val api = mockk<AniListApiClient>()
    private val simkl = mockk<SimklIdResolver>()
    private val link = AniListLink("tt123|s2", listOf(AniListLinkPart(21, "One Piece")), false, absolute = true)
    private fun video(s: Int, e: Int) = Video("tt123:$s:$e", "$e", null, null, season = s, episode = e, overview = null)

    @Test fun laterSeasonWithoutEpisodeListDoesNotFallBackToItsRelativeNumber() = runBlocking {
        HttpClient(OkHttp).use { client ->
            val resolver = AniListIdResolver(api, simkl, client, showEpisodes = { null })
            assertNull(resolver.targetFor(link, buildTrackingMediaReference("series", "tt123", seasonNumber = 2, episodeNumber = 3)))
        }
    }
    @Test fun completeEpisodeListConvertsLaterSeasonToAbsolute() = runBlocking {
        HttpClient(OkHttp).use { client ->
            val resolver = AniListIdResolver(api, simkl, client, showEpisodes = { (1..12).map { video(1, it) } + (1..12).map { video(2, it) } })
            assertEquals(AniListTarget(21, 15), resolver.targetFor(link, buildTrackingMediaReference("series", "tt123", seasonNumber = 2, episodeNumber = 3)))
        }
    }
    @Test fun gappedEpisodeListCannotEstablishAnAbsoluteOffset() = runBlocking {
        HttpClient(OkHttp).use { client ->
            val resolver = AniListIdResolver(api, simkl, client, showEpisodes = { listOf(video(1, 1), video(1, 3), video(2, 1)) })
            assertNull(resolver.targetFor(link, buildTrackingMediaReference("series", "tt123", seasonNumber = 2, episodeNumber = 1)))
        }
    }
    @Test fun perVideoMalIdentityWinsOverPreviouslySavedSeasonPart() = runBlocking {
        coEvery { api.mediaByMalId(null, 200) } returns (AniListMedia(2, idMal = 200, episodes = 12) to null)
        HttpClient(OkHttp).use { client ->
            val resolver = AniListIdResolver(api, simkl, client)
            val savedFirstPart = AniListLink("tt123|s1", listOf(AniListLinkPart(1, "Part one")), false)
            val target = resolver.targetFor(savedFirstPart, buildTrackingMediaReference("series", "tt123", "mal:200:3", seasonNumber = 1, episodeNumber = 15))
            assertEquals(AniListTarget(2, 3), target)
        }
    }
    @Test fun episodeZeroNeverProducesAWriteTarget() = runBlocking {
        HttpClient(OkHttp).use { client ->
            val resolver = AniListIdResolver(api, simkl, client)
            assertNull(resolver.targetFor(link, buildTrackingMediaReference("series", "tt123", seasonNumber = 1, episodeNumber = 0)))
        }
    }
    @Test fun specialVideoCannotIncreaseAnOrdinaryTvEntry() = runBlocking {
        coEvery { api.mediaPage(null, listOf(1L)) } returns listOf(AniListMedia(1, format = "TV", episodes = 12))
        HttpClient(OkHttp).use { client ->
            val resolver = AniListIdResolver(api, simkl, client)
            assertNull(resolver.targetFor(link, buildTrackingMediaReference("series", "anilist:1", "anilist:1:1", seasonNumber = 0, episodeNumber = 1)))
        }
    }
}
