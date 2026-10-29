package com.nuvio.tv.data.anilist

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.nuvio.tv.data.anilist.mobiletest.assertEquals
import com.nuvio.tv.data.anilist.mobiletest.assertNull
import com.nuvio.tv.data.anilist.mobiletest.assertTrue

class AniListSendQueueTest {
    private class MemoryStorage : AniListQueueStorage {
        val saved = mutableMapOf<Int, String>()
        var last: Int? = null
        override fun load(profileId: Int): String? = saved[profileId]
        override fun save(profileId: Int, payload: String) {
            saved[profileId] = payload
        }
        override fun lastProfile(): Int? = last
        override fun saveLastProfile(profileId: Int) {
            last = profileId
        }
    }

    private var clock = 1_000_000L
    private val storage = MemoryStorage()
    private val delivered = mutableListOf<Pair<String, Int?>>()
    private var answer: (AniListPendingSend) -> AniListDelivery = { AniListDelivery.Sent(it.title, it.episode ?: 1) }

    private fun TestScope.queue() = AniListSendQueue(
        storage = storage,
        deliver = { _, item ->
            delivered += item.key to item.episode
            answer(item)
        },
        scope = backgroundScope,
        now = { clock },
    )

    private fun send(episode: Int, season: Int = 1, contentId: String = "tt2560140", title: String = "Attack on Titan") =
        AniListPendingSend(
            key = aniListSendKey(contentId, "series", season),
            title = title,
            contentType = "series",
            contentId = contentId,
            videoId = "$contentId:$season:$episode",
            season = season,
            episode = episode,
        )

    @Test
    fun onlyTheHighestPendingEpisodePerAnimeIsKept() = runTest {
        answer = { AniListDelivery.Retry("offline") }
        val queue = queue()
        queue.open(1)
        queue.enqueue(send(episode = 3))
        runCurrent()
        queue.enqueue(send(episode = 5))
        queue.enqueue(send(episode = 4))
        runCurrent()

        val pending = queue.state.value.pending.single()
        assertEquals(5, pending.episode)
    }

    @Test
    fun pendingSendsSurviveARestart() = runTest {
        answer = { AniListDelivery.Retry("offline") }
        val first = queue()
        first.open(1)
        first.enqueue(send(episode = 7))
        runCurrent()

        answer = { AniListDelivery.Sent("Attack on Titan", it.episode ?: 1) }
        val afterRestart = queue()
        afterRestart.open(1)
        assertEquals(7, afterRestart.state.value.pending.single().episode)
        afterRestart.retryNow()
        runCurrent()

        assertTrue(afterRestart.state.value.pending.isEmpty())
        assertEquals(7, afterRestart.state.value.lastSyncEpisode)
    }

    @Test
    fun temporaryErrorsWaitLongerEachTimeAndHonourRetryAfter() = runTest {
        answer = { AniListDelivery.Retry("network error") }
        val queue = queue()
        queue.open(1)
        queue.enqueue(send(episode = 1))
        runCurrent()
        val first = queue.state.value.pending.single()
        assertEquals(1, first.attempts)
        assertEquals(clock + 30_000L, first.nextAttemptAtMs)
        assertEquals("network error", first.lastError)

        clock += 30_000L
        queue.process()
        assertEquals(clock + 60_000L, queue.state.value.pending.single().nextAttemptAtMs)

        answer = { AniListDelivery.Retry("AniList rate limit", retryAtMs = clock + 90_000L) }
        clock += 60_000L
        queue.process()
        assertEquals(clock + 90_000L, queue.state.value.pending.single().nextAttemptAtMs)
        assertEquals(1, queue.state.value.failures.size, "one failure line per title, not one per attempt")
    }

    @Test
    fun anAnimeAniListDoesNotKnowIsDroppedRecordedAndAnnounced() = runTest {
        answer = { AniListDelivery.NotFound("no AniList entry matches") }
        val queue = queue()
        val events = mutableListOf<AniListSyncEvent>()
        backgroundScope.launch { queue.events.collect { events += it } }
        runCurrent()
        queue.open(1)
        queue.enqueue(send(episode = 2, contentId = "tt0000001", title = "Unknown Show"))
        runCurrent()

        assertTrue(queue.state.value.pending.isEmpty())
        val failure = queue.state.value.failures.single()
        assertTrue(failure.notFound)
        assertEquals("Unknown Show", failure.title)
        assertEquals(listOf<AniListSyncEvent>(AniListSyncEvent.NotFound("Unknown Show")), events)
    }

    @Test
    fun anEpisodeAlreadyDeliveredIsNotSentAgainUntilUnmarked() = runTest {
        val queue = queue()
        queue.open(1)
        queue.enqueue(send(episode = 4))
        runCurrent()
        queue.enqueue(send(episode = 4))
        queue.enqueue(send(episode = 3))
        runCurrent()
        assertEquals(listOf<Pair<String, Int?>>(aniListSendKey("tt2560140", "series", 1) to 4), delivered)

        queue.forgetDelivered(aniListSendKey("tt2560140", "series", 1))
        queue.enqueue(send(episode = 4))
        runCurrent()
        assertEquals(2, delivered.size)
    }

    @Test
    fun seasonsAndMoviesAreSeparateAnime() {
        assertEquals("tt2560140|s3", aniListSendKey("TT2560140", "series", 3))
        assertEquals("tt2560140|s1", aniListSendKey("tt2560140", "series", null))
        assertEquals("tt5323662|movie", aniListSendKey("tt5323662", "movie", null))
    }

