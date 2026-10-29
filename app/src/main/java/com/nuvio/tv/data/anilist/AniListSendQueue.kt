/*
 * Parts of this file are adapted from Aniyomi (https://github.com/aniyomiorg/aniyomi):
 * domain/track/anime/store/DelayedAnimeTrackingStore.kt and
 * domain/track/anime/interactor/TrackEpisode.kt.
 *
 * Copyright © 2015 Javier Tomás
 * Copyright © 2024 Mihon Open Source Project
 * Copyright © 2024 Aniyomi Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified for Nuvio: per-season keys, persisted links, Retry-After and failure reporting.
 */
package com.nuvio.tv.data.anilist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** A watched episode waiting to reach AniList. */
@Serializable
internal data class AniListPendingSend(
    /** One anime: the show and its season (or "movie"); see [aniListSendKey]. */
    val key: String,
    val title: String,
    val contentType: String,
    val contentId: String,
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val attempts: Int = 0,
    val nextAttemptAtMs: Long = 0L,
    val lastError: String? = null,
    val queuedAtMs: Long = 0L,
    /**
     * The AniList part this episode belongs to, when the season is split (AoT S3 = two entries).
     * Each part keeps its own highest episode, so the end of part 1 is not lost behind part 2.
     */
    val partMediaId: Long? = null,
)

/** One anime as the queue counts it: the title and season, and its AniList part when split. */
internal val AniListPendingSend.queueKey: String
    get() = partMediaId?.let { "$key#$it" } ?: key

private fun Map<String, Int>.withoutTitle(key: String): Map<String, Int> =
    filterKeys { it != key && !it.startsWith("$key#") }

@Serializable
internal data class AniListSendFailure(
    val title: String,
    val reason: String,
    val atMs: Long,
    val notFound: Boolean,
)

/** One AniList entry of a bound season; [firstEpisode] is the season episode that is its episode 1. */
@Serializable
internal data class AniListLinkPart(
    val mediaId: Long,
    val title: String,
    val firstEpisode: Int = 1,
    val episodes: Int? = null,
)

/**
 * The AniList entries one title and season (see [aniListSendKey]) is bound to. Made once, by the
 * mapping when the user starts watching or by hand from the detail page, and used as is by every
 * later sync. A manual link always wins over the mapping.
 */
@Serializable
internal data class AniListLink(
    val key: String,
    val parts: List<AniListLinkPart>,
    val manual: Boolean,
    /** One entry counting the whole show (One Piece). */
    val absolute: Boolean = false,
    val linkedAtMs: Long = 0L,
) {
    val title: String get() = parts.joinToString(" + ") { it.title }
}

@Serializable
internal data class AniListQueueState(
    val pending: List<AniListPendingSend> = emptyList(),
    /** Highest episode AniList confirmed per key, so repeated "watched" signals are not resent. */
    val delivered: Map<String, Int> = emptyMap(),
    val links: List<AniListLink> = emptyList(),
    val lastSyncAtMs: Long? = null,
    val lastSyncTitle: String? = null,
    val lastSyncEpisode: Int? = null,
    /** Newest first. */
    val failures: List<AniListSendFailure> = emptyList(),
    val notifyOnSync: Boolean = true,
    /** Titles and seasons (see [aniListSendKey]) no AniList entry matched; cleared by linking. */
    val unmatched: List<String> = emptyList(),
    /** List entries the background linking already looked up, and when (see [AniListListLinker]). */
    val listChecked: Map<Long, Long> = emptyMap(),
)

internal sealed interface AniListDelivery {
    data class Sent(val title: String, val episode: Int) : AniListDelivery
    data object AlreadyUpToDate : AniListDelivery
    data class NotFound(val reason: String) : AniListDelivery
    data class Retry(val reason: String, val retryAtMs: Long? = null) : AniListDelivery

    /** Not an anime AniList tracks (a regular series): dropped without a warning. */
    data object Skipped : AniListDelivery
}

internal sealed interface AniListSyncEvent {
    data class Synced(val title: String, val episode: Int) : AniListSyncEvent
    data class NotFound(val title: String) : AniListSyncEvent

    /** Raised when playback starts and no entry could be bound: link it by hand before the episode ends. */
    data class LinkNeeded(val title: String) : AniListSyncEvent
}

/** Runs the queue again later with the network up, even if the app is closed (WorkManager on Android). */
internal fun interface AniListBackgroundScheduler {
    fun schedule(delayMs: Long)
}

