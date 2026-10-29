package com.nuvio.tv.data.anilist

import org.junit.Test
import com.nuvio.tv.data.anilist.mobiletest.assertEquals
import com.nuvio.tv.data.anilist.mobiletest.assertNull

/** AniList progress in the detail page's numbering, with real AniList ids and counts. */
class AniListWatchedImportTest {
    private fun media(id: Long, episodes: Int?) = AniListMedia(id = id, format = "TV", episodes = episodes)

    private fun entry(id: Long, status: String, progress: Int, episodes: Int?) =
        AniListEntry(id = id, mediaId = id, status = status, progress = progress, media = media(id, episodes))

    private fun tv(id: Long, season: Int?, episodes: Int?, start: Long) = AniListSeasonCandidate(id, "TV", season, episodes, start)

    private fun link(key: String, plan: AniListLinkPlan) = AniListLink(key, plan.parts, manual = false, absolute = plan.absolute)

    private fun range(season: Int, episodes: IntRange) = episodes.map { SeasonEpisode(season, it) }

    private val mushoku = listOf(
        tv(108465, season = 1, episodes = 11, start = 20210111),
        tv(127720, season = 1, episodes = 12, start = 20211004),
        tv(146065, season = 2, episodes = 12, start = 20230703),
        tv(166873, season = 2, episodes = 12, start = 20240408),
    )

    @Test
    fun mushokuTenseiSeasonOneComesInAsTwentyThreeEpisodes() {
        val plan = aniListLinkPlan(mushoku, season = 1, isMovie = false)!!
        val entries = mapOf(
            108465L to entry(108465, AniListListStatus.COMPLETED, 11, 11),
            127720L to entry(127720, AniListListStatus.COMPLETED, 12, 12),
        )
        val parts = importLinkedSeason(link("tt13293588|s1", plan), season = 1, entries = entries)
        assertEquals(range(1, 1..11), parts[0].episodes)
        // "Part 2" with progress 12 is S1E12-E23.
        assertEquals(range(1, 12..23), parts[1].episodes)
        assertEquals(listOf(11 to 11, 12 to 12), parts.map { it.watched to it.total })
        assertEquals(range(1, 1..23), parts.flatMap { it.episodes })
    }

    @Test
    fun aPartWatchedHalfwayMarksOnlyItsEpisodes() {
        val plan = aniListLinkPlan(mushoku, season = 2, isMovie = false)!!
        val entries = mapOf(
            146065L to entry(146065, AniListListStatus.COMPLETED, 12, 12),
            166873L to entry(166873, AniListListStatus.CURRENT, 5, 12),
        )
        val parts = importLinkedSeason(link("tt13293588|s2", plan), season = 2, entries = entries)
        assertEquals(range(2, 1..12) + range(2, 13..17), parts.flatMap { it.episodes })
        assertEquals(5 to 12, parts[1].watched to parts[1].total)
        // Nothing on the list for a part: nothing watched.
        val empty = importLinkedSeason(link("tt13293588|s2", plan), season = 2, entries = emptyMap())
        assertEquals(emptyList(), empty.flatMap { it.episodes })
    }

    @Test
    fun attackOnTitanSeasonThreeHasTwoParts() {
        val aot = listOf(
            tv(16498, season = 1, episodes = 25, start = 20130407),
            tv(99147, season = 3, episodes = 12, start = 20180723),
            tv(104578, season = 3, episodes = 10, start = 20190429),
        )
        val plan = aniListLinkPlan(aot, season = 3, isMovie = false)!!
        val entries = mapOf(
            99147L to entry(99147, AniListListStatus.COMPLETED, 12, 12),
            104578L to entry(104578, AniListListStatus.CURRENT, 4, 10),
        )
        val parts = importLinkedSeason(link("tt2560140|s3", plan), season = 3, entries = entries)
        assertEquals(range(3, 1..16), parts.flatMap { it.episodes })
    }