    @Test
    fun backoffDoublesUpToSixHours() {
        assertEquals(30_000L, AniListSendQueue.backoffMs(1))
        assertEquals(60_000L, AniListSendQueue.backoffMs(2))
        assertEquals(6L * 60L * 60L * 1_000L, AniListSendQueue.backoffMs(30))
    }

    @Test
    fun linksAreStoredPerTitleAndSeasonAndManualOnesWin() = runTest {
        val queue = queue()
        queue.open(1)
        val key = aniListSendKey("tt2560140", "series", 3)
        val automatic = AniListLink(
            key,
            listOf(
                AniListLinkPart(99147, "Attack on Titan Season 3", firstEpisode = 1, episodes = 12),
                AniListLinkPart(104578, "Attack on Titan Season 3 Part 2", firstEpisode = 13, episodes = 10),
            ),
            manual = false,
        )
        queue.setLink(automatic)
        assertEquals(2, queue.linkFor(key)?.parts?.size)
        assertNull(queue.linkFor(aniListSendKey("tt2560140", "series", 2)))

        val manual = AniListLink(key, listOf(AniListLinkPart(104578, "Attack on Titan Season 3 Part 2", firstEpisode = 13)), manual = true)
        queue.setLink(manual)
        // The mapping never overwrites the user's own choice.
        queue.setLink(automatic)
        assertEquals(true, queue.linkFor(key)?.manual)
        assertEquals(104578, queue.linkFor(key)?.parts?.single()?.mediaId)

        queue.removeLink(key)
        assertNull(queue.linkFor(key))
    }

    @Test
    fun linksSurviveARestart() = runTest {
        val key = aniListSendKey("tt0388629", "series", 1)
        queue().apply {
            open(1)
            setLink(AniListLink(key, listOf(AniListLinkPart(21, "ONE PIECE")), manual = false, absolute = true))
        }
        val reopened = queue()
        reopened.open(1)
        assertEquals(true, reopened.linkFor(key)?.absolute)
        assertEquals(1, storage.lastProfile())
    }

    @Test
    fun aRegularSeriesThatIsNotOnAniListIsDroppedQuietly() = runTest {
        answer = { AniListDelivery.Skipped }
        val queue = queue()
        val events = mutableListOf<AniListSyncEvent>()
        backgroundScope.launch { queue.events.collect { events += it } }
        runCurrent()
        queue.open(1)
        queue.enqueue(send(episode = 3, contentId = "tt0903747", title = "Breaking Bad"))
        runCurrent()
        assertTrue(queue.state.value.pending.isEmpty())
        assertTrue(queue.state.value.failures.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun aTitleThatCannotBeLinkedAtPlaybackStartIsAnnounced() = runTest {
        val queue = queue()
        val events = mutableListOf<AniListSyncEvent>()
        backgroundScope.launch { queue.events.collect { events += it } }
        runCurrent()
        queue.open(1)
        queue.reportLinkNeeded("tt1|s1", "Obscure Anime", "no AniList entry matches")
        runCurrent()
        assertEquals(listOf<AniListSyncEvent>(AniListSyncEvent.LinkNeeded("Obscure Anime")), events)
        assertTrue(queue.state.value.failures.single().notFound)
    }

    @Test
    fun aFailedSendAlsoAsksTheSystemToRetryInTheBackground() = runTest {
        answer = { AniListDelivery.Retry("offline") }
        val scheduled = mutableListOf<Long>()
        val queue = AniListSendQueue(
            storage = storage,
            deliver = { _, item -> answer(item) },
            scope = backgroundScope,
            now = { clock },
            background = { scheduled += it },
        )
        queue.open(1)
        queue.enqueue(send(episode = 1))
        runCurrent()
        assertTrue(scheduled.isNotEmpty())
        assertEquals(0L, scheduled.first())
        assertTrue(scheduled.drop(1).all { it == 30_000L }, "the background retry is due when the next attempt is")
    }

    @Test
    fun eachPartOfASplitSeasonKeepsItsOwnEpisode() = runTest {
        answer = { AniListDelivery.Retry("offline") }
        val queue = queue()
        queue.open(1)
        // Offline: S3E12 ends part 1, S3E13 starts part 2; neither hides the other.
        queue.enqueue(send(episode = 12, season = 3).copy(partMediaId = 99147))
        queue.enqueue(send(episode = 13, season = 3).copy(partMediaId = 104578))
        queue.enqueue(send(episode = 11, season = 3).copy(partMediaId = 99147))
        runCurrent()
        assertEquals(listOf(12, 13), queue.state.value.pending.map { it.episode }.sortedBy { it })
    }

    @Test
    fun retryingSendsEverythingNowEvenDuringALongWait() = runTest {
        answer = { AniListDelivery.Retry("AniList rate limit", retryAtMs = clock + 3_600_000L) }
        val queue = queue()
        queue.open(1)
        queue.enqueue(send(episode = 2))
        runCurrent()
        assertEquals(1, delivered.size)

        answer = { AniListDelivery.Sent("Attack on Titan", 2) }
        queue.retryNow("Back online")
        runCurrent()
        assertEquals(2, delivered.size)
        assertTrue(queue.state.value.pending.isEmpty())
    }

    @Test
    fun unmatchedTitlesAreRememberedUntilLinked() = runTest {
        answer = { AniListDelivery.NotFound("no AniList entry matches") }
        val queue = queue()
        queue.open(1)
        queue.enqueue(send(episode = 1, contentId = "tt0000001", title = "Obscure"))
        runCurrent()
        val key = aniListSendKey("tt0000001", "series", 1)
        assertEquals(listOf(key), queue.state.value.unmatched)
        queue.setLink(AniListLink(key, listOf(AniListLinkPart(1, "Obscure")), manual = true))
        assertTrue(queue.state.value.unmatched.isEmpty())
    }
}