internal fun aniListSendKey(contentId: String, contentType: String, season: Int?): String {
    val id = contentId.trim().lowercase()
    return if (contentType.equals("movie", ignoreCase = true)) "$id|movie" else "$id|s${season ?: 1}"
}

/**
 * Sends watched episodes to AniList as soon as they happen, and keeps the ones that cannot go out
 * yet on disk until they do: after a closed app, a crash or time without network.
 *
 * - Per anime only the highest pending episode is kept.
 * - Temporary errors (network, 429, 5xx) retry with a growing wait, or AniList's Retry-After.
 * - An anime AniList does not know is dropped from the queue, recorded and announced.
 * - The queue runs when something is queued, when the app opens, when the network comes back and
 *   on "Retry now".
 */
internal class AniListSendQueue(
    private val storage: AniListQueueStorage,
    private val deliver: suspend (profileId: Int, item: AniListPendingSend) -> AniListDelivery,
    private val scope: CoroutineScope,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val background: AniListBackgroundScheduler = AniListBackgroundScheduler { },
    private val canProcess: () -> Boolean = { true },
) {
    private val stateLock = Mutex()
    private val runLock = Mutex()
    private var profileId: Int? = null
    private var wakeJob: Job? = null
    private val mutableState = MutableStateFlow(AniListQueueState())
    val state: StateFlow<AniListQueueState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<AniListSyncEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AniListSyncEvent> = mutableEvents.asSharedFlow()

    /** Loads [profile]'s queue and, unless told otherwise, sends whatever is due. */
    suspend fun open(profile: Int, process: Boolean = true) {
        stateLock.withLock {
            if (profileId != profile) {
                profileId = profile
                mutableState.value = load(profile)
                runCatching { storage.saveLastProfile(profile) }
            }
        }
        if (process) processSoon()
    }

    suspend fun close() = stateLock.withLock {
        profileId = null
        wakeJob?.cancel()
        mutableState.value = AniListQueueState()
    }

    suspend fun enqueue(item: AniListPendingSend) {
        val episode = item.episode ?: 1
        val added = stateLock.withLock {
            val profile = profileId ?: return@withLock false
            val current = mutableState.value
            if ((current.delivered[item.queueKey] ?: 0) >= episode) return@withLock false
            val existing = current.pending.firstOrNull { it.queueKey == item.queueKey }
            if (existing != null && (existing.episode ?: 1) >= episode) return@withLock false
            val queued = item.copy(attempts = 0, nextAttemptAtMs = 0L, lastError = null, queuedAtMs = now())
            update(profile, current.copy(pending = current.pending.filterNot { it.queueKey == item.queueKey } + queued))
            true
        }
        if (added) {
            // Schedule as soon as the event is durable, including an app killed before its first attempt.
            background.schedule(0L)
            InAppLogger.info(TAG, "Queued ${item.title} episode $episode (${item.queueKey})")
            processSoon()
        }
    }

    /** Forgets what was delivered for [key], so marking it watched again sends again. */
    suspend fun forgetDelivered(key: String): Unit = stateLock.withLock {
        val profile = profileId ?: return@withLock
        val current = mutableState.value
        val remaining = current.delivered.withoutTitle(key)
        if (remaining.size != current.delivered.size) update(profile, current.copy(delivered = remaining))
    }

    /** Sends everything pending now, whatever wait each item had left. */
    suspend fun retryNow(why: String = "Retry now") {
        val count = stateLock.withLock {
            val profile = profileId ?: return@withLock 0
            val current = mutableState.value
            if (current.pending.isNotEmpty()) {
                update(profile, current.copy(pending = current.pending.map { it.copy(nextAttemptAtMs = 0L) }))
            }
            current.pending.size
        }
        if (count > 0) InAppLogger.info(TAG, "$why: sending $count pending")
        processSoon()
    }

    suspend fun setNotifyOnSync(enabled: Boolean): Unit = stateLock.withLock {
        val profile = profileId ?: return@withLock
        update(profile, mutableState.value.copy(notifyOnSync = enabled))
    }

    /** Saves [link] for its title and season; a new link also resends the latest episode. */
    suspend fun setLink(link: AniListLink): Unit = stateLock.withLock {
        val profile = profileId ?: return@withLock
        val current = mutableState.value
        val previous = current.links.firstOrNull { it.key == link.key }
        if (previous?.manual == true && !link.manual) return@withLock
        update(
            profile,
            current.copy(
                links = current.links.filterNot { it.key == link.key } + link.copy(linkedAtMs = now()),
                unmatched = current.unmatched - link.key,
                delivered = if (previous?.parts == link.parts) current.delivered else current.delivered.withoutTitle(link.key),
            ),
        )
        InAppLogger.info(
            TAG,
            "${if (link.manual) "Linked by hand" else "Linked"} ${link.key} to AniList " +
                link.parts.joinToString { "${it.mediaId} from episode ${it.firstEpisode}" } +
                if (link.absolute) " (absolute numbering)" else "",
        )
    }

    /**
     * The background linking of the AniList list: saves [links] for titles and seasons that have
     * none yet (never over one already there) and what was looked up, in one write. Nothing when
     * [profile] is no longer the open one.
     */
    suspend fun addListLinks(profile: Int, links: List<AniListLink>, checked: Map<Long, Long>, keepChecked: Set<Long>): Unit = stateLock.withLock {
        if (profileId != profile) return@withLock
        val current = mutableState.value
        val existing = current.links.mapTo(mutableSetOf(), AniListLink::key)
        val added = links.filter { it.key !in existing }.distinctBy(AniListLink::key).map { it.copy(linkedAtMs = now()) }
        val addedKeys = added.mapTo(mutableSetOf(), AniListLink::key)
        update(
            profile,
            current.copy(
                links = current.links + added,
                unmatched = current.unmatched.filterNot(addedKeys::contains),
                listChecked = (current.listChecked + checked).filterKeys(keepChecked::contains),
            ),
        )
        if (added.isNotEmpty()) {
            InAppLogger.info(TAG, "Linked ${added.size} titles of the AniList list: " + added.joinToString { "${it.key} to ${it.parts.joinToString("+") { part -> part.mediaId.toString() }}" })
        }
    }

    suspend fun removeLink(key: String): Unit = stateLock.withLock {
        val profile = profileId ?: return@withLock
        val current = mutableState.value
        update(profile, current.copy(links = current.links.filterNot { it.key == key }, delivered = current.delivered.withoutTitle(key)))
        InAppLogger.info(TAG, "Removed the AniList link of $key")
    }

    /** Records and announces that playback started on a title no AniList entry could be bound to. */
    suspend fun reportLinkNeeded(key: String, title: String, reason: String) {
        stateLock.withLock {
            val profile = profileId ?: return
            val current = mutableState.value
            update(
                profile,
                current.copy(
                    failures = (listOf(AniListSendFailure(title, reason, now(), notFound = true)) +
                        current.failures.filterNot { it.notFound && it.title == title }).take(MAX_FAILURES),
                    unmatched = (current.unmatched - key + key).takeLast(MAX_UNMATCHED),
                ),
            )
        }
        InAppLogger.warn(TAG, "No AniList entry for $title ($key): $reason; asking to link it by hand")
        mutableEvents.tryEmit(AniListSyncEvent.LinkNeeded(title))
    }

    fun linkFor(key: String): AniListLink? = mutableState.value.links.firstOrNull { it.key == key }

    /** The profile whose queue is loaded, if any. */
    val openProfile: Int? get() = profileId

    fun processSoon() {
        scope.launch { process() }
    }

    /** Sends every due item once; reschedules itself for the next retry. */
    suspend fun process() {
        if (!runLock.tryLock()) return
        try {
            while (true) {
                if (!canProcess()) {
                    scheduleWake(minimumDelayMs = 5 * 60_000L)
                    return
                }
                val (profile, item) = stateLock.withLock {
                    val profile = profileId ?: return
                    val due = mutableState.value.pending
                        .filter { it.nextAttemptAtMs <= now() }
                        .minByOrNull { it.queuedAtMs }
                        ?: return@withLock null
                    profile to due
                } ?: break
                val outcome = try {
                    deliver(profile, item)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    AniListDelivery.Retry(InAppLogger.throwableSummary(error))
                }
                record(profile, item, outcome)
            }
        } finally {
            runLock.unlock()
        }
        scheduleWake()
    }

    private suspend fun record(profile: Int, item: AniListPendingSend, outcome: AniListDelivery) {
        val event = stateLock.withLock {
            if (profileId != profile) return
            val current = mutableState.value
            val rest = current.pending.filterNot { it.queueKey == item.queueKey && it.episode == item.episode }
            val replacedMeanwhile = current.pending.any { it.queueKey == item.queueKey && it.episode != item.episode }
            when (outcome) {
                is AniListDelivery.Sent, AniListDelivery.AlreadyUpToDate -> {
                    val episode = item.episode ?: 1
                    val synced = outcome as? AniListDelivery.Sent
                    update(
                        profile,
                        current.copy(
                            pending = rest,
                            delivered = (current.delivered + (item.queueKey to maxOf(episode, current.delivered[item.queueKey] ?: 0)))
                                .entries.toList().takeLast(MAX_DELIVERED).associate { it.key to it.value },
                            lastSyncAtMs = now(),
                            lastSyncTitle = synced?.title ?: item.title,
                            lastSyncEpisode = synced?.episode ?: episode,
                        ),
                    )
                    InAppLogger.info(
                        TAG,
                        if (synced != null) "Synced ${synced.title} episode ${synced.episode}" else "${item.title} already up to date",
                    )
                    synced?.let { AniListSyncEvent.Synced(it.title, it.episode) }
                }
                AniListDelivery.Skipped -> {
                    update(profile, current.copy(pending = rest))
                    InAppLogger.debug(TAG, "Skipped ${item.title}: not on AniList and not an anime")
                    null
                }
                is AniListDelivery.NotFound -> {
                    update(
                        profile,
                        current.copy(
                            pending = rest,
                            unmatched = (current.unmatched - item.key + item.key).takeLast(MAX_UNMATCHED),
                            failures = (listOf(AniListSendFailure(item.title, outcome.reason, now(), notFound = true)) +
                                current.failures).take(MAX_FAILURES),
                        ),
                    )
                    InAppLogger.warn(TAG, "Not found on AniList: ${item.title} (${item.key}): ${outcome.reason}")
                    AniListSyncEvent.NotFound(item.title)
                }
                is AniListDelivery.Retry -> {
                    if (replacedMeanwhile) {
                        update(profile, current.copy(pending = rest))
                        return@withLock null
                    }
                    val attempts = item.attempts + 1
                    val retryAt = outcome.retryAtMs?.takeIf { it > now() } ?: (now() + backoffMs(attempts))
                    val retried = item.copy(attempts = attempts, nextAttemptAtMs = retryAt, lastError = outcome.reason)
                    update(
                        profile,
                        current.copy(
                            pending = rest + retried,
                            failures = (listOf(AniListSendFailure(item.title, outcome.reason, now(), notFound = false)) +
                                current.failures.filterNot { !it.notFound && it.title == item.title }).take(MAX_FAILURES),
                        ),
                    )
                    InAppLogger.warn(
                        TAG,
                        "Send failed for ${item.title} episode ${item.episode ?: 1} (attempt $attempts): " +
                            "${outcome.reason}; retrying in ${(retryAt - now()) / 1_000}s",
                    )
                    null
                }
            }
        }
        event?.let(mutableEvents::tryEmit)
    }

    private fun scheduleWake(minimumDelayMs: Long = 0L) {
        val due = mutableState.value.pending.minOfOrNull { it.nextAttemptAtMs } ?: return
        val next = maxOf(due, now() + minimumDelayMs)
        // Also outside the app: if it is closed before the retry, the system runs it once online.
        runCatching { background.schedule((next - now()).coerceAtLeast(0L)) }
            .onFailure { InAppLogger.warn(TAG, "Could not schedule the background retry: ${InAppLogger.throwableSummary(it)}") }
        wakeJob?.cancel()
        wakeJob = scope.launch {
            delay((next - now()).coerceAtLeast(1_000L))
            process()
        }
    }

    private fun update(profile: Int, state: AniListQueueState) {
        // Commit before publishing: a failed disk write must never look like a durable send.
        storage.save(profile, aniListJson.encodeToString(AniListQueueState.serializer(), state))
        mutableState.value = state
    }

    private fun load(profile: Int): AniListQueueState =
        storage.load(profile)?.let { aniListJson.decodeFromString(AniListQueueState.serializer(), it) }
            ?: AniListQueueState()

    companion object {
        private const val TAG = "Sync/AniList"
        private const val MAX_FAILURES = 10
        private const val MAX_UNMATCHED = 200
        private const val MAX_DELIVERED = 300
        private const val FIRST_RETRY_MS = 30_000L
        private const val MAX_RETRY_MS = 6L * 60L * 60L * 1_000L

        /** 30 s, 1 min, 2 min... up to 6 h. */
        internal fun backoffMs(attempts: Int): Long {
            val shift = (attempts - 1).coerceIn(0, 20)
            return (FIRST_RETRY_MS shl shift).coerceAtMost(MAX_RETRY_MS)
        }
    }
}
