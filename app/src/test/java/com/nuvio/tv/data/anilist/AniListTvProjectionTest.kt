package com.nuvio.tv.data.anilist

import com.nuvio.tv.domain.model.Video
import org.junit.Assert.*
import org.junit.Test

class AniListTvProjectionTest {
    private fun videos(season: Int, count: Int) = (1..count).map {
        Video("tt123:$season:$it", "Episode $it", null, null, season = season, episode = it, overview = null)
    }
    private fun link(id: Long = 1, season: Int = 1, absolute: Boolean = false) =
        AniListLink("tt123|s$season", listOf(AniListLinkPart(id, "Anime", episodes = 24)), false, absolute)

    @Test fun importTwelveWatchedAndSelectThirteenWithoutHistoryWrites() {
        val episodes = videos(1, 24)
        val imported = aniListOverlay(listOf(link()), listOf(AniListEntry(1, 1, "CURRENT", 12)), episodes)
        assertEquals((1..12).map { 1 to it }.toSet(), imported.watched)
        assertEquals(13, aniListNextEpisode(episodes, imported)?.episode)
    }
    @Test fun completedFirstSeasonSelectsSecondSeasonAndDoesNotMarkIt() {
        val episodes = videos(1, 24) + videos(2, 12)
        val imported = aniListOverlay(listOf(link()), listOf(AniListEntry(1, 1, "COMPLETED", 24)), episodes)
        assertEquals(1, aniListNextEpisode(episodes, imported)?.episode)
        assertEquals(2, aniListNextEpisode(episodes, imported)?.season)
        assertFalse((2 to 1) in imported.watched)
    }
    @Test fun rewatchingKeepsPreviousWatchedPrefixAndResumesRewatch() {
        val episodes = videos(1, 24)
        val imported = aniListOverlay(listOf(link()), listOf(AniListEntry(1, 1, "REPEATING", 5)), episodes)
        assertEquals(24, imported.watched.size)
        assertEquals(6, aniListNextEpisode(episodes, imported)?.episode)
    }
    @Test fun absoluteImportExcludesSpecialsAndEpisodeZero() {
        val episodes = videos(0, 3) + videos(1, 12) + videos(2, 12)
        val imported = aniListOverlay(listOf(link(absolute = true)), listOf(AniListEntry(1, 1, "CURRENT", 13)), episodes)
        assertEquals((2 to 1), imported.watched.maxWith(compareBy({ it.first }, { it.second })))
        assertFalse(imported.watched.any { it.first == 0 || it.second == 0 })
    }
    @Test fun disconnectRemovesImportedOverlayAndDoesNotAlterLocalEntries() {
        val episodes = videos(1, 24)
        assertEquals(emptySet<Pair<Int, Int>>(), aniListOverlay(emptyList(), emptyList(), episodes).watched)
        assertEquals(24, episodes.size)
    }
    @Test fun onlyEpisodesPresentInAddonMetadataAreProjected() {
        val imported = aniListOverlay(listOf(link()), listOf(AniListEntry(1, 1, "CURRENT", 12)), videos(1, 8))
        assertEquals(8, imported.watched.size)
    }
    @Test fun snapshotRoundTripRetainsImportedProgress() {
        val before = AniListSyncSnapshot(7, listOf(AniListEntry(1, 1, "CURRENT", 12)))
        val after = aniListJson.decodeFromString(AniListSyncSnapshot.serializer(), aniListJson.encodeToString(AniListSyncSnapshot.serializer(), before))
        assertEquals(before, after)
        assertEquals(13, aniListNextEpisode(videos(1, 24), aniListOverlay(listOf(link()), after.entries, videos(1, 24)))?.episode)
    }
    @Test fun aiometadataVideoIdsKeepIndependentMalPartsInsideOneTvSeason() {
        val episodes = videos(1, 24).map { v -> v.copy(id = if (v.episode!! <= 12) "mal:100:${v.episode}" else "mal:200:${v.episode - 12}") }
        val entries = listOf(
            AniListEntry(1, 1, "COMPLETED", 12, media = AniListMedia(1, idMal = 100, episodes = 12)),
            AniListEntry(2, 2, "CURRENT", 3, media = AniListMedia(2, idMal = 200, episodes = 12))
        )
        val overlay = aniListOverlay(emptyList(), entries, episodes)
        assertEquals((1..15).map { 1 to it }.toSet(), overlay.watched)
        assertEquals(16, aniListNextEpisode(episodes, overlay)?.episode)
    }
    @Test fun unverifiedSeasonAndUnknownPartOrderingAreRejected() {
        assertNull(verifiedAniListLinkPlan(listOf(
            AniListSeasonCandidate(1, "TV", null, 12, 20200101),
            AniListSeasonCandidate(2, "TV", null, 12, 20210101)), 2, false))
        assertNull(verifiedAniListLinkPlan(listOf(
            AniListSeasonCandidate(1, "TV", 1, null, 20200101),
            AniListSeasonCandidate(2, "TV", 1, 12, 20210101)), 1, false))
        assertNull(verifiedAniListLinkPlan(listOf(AniListSeasonCandidate(1, "TV", null, 12, 20200101)), 0, false))
    }

}