    @Test
    fun onePieceWalksTheShowInOrder() {
        val plan = aniListLinkPlan(listOf(tv(21, season = null, episodes = null, start = 19991020)), season = 2, isMovie = false)!!
        // A short slice of the TVDB order: season 1 has 8 episodes, season 2 has 22, specials ignored.
        val show = (1..8).map { ShowEpisode(1, it) } + (1..22).map { ShowEpisode(2, it) } + ShowEpisode(0, 1)
        val entries = mapOf(21L to AniListEntry(1, 21, AniListListStatus.CURRENT, progress = 10, media = media(21, null)))
        val parts = importLinkedSeason(link("tt0388629|s2", plan), season = 2, entries = entries, showEpisodes = show)
        // Episode 10 overall is S2E2.
        assertEquals(range(1, 1..8) + range(2, 1..2), parts.single().episodes)
        // Without the episode list an absolute link cannot place anything.
        assertEquals(emptyList(), importLinkedSeason(link("tt0388629|s2", plan), 2, entries).single().episodes)
    }

    @Test
    fun aSingleEntrySeries() {
        val link = AniListLink("kitsu:7442|s1", listOf(AniListLinkPart(16498, "Attack on Titan", 1, 25)), manual = false)
        val parts = importLinkedSeason(link, 1, mapOf(16498L to entry(16498, AniListListStatus.CURRENT, 7, 25)))
        assertEquals(range(1, 1..7), parts.single().episodes)
        assertNull(parts.single().resume)
    }

    @Test
    fun rewatchingCountsEverythingAndResumesAtTheRewatch() {
        val link = AniListLink("tt13293588|s1", listOf(AniListLinkPart(108465, "Mushoku Tensei", 1, 11), AniListLinkPart(127720, "Part 2", 12, 12)), manual = false)
        val entries = mapOf(
            108465L to entry(108465, AniListListStatus.COMPLETED, 11, 11),
            127720L to entry(127720, AniListListStatus.REPEATING, 3, 12),
        )
        val parts = importLinkedSeason(link, 1, entries)
        assertEquals(range(1, 1..23), parts.flatMap { it.episodes })
        // Rewatching Part 2 at episode 3: resume at its episode 4, S1E15.
        assertEquals(SeasonEpisode(1, 15), parts[1].resume)
        assertEquals(12, aniListWatchedCount(entries[127720L], 12))
        assertEquals(0, aniListWatchedCount(null, 12))
        assertEquals(0, aniListWatchedCount(entry(1, AniListListStatus.PLANNING, 0, 12), 12))
    }

    @Test
    fun datesFindTheBlockWhenNoSeasonTableDoes() {
        // Mushoku Tensei on TVDB: S1E1 aired 2021-01-11, E12 (Part 2) 2021-10-04, weekly.
        val partOneStart = epochDay(2021, 1, 11)
        val partTwoStart = epochDay(2021, 10, 4)
        val show = (1..11).map { ShowEpisode(1, it, partOneStart + (it - 1) * 7) } +
            (12..23).map { ShowEpisode(1, it, partTwoStart + (it - 12) * 7) }
        val partTwo = AniListMedia(id = 127720, episodes = 12, startDate = AniListFuzzyDate(2021, 10, 4))
        assertEquals(range(1, 12..23), aniListDateBlock(show, aniListStartDay(partTwo)!!, count = 12))
        // A day off is fine; months off is not this show.
        assertEquals(SeasonEpisode(1, 12), aniListDateBlock(show, partTwoStart + 1, count = 12)!!.first())
        assertNull(aniListDateBlock(show, epochDay(2023, 7, 3), count = 12))
        assertEquals(epochDay(2021, 1, 11), releasedEpochDay("2021-01-11T00:00:00.000Z"))
        assertEquals(0L, epochDay(1970, 1, 1))
        assertNull(releasedEpochDay("TBA"))
    }
}
