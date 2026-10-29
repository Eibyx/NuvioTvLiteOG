package com.nuvio.tv.data.anilist

import org.junit.Test
import com.nuvio.tv.data.anilist.mobiletest.assertEquals
import com.nuvio.tv.data.anilist.mobiletest.assertNull

/**
 * Real AniList ids with ARM's TVDB seasons (arm.haglund.dev, checked October 2026) and AniList
 * episode counts.
 */
class AniListSeasonMappingTest {
    private fun tv(id: Long, season: Int?, episodes: Int?, start: Long) =
        AniListSeasonCandidate(id, "TV", season, episodes, start)

    @Test
    fun onePieceUsesAbsoluteNumberingOnItsSingleEntry() {
        val onePiece = listOf(
            tv(21, season = null, episodes = null, start = 19991020),
            AniListSeasonCandidate(459, "MOVIE", null, 1, 20000304),
            AniListSeasonCandidate(1094, "SPECIAL", 0, 1, 20001220),
        )
        // S21E50 of the TVDB order is episode 941 overall.
        assertEquals(AniListTarget(21, 941), chooseAniListTarget(onePiece, season = 21, episode = 50, isMovie = false, absoluteEpisode = 941))
        assertEquals(true, needsAbsoluteEpisode(onePiece, isMovie = false))
        // Without the episode list, the season episode is used rather than nothing.
        assertEquals(AniListTarget(21, 50), chooseAniListTarget(onePiece, season = 21, episode = 50, isMovie = false))
    }

    @Test
    fun attackOnTitanSeasonThreeSplitsIntoTwoParts() {
        val aot = listOf(
            tv(16498, season = 1, episodes = 25, start = 20130407),
            tv(20958, season = 2, episodes = 12, start = 20170401),
            tv(99147, season = 3, episodes = 12, start = 20180723),
            tv(104578, season = 3, episodes = 10, start = 20190429),
        )
        assertEquals(AniListTarget(99147, 12), chooseAniListTarget(aot, season = 3, episode = 12, isMovie = false))
        assertEquals(AniListTarget(104578, 1), chooseAniListTarget(aot, season = 3, episode = 13, isMovie = false))
        assertEquals(AniListTarget(104578, 10), chooseAniListTarget(aot, season = 3, episode = 22, isMovie = false))
        assertEquals(AniListTarget(20958, 5), chooseAniListTarget(aot, season = 2, episode = 5, isMovie = false))
        assertEquals(false, needsAbsoluteEpisode(aot, isMovie = false))
    }

    @Test
    fun spyFamilyCoursShareTheFirstSeason() {
        val spyFamily = listOf(
            tv(158927, season = 2, episodes = 12, start = 20231007),
            tv(142838, season = 1, episodes = 13, start = 20221001),
            tv(140960, season = 1, episodes = 12, start = 20220409),
        )
        assertEquals(AniListTarget(140960, 12), chooseAniListTarget(spyFamily, season = 1, episode = 12, isMovie = false))
        assertEquals(AniListTarget(142838, 1), chooseAniListTarget(spyFamily, season = 1, episode = 13, isMovie = false))
        assertEquals(AniListTarget(142838, 13), chooseAniListTarget(spyFamily, season = 1, episode = 25, isMovie = false))
        assertEquals(AniListTarget(158927, 3), chooseAniListTarget(spyFamily, season = 2, episode = 3, isMovie = false))
    }

    @Test
    fun reZeroSecondSeasonHasTwoParts() {
        val reZero = listOf(
            tv(21355, season = 1, episodes = 25, start = 20160404),
            tv(108632, season = 2, episodes = 13, start = 20200708),
            tv(119661, season = 2, episodes = 12, start = 20210106),
            tv(163134, season = 3, episodes = null, start = 20241002),
        )
        assertEquals(AniListTarget(21355, 25), chooseAniListTarget(reZero, season = 1, episode = 25, isMovie = false))
        assertEquals(AniListTarget(108632, 13), chooseAniListTarget(reZero, season = 2, episode = 13, isMovie = false))
        assertEquals(AniListTarget(119661, 1), chooseAniListTarget(reZero, season = 2, episode = 14, isMovie = false))
        // Season 3 is still airing (no episode count): it takes every episode.
        assertEquals(AniListTarget(163134, 9), chooseAniListTarget(reZero, season = 3, episode = 9, isMovie = false))
    }

    @Test
    fun animeMoviePicksTheMovieEntry() {
        val yourName = listOf(AniListSeasonCandidate(21519, "MOVIE", null, 1, 20160826))
        assertEquals(AniListTarget(21519, 1), chooseAniListTarget(yourName, season = null, episode = null, isMovie = true))
    }

    @Test
    fun specialsOnlyMatchSeasonZeroEntries() {
        val show = listOf(
            tv(16498, season = 1, episodes = 25, start = 20130407),
            AniListSeasonCandidate(18397, "OVA", 0, 3, 20131209),
        )
        assertEquals(AniListTarget(18397, 2), chooseAniListTarget(show, season = 0, episode = 2, isMovie = false))
        assertNull(chooseAniListTarget(listOf(tv(16498, 1, 25, 20130407)), season = 0, episode = 1, isMovie = false))
    }

