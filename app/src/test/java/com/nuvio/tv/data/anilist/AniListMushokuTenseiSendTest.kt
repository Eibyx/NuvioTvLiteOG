package com.nuvio.tv.data.anilist

import org.junit.Test
import com.nuvio.tv.data.anilist.mobiletest.assertEquals
import com.nuvio.tv.data.anilist.mobiletest.assertNull
import com.nuvio.tv.data.anilist.mobiletest.assertTrue

/**
 * The reported case: Mushoku Tensei season 1 is one TVDB season of 23
 * episodes on the detail page (AIOMetadata, IMDb tt13293588) and two AniList entries, both
 * completed: "Mushoku Tensei" (108465, 11 episodes) and "Part 2" (127720, 12 episodes).
 */
class AniListMushokuTenseiSendTest {
    @Test
    fun aCompletedPartIsSavedAgainWhenTheSeasonEpisodeIsPastItsEnd() {
        // Part 1 is completed at 11. Episode 23 of the season reaches it (the link only knows part 1,
        // or the season is not split): 23 > 11 passes the check, is cut to 11 and saved as Completed.
        val completed = AniListEntry(id = 1, mediaId = 108465, status = AniListListStatus.COMPLETED, progress = 11)
        assertNull(
            aniListProgressUpdate(completed, episode = 23, totalEpisodes = 11),
            "a completed entry must not be written again (AniList logs it as watched again)",
        )
    }

    private fun tv(id: Long, season: Int?, episodes: Int?, start: Long) = AniListSeasonCandidate(id, "TV", season, episodes, start)

    /** ARM's rows for tt13293588: two parts in TVDB season 1, two in season 2. */
    private val mushoku = listOf(
        tv(108465, season = 1, episodes = 11, start = 20210111),
        tv(127720, season = 1, episodes = 12, start = 20211004),
        tv(146065, season = 2, episodes = 12, start = 20230703),
        tv(166873, season = 2, episodes = 12, start = 20240408),
    )

    /** What marking the whole season queues: each episode on its part, the highest one per part. */
    private fun seasonSends(plan: AniListLinkPlan, episodes: IntRange): Map<Long, Int> =
        episodes.map { plan.target(it) }.groupBy { it.mediaId }.mapValues { (_, targets) -> targets.maxOf { it.episode } }

    @Test
    fun markingSeasonOneSendsOneUpdatePerPart() {
        val plan = aniListLinkPlan(mushoku, season = 1, isMovie = false)!!
        assertEquals(listOf(1, 12), plan.parts.map { it.firstEpisode })
        // Part 1 gets its 11, Part 2 gets 12 (season episode 23); nothing reaches season 2.
        assertEquals(mapOf(108465L to 11, 127720L to 12), seasonSends(plan, 1..23))
        assertEquals(AniListTarget(127720, 1), plan.target(12))
        val seasonTwo = aniListLinkPlan(mushoku, season = 2, isMovie = false)!!
        assertEquals(mapOf(146065L to 12, 166873L to 12), seasonSends(seasonTwo, 1..24))
    }

    @Test
    fun markingAnAlreadyCompletedSeasonSendsNothing() {
        val plan = aniListLinkPlan(mushoku, season = 1, isMovie = false)!!
        val onAniList = mapOf(
            108465L to AniListEntry(1, 108465, AniListListStatus.COMPLETED, progress = 11),
            127720L to AniListEntry(2, 127720, AniListListStatus.COMPLETED, progress = 12),
        )
        val totals = plan.parts.associate { it.mediaId to (it.episodes ?: -1) }
        val writes = seasonSends(plan, 1..23).mapNotNull { (mediaId, episode) ->
            aniListProgressUpdate(onAniList[mediaId], episode, totals.getValue(mediaId))
        }
        assertTrue(writes.isEmpty(), "writes: $writes")
    }

    @Test
    fun episodesPastTheLinkedPartsAreNotPiledOntoTheLastOne() {
        // ARM only knew Part 1 when the season was linked: E12-E23 have no part, they are not sent to it.
        val partOne = aniListLinkPlan(mushoku.take(1), season = 1, isMovie = false)!!
        assertEquals(false, partOne.parts.endsBefore(11))
        assertEquals(true, partOne.parts.endsBefore(12))
        assertEquals(true, partOne.parts.endsBefore(23))
    }
}
