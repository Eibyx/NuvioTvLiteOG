package com.nuvio.tv.data.anilist

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.repository.SimklIdResolver
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lightweight TV adapter around Mobile's mapping, prefix import and send queue. */
data class AniListTvState(
    val configured: Boolean = AniListConfig.isConfigured,
    val connected: Boolean = false,
    val accountName: String? = null,
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    val pending: Int = 0,
    val error: String? = null,
    val authorizationUrl: String? = null,
    /** Set while the TV's own browser runs Mobile's OAuth request (app-scheme redirect). */
    val webAuthorizationUrl: String? = null
)

@Singleton
class AniListRepository @Inject internal constructor(
    private val persistence: AndroidAniListAuthPersistence,
    private val disk: AndroidAniListStorage,
    private val profiles: ProfileManager,
    private val metas: MetaRepository,
    private val simkl: SimklIdResolver,
    private val retries: AniListRetryScheduler,
    private val cleaner: AniListProfileCleaner
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private lateinit var auth: AniListAuthStore
    private lateinit var api: AniListApiClient
    private lateinit var resolver: AniListIdResolver
    private var queue: AniListSendQueue? = null
    private var accountJob: Job? = null
    private var snapshot: AniListSyncSnapshot? = null
    private var pairing: AniListPhonePairing? = null
    private var pairingExpiry: Job? = null
    private var pairingProfile: AniListAuthScope? = null
    private val titleVideos = object : LinkedHashMap<String, List<Video>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Video>>?) = size > 4
    }
    private fun playbackActive() = com.nuvio.tv.core.recommendations.TvRecommendationManager.isPlaybackActive.value
    private val mutableState = MutableStateFlow(AniListTvState())
    val state: StateFlow<AniListTvState> = mutableState.asStateFlow()
    private val mutableOverlays = MutableStateFlow<Map<String, AniListEpisodeOverlay>>(emptyMap())
    val overlays: StateFlow<Map<String, AniListEpisodeOverlay>> = mutableOverlays.asStateFlow()

    init {
        scope.launch {
            try {
                profiles.activeProfileReady.first { it }
                auth = AniListAuthStore(persistence, profiles.activeProfileId.value)
                api = AniListApiClient(auth)
                resolver = AniListIdResolver(api, simkl, showEpisodes = { id -> fetchMeta("series", id)?.videos })
                resolver.storedLink = { queue?.linkFor(it.toPendingSend().key) }
                scope.launch {
                    cleaner.changes.collect { profile ->
                        gate.withLock {
                            if (profile == -1 || profile == auth.scope().profileId) {
                                auth.clearAuth()
                                stopPairingLocked()
                                openAccountLocked()
                            }
                        }
                    }
                }
                initialized.complete(Unit)
                profiles.activeProfileId.collectLatest { id ->
                    gate.withLock {
                        stopPairingLocked()
                        auth.selectProfile(id)
                        openAccountLocked()
                    }
                    // Only pending sends are retried on startup. Full list refresh is explicit.
                    retryPending()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { initialized.completeExceptionally(e); report(e) }
        }
    }

    private suspend fun fetchMeta(type: String, id: String): Meta? = withTimeoutOrNull(20_000) {
        metas.getMetaFromAllAddons(type, id).first { it !is NetworkResult.Loading }
            .let { (it as? NetworkResult.Success)?.data }
    }

    private suspend fun openAccountLocked() {
        accountJob?.cancel()
        queue?.close()
        queue = null
        auth.refreshExpiry()
        val account = auth.scope()
        snapshot = if (auth.state.value.isAuthenticated) disk.snapshot(account.profileId)
            ?.takeIf { it.viewerId == auth.state.value.viewer?.id } else null
        mutableOverlays.value = emptyMap()
        titleVideos.clear()
        if (auth.state.value.isAuthenticated) {
            val job = SupervisorJob(scope.coroutineContext[Job]); accountJob = job
            val accountScope = CoroutineScope(job + Dispatchers.IO)
            val q = AniListSendQueue(disk, { profile, item -> deliver(account, profile, item) }, accountScope,
                background = { delay -> retries.schedule(delay) },
                canProcess = { !playbackActive() || (queue?.state?.value?.pending?.size ?: 0) <= 1 })
            q.open(account.profileId, process = false)
            queue = q
            accountScope.launch { q.state.collect { gate.withLock { if (queue === q) publishLocked() } } }
        }
        publishLocked()
    }

    private fun publishLocked(error: String? = null, syncing: Boolean = false) {
        val connected = auth.state.value.isAuthenticated && auth.scope().profileId == profiles.activeProfileId.value
        val q = queue
        if (connected) {
            val entries = snapshot?.entries.orEmpty()
            mutableOverlays.value = titleVideos.mapValues { (id, videos) ->
                val directIds = videos.mapNotNull { parseAniListAnimeVideoId(it.id) }.map { "${it.prefix}:${it.id}" }.toSet()
                val allLinks = q?.state?.value?.links.orEmpty()
                aniListOverlay(allLinks.filter { it.key.substringBeforeLast('|') == id }, entries, videos,
                    allLinks.filter { it.key.substringBeforeLast('|') in directIds })
            }
        } else mutableOverlays.value = emptyMap()
        mutableState.value = mutableState.value.copy(connected = connected, accountName = auth.state.value.viewer?.name,
            syncing = syncing, lastSyncAt = maxOf(snapshot?.checkedAtEpochMs ?: 0, q?.state?.value?.lastSyncAtMs ?: 0).takeIf { it > 0 },
            pending = q?.state?.value?.pending?.size ?: 0,
            error = error ?: q?.state?.value?.pending?.firstNotNullOfOrNull { it.lastError }
                ?: q?.state?.value?.failures?.firstOrNull { it.notFound }?.reason)
    }

    private fun report(error: Throwable) {
        // Never publish exception bodies or callback URLs; they can include OAuth credentials.
        mutableState.update { it.copy(syncing = false, error = when (error) {
            is AniListAuthException -> "La autorización de AniList ha caducado. Conecta de nuevo."
            is AniListApiException -> "AniList respondió ${error.status}. Se conservarán los envíos pendientes."
            else -> "No se pudo sincronizar AniList. Comprueba la conexión y reintenta."
        }) }
    }

    fun connect() { scope.launch {
        initialized.await()
        try { gate.withLock {
            require(AniListConfig.isConfigured) { "Missing TV OAuth client" }
            stopPairingLocked()
            val account = auth.scope(); pairingProfile = account
            auth.beginAuthorization(account)
            if (AniListConfig.usesPhonePairing) {
                pairing = AniListPhonePairing(AniListConfig.clientId, AniListConfig.redirectUri) { token, expires ->
                    scope.launch { finishAuthorization(account, token, expires) }
                }.also { it.start() }
                mutableState.update { it.copy(error = null, authorizationUrl = pairing!!.authorizeUrl) }
            } else {
                mutableState.update { it.copy(error = null, webAuthorizationUrl = AniListConfig.tvBrowserAuthorizeUrl()) }
            }
            pairingExpiry = scope.launch {
                delay(10 * 60_000L)
                gate.withLock {
                    if (pairingProfile == account) {
                        stopPairingLocked()
                        mutableState.update { it.copy(error = "La conexión de AniList ha caducado. Iníciala de nuevo.") }
                    }
                }
            }
        } } catch (e: CancellationException) { throw e } catch (e: Exception) { report(e) }
    } }

    private suspend fun finishAuthorization(account: AniListAuthScope, token: String, expires: Long) {
        try {
            gate.withLock {
                if (!auth.isCurrent(account) || pairingProfile != account || account.profileId != profiles.activeProfileId.value) return
                auth.authorize(token, expires, account)
                val authorized = auth.scope()
                val viewer = try { api.viewer(authorized).also { auth.saveViewer(it, authorized) } }
                catch (e: Exception) { auth.clearAuth(authorized); throw e }
                stopPairingLocked()
                // Reauthorizing the same account retains pending sends. Different accounts cannot inherit them.
                if (disk.snapshot(authorized.profileId)?.viewerId != viewer.id) disk.remove(authorized.profileId)
                openAccountLocked()
            }
            syncNowInternal()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { report(e) }
    }

    /** Called by the TV browser for every navigation; true when it was the OAuth redirect. */
    fun handleWebCallback(url: String): Boolean {
        val callback = parseAniListCallback(url, AniListConfig.redirectUri) ?: return false
        scope.launch {
            initialized.await()
            // Close the browser as soon as AniList answers; repeated callbacks are ignored.
            val account = gate.withLock {
                pairingProfile?.takeIf { mutableState.value.webAuthorizationUrl != null }
                    ?.also { mutableState.update { it.copy(webAuthorizationUrl = null) } }
            } ?: return@launch
            when (callback) {
                is AniListCallback.Token -> finishAuthorization(account, callback.token,
                    System.currentTimeMillis() + (callback.expiresInSeconds ?: 31_536_000L) * 1000)
                AniListCallback.Denied -> gate.withLock { stopPairingLocked() }
                AniListCallback.Invalid -> gate.withLock {
                    stopPairingLocked()
                    mutableState.update { it.copy(error = "AniList no devolvió una autorización válida. Inténtalo de nuevo.") }
                }
            }
        }
        return true
    }

    fun cancelConnection() { scope.launch { initialized.await(); gate.withLock { stopPairingLocked() } } }
    private fun stopPairingLocked() {
        pairingExpiry?.cancel(); pairingExpiry = null
        pairing?.stop(); pairing = null; pairingProfile = null
        if (::auth.isInitialized) auth.cancelAuthorization()
        mutableState.update { it.copy(authorizationUrl = null, webAuthorizationUrl = null) }
    }

    fun disconnect() { scope.launch {
        initialized.await()
        gate.withLock {
            stopPairingLocked()
            val profile = auth.scope().profileId
            auth.clearAuth()
            accountJob?.cancel(); queue?.close(); queue = null
            disk.remove(profile); snapshot = null
            publishLocked()
        }
    } }

    fun syncNow() { scope.launch {
        try { initialized.await(); syncNowInternal() }
        catch (e: CancellationException) { throw e } catch (e: Exception) { report(e) }
    } }

    private suspend fun syncNowInternal() {
        gate.withLock {
            auth.refreshExpiry()
            if (!auth.state.value.isAuthenticated) { publishLocked(); return }
            if (playbackActive()) { publishLocked(); return }
            val account = auth.scope()
            publishLocked(syncing = true)
            try {
                val viewer = auth.state.value.viewer ?: api.viewer(account).also { auth.saveViewer(it, account) }
                val entries = api.collection(account, viewer.id)
                auth.checkScope(account)
                snapshot = AniListSyncSnapshot(viewer.id, entries, snapshot?.mappings.orEmpty(), checkedAtEpochMs = System.currentTimeMillis())
                disk.saveSnapshot(account.profileId, snapshot!!)
                // Per-entry anime identifiers are exact. IMDb/TVDB seasons are bound only on opening a title.
                val entryLinks = entries.flatMap { entry ->
                    val part = AniListLinkPart(entry.mediaId, entry.media?.displayTitle() ?: "AniList ${entry.mediaId}", episodes = entry.media?.episodes)
                    listOfNotNull("anilist:${entry.mediaId}", entry.media?.idMal?.let { "mal:$it" }).map { id ->
                        AniListLink(aniListSendKey(id, "series", 1), listOf(part), manual = false)
                    }
                }
                queue?.addListLinks(account.profileId, entryLinks, emptyMap(), emptySet())
                publishLocked()
            } catch (e: Exception) { publishLocked(); throw e }
        }
        retryPending(force = true)
    }

    /** Title opening only reads AniList. No history, progress or other provider is written. */
    suspend fun refreshTitle(meta: Meta) = withContext(Dispatchers.IO) {
        initialized.await()
        if (meta.apiType == "movie") return@withContext
        try { gate.withLock {
            if (!auth.state.value.isAuthenticated) return@withLock
            val account = auth.scope()
            val id = meta.id.lowercase()
            titleVideos[id] = meta.videos
            // Cached imports must appear after restart even when every network lookup fails.
            publishLocked()
            if (playbackActive()) return@withLock
            // IMDb is a verified metadata identifier supplied by the installed addon (including AIOmetadata).
            val baseIds = parseTrackingExternalIds(meta.id).mergeMissing(parseTrackingExternalIds(meta.imdbId))
            val directLinks = meta.videos.mapNotNull { video ->
                parseAniListAnimeVideoId(video.id)?.takeIf { (it.episode ?: 0) > 0 }?.let { it to video }
            }.distinctBy { it.first.prefix to it.first.id }.mapNotNull { (direct, video) ->
                if (playbackActive()) return@mapNotNull null
                val content = "${direct.prefix}:${direct.id}"
                resolver.link(buildTrackingMediaReference("series", content, video.id, meta.name,
                    seasonNumber = 1, episodeNumber = direct.episode), snapshot)?.also { queue?.setLink(it) }
            }
            val links = meta.videos.mapNotNull { it.season }.distinct().sorted().mapNotNull { season ->
                if (playbackActive()) return@mapNotNull null
                val first = meta.videos.firstOrNull { it.season == season && (it.episode ?: 0) > 0 } ?: return@mapNotNull null
                if (parseAniListAnimeVideoId(first.id) != null) return@mapNotNull null
                val ref = buildTrackingMediaReference(meta.apiType, meta.id, first.id, meta.name,
                    seasonNumber = season, episodeNumber = first.episode).copy(ids = baseIds.mergeMissing(parseTrackingExternalIds(first.id)))
                val key = ref.toPendingSend().key
                queue?.linkFor(key) ?: resolver.link(ref, snapshot)?.also { queue?.setLink(it) }
            }
            var entries = snapshot?.entries.orEmpty()
            for (mediaId in (links + directLinks).flatMap { it.parts }.map { it.mediaId }.distinct()) {
                if (playbackActive()) break
                val (media, entry) = api.mediaById(account, mediaId) ?: continue
                entries = entries.filterNot { it.mediaId == mediaId } + listOfNotNull(entry?.copy(media = media))
            }
            auth.checkScope(account)
            val viewer = auth.state.value.viewer ?: api.viewer(account).also { auth.saveViewer(it, account) }
            snapshot = (snapshot ?: AniListSyncSnapshot(viewer.id)).copy(entries = entries)
            disk.saveSnapshot(account.profileId, snapshot!!)
            publishLocked()
        } } catch (e: CancellationException) { throw e } catch (e: Exception) { report(e) }
    }

    /** Called only by Lite's original completed-episode path. Persist first; perform network work later. */
    suspend fun episodeWatched(profileId: Int, progress: WatchProgress) = withContext(Dispatchers.IO) {
        initialized.await()
        if (progress.episode == null || progress.episode <= 0 || !progress.isCompleted()) return@withContext
        gate.withLock {
            if (!auth.state.value.isAuthenticated || auth.scope().profileId != profileId || profiles.activeProfileId.value != profileId) return@withLock
            val ref = buildTrackingMediaReference(progress.contentType, progress.contentId, progress.videoId, progress.name,
                seasonNumber = progress.season, episodeNumber = progress.episode)
            val item = ref.toPendingSend()
            val link = queue?.linkFor(item.key)
            // Without a link yet, keep each episode until resolution, avoiding loss of an earlier cour.
            val part = if (parseAniListAnimeVideoId(progress.videoId) != null) null
                else link?.parts?.lastOrNull { it.firstEpisode <= progress.episode }?.mediaId
            queue?.enqueue(item.copy(partMediaId = part ?: -progress.episode.toLong()))
            publishLocked()
        }
    }

    private suspend fun deliver(account: AniListAuthScope, profile: Int, item: AniListPendingSend): AniListDelivery = gate.withLock {
        if (!auth.isCurrent(account) || !auth.state.value.isAuthenticated || profile != profiles.activeProfileId.value)
            return@withLock AniListDelivery.Retry("AniList account is not active", System.currentTimeMillis() + 21_600_000)
        try {
            val raw = item.toReference()
            val meta = metas.getCachedMeta(item.contentType, item.contentId)
                ?: if (raw.ids.imdb == null && raw.ids.anilist == null && raw.ids.mal == null && raw.ids.kitsu == null && parseAniListAnimeVideoId(item.videoId) == null)
                    fetchMeta(item.contentType, item.contentId) else null
            val ref = raw.copy(ids = raw.ids.mergeMissing(parseTrackingExternalIds(meta?.imdbId)))
            val directVideo = parseAniListAnimeVideoId(item.videoId) != null
            val link = (if (directVideo) resolver.link(ref, snapshot) else queue?.linkFor(item.key))
                ?: resolver.link(ref, snapshot)?.also { if (!directVideo) queue?.setLink(it) }
                ?: return@withLock AniListDelivery.NotFound("No hay una correspondencia verificada para esta temporada")
            if (!directVideo && !link.absolute && link.parts.endsBefore(item.episode ?: 0)) return@withLock AniListDelivery.NotFound("Episodio fuera de la entrada AniList")
            val target = resolver.targetFor(link, ref) ?: return@withLock AniListDelivery.NotFound("Numeración absoluta o temporada sin verificar")
            AniListProgressSender(api).send(account, target) { saved, media ->
                snapshot?.let { old ->
                    snapshot = old.copy(entries = old.entries.filterNot { it.mediaId == saved.mediaId } + saved.copy(media = media))
                    disk.saveSnapshot(profile, snapshot!!)
                }
                publishLocked()
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            publishLocked(); report(e)
            AniListDelivery.Retry(InAppLogger.throwableSummary(e), (e as? AniListApiException)?.retryAtEpochMs)
        }
    }

    internal suspend fun retryPending(force: Boolean = false) {
        initialized.await()
        val q = gate.withLock { queue }
        if (force) q?.retryNow()
        q?.process()
    }
}