    @Test
    fun aSeasonArmDoesNotKnowIsNotGuessed() {
        val aot = listOf(tv(16498, season = 1, episodes = 25, start = 20130407))
        assertNull(chooseAniListTarget(aot + tv(20958, 2, 12, 20170401), season = 5, episode = 1, isMovie = false))
        assertNull(chooseAniListTarget(emptyList(), season = 1, episode = 1, isMovie = false))
    }

    @Test
    fun progressRules() {
        fun entry(status: String, progress: Int) = AniListEntry(id = 1, mediaId = 21, status = status, progress = progress)

        assertEquals(AniListProgressUpdate(AniListListStatus.CURRENT, 1), aniListProgressUpdate(null, 1, 12))
        assertEquals(AniListProgressUpdate(AniListListStatus.CURRENT, 1), aniListProgressUpdate(entry(AniListListStatus.PLANNING, 0), 1, 12))
        assertEquals(AniListProgressUpdate(AniListListStatus.COMPLETED, 12), aniListProgressUpdate(entry(AniListListStatus.CURRENT, 11), 12, 12))
        assertEquals(AniListProgressUpdate(AniListListStatus.REPEATING, 4), aniListProgressUpdate(entry(AniListListStatus.REPEATING, 3), 4, 12))
        assertEquals(AniListProgressUpdate(AniListListStatus.COMPLETED, 12), aniListProgressUpdate(entry(AniListListStatus.REPEATING, 11), 12, 12))
        // Never lowers progress.
        assertNull(aniListProgressUpdate(entry(AniListListStatus.CURRENT, 7), 5, 12))
        assertNull(aniListProgressUpdate(entry(AniListListStatus.COMPLETED, 12), 3, 12))
        // The comparison uses the progress as saved: episode 23 of a season reaching an entry of 11
        // that is already at 11 is no change.
        assertNull(aniListProgressUpdate(entry(AniListListStatus.CURRENT, 11), 23, 11))
        assertEquals(AniListProgressUpdate(AniListListStatus.COMPLETED, 11), aniListProgressUpdate(entry(AniListListStatus.CURRENT, 10), 23, 11))
        // A Completed entry is never written again (AniList would log it as watched again), and
        // nothing turns an entry into Rewatching.
        assertNull(aniListProgressUpdate(entry(AniListListStatus.COMPLETED, 11), 23, 11))
        assertNull(aniListProgressUpdate(entry(AniListListStatus.COMPLETED, 11), 11, 11))
        assertNull(aniListProgressUpdate(entry(AniListListStatus.COMPLETED, 5), 7, 12))
        assertEquals(AniListProgressUpdate(AniListListStatus.CURRENT, 6), aniListProgressUpdate(entry(AniListListStatus.PAUSED, 5), 6, 12))
        assertEquals(AniListProgressUpdate(AniListListStatus.CURRENT, 3), aniListProgressUpdate(entry(AniListListStatus.DROPPED, 2), 3, 12))
        // Airing shows have no total yet.
        assertEquals(AniListProgressUpdate(AniListListStatus.CURRENT, 1100), aniListProgressUpdate(entry(AniListListStatus.CURRENT, 1099), 1100, -1))
    }

    @Test
    fun aSeasonIsLinkedOnceAndEveryEpisodeUsesTheSavedParts() {
        val aot = listOf(
            tv(16498, season = 1, episodes = 25, start = 20130407),
            tv(99147, season = 3, episodes = 12, start = 20180723),
            tv(104578, season = 3, episodes = 10, start = 20190429),
        )
        val plan = aniListLinkPlan(aot, season = 3, isMovie = false)!!
        assertEquals(listOf(1, 13), plan.parts.map { it.firstEpisode })
        assertEquals(AniListTarget(99147, 5), plan.target(5))
        assertEquals(AniListTarget(104578, 1), plan.target(13))
        assertEquals(false, plan.parts.endsBefore(22))
        // Episode 23 would need a part AniList did not have when the link was made.
        assertEquals(true, plan.parts.endsBefore(23))

        val onePiece = aniListLinkPlan(listOf(tv(21, season = null, episodes = null, start = 19991020)), season = 21, isMovie = false)!!
        assertEquals(true, onePiece.absolute)
        assertEquals(AniListTarget(21, 941), onePiece.target(50, absoluteEpisode = 941))
        assertEquals(false, onePiece.parts.endsBefore(5000))
    }

    @Test
    fun aMovieLinksToItsMovieEntry() {
        val plan = aniListLinkPlan(
            listOf(tv(21, null, null, 19991020), AniListSeasonCandidate(459, "MOVIE", null, 1, 20000304)),
            season = null,
            isMovie = true,
        )!!
        assertEquals(459L, plan.parts.single().mediaId)
    }
}
