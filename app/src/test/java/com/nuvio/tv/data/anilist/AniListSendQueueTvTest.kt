package com.nuvio.tv.data.anilist

import java.io.IOException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AniListSendQueueTvTest {
    private class Disk : AniListQueueStorage {
        val data = mutableMapOf<Int, String>()
        var writes = 0
        var fail = false
        override fun load(profileId: Int) = data[profileId]
        override fun save(profileId: Int, payload: String) {
            if (fail) throw IOException("Test disk full")
            writes++; data[profileId] = payload
        }
    }
    private fun item(episode: Int) = AniListPendingSend("tt123|s1", "Anime", "series", "tt123", season = 1, episode = episode)
    @Test fun bulkListImportCommitsOnceAndKeepsEveryEntry() = runTest {
        val disk = Disk()
        val q = AniListSendQueue(disk, { _, _ -> AniListDelivery.AlreadyUpToDate }, backgroundScope)
        q.open(1, process = false)
        val links = (1..2000).map { AniListLink("anilist:$it|s1", listOf(AniListLinkPart(it.toLong(), "Anime $it")), false) }
        q.addListLinks(1, links, emptyMap(), emptySet())
        assertEquals(1, disk.writes); assertEquals(2000, q.state.value.links.size)
    }
    @Test fun failedDiskCommitDoesNotClaimEventIsDurable() = runTest {
        val disk = Disk(); val q = AniListSendQueue(disk, { _, _ -> AniListDelivery.AlreadyUpToDate }, backgroundScope)
        q.open(1, process = false); disk.fail = true
        val failure = runCatching { q.enqueue(item(13)) }.exceptionOrNull()
        assertTrue(failure is IOException); assertTrue(q.state.value.pending.isEmpty())
    }
    @Test fun corruptQueueIsNotSilentlyOverwritten() = runTest {
        val disk = Disk().apply { data[1] = "malformed persisted payload" }
        val q = AniListSendQueue(disk, { _, _ -> AniListDelivery.AlreadyUpToDate }, backgroundScope)
        assertNotNull(runCatching { q.open(1, process = false) }.exceptionOrNull())
        assertEquals(0, disk.writes); assertEquals("malformed persisted payload", disk.data[1])
    }
    @Test fun closingQueueStopsAnEnqueuedEventBeforeDelivery() = runTest {
        val disk = Disk(); var sent = 0
        val q = AniListSendQueue(disk, { _, _ -> sent++; AniListDelivery.AlreadyUpToDate }, backgroundScope)
        q.open(1, process = false); q.enqueue(item(13)); q.close(); runCurrent()
        assertEquals(0, sent); assertTrue(q.state.value.pending.isEmpty())
    }
    @Test fun bulkSendsPauseDuringPlaybackWithoutChangingAttemptsOrLosingEvents() = runTest {
        val disk = Disk(); var allowed = false; var sent = 0
        val scheduled = mutableListOf<Long>()
        val q = AniListSendQueue(disk, { _, _ -> sent++; AniListDelivery.AlreadyUpToDate }, backgroundScope,
            now = { 1000L }, background = { scheduled += it }, canProcess = { allowed })
        q.open(1, process = false); q.enqueue(item(13)); runCurrent()
        assertEquals(0, sent); assertEquals(1, q.state.value.pending.size)
        assertEquals(0, q.state.value.pending.single().attempts)
        assertEquals(300000L, scheduled.last())
        allowed = true; q.process()
        assertEquals(1, sent); assertTrue(q.state.value.pending.isEmpty())
    }

}
