package com.nuvio.tv.core.cloudstorage

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerPlayMethod
import com.nuvio.tv.data.mediaserver.ServerPlaybackSession
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerStreamSource
import com.nuvio.tv.data.mediaserver.catalogIds
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cloud files as stream sources, next to add-ons, plugins and media servers (ported from
 * NuvioMobileKai's `CloudStreams`). Each account is one source group, handled like a media server:
 * the playable URL is made only when the stream is chosen, so it is never saved as a last link.
 *
 * The index is read from disk for each search and dropped afterwards; only the few files that
 * matched are kept, to open them without parsing the index again.
 */
class CloudStreams internal constructor(
    private val repository: CloudStorageRepository,
    private val metaRepository: MetaRepository,
    private val originalTitle: (tmdbId: String, type: ContentType) -> String?,
    private val aliases: CloudTitleAliases,
    private val server: LocalCloudStreamServer
) {
    internal constructor(
        repository: CloudStorageRepository,
        metaRepository: MetaRepository,
        tmdbMetadata: TmdbMetadataService,
        aliases: CloudTitleAliases,
        server: LocalCloudStreamServer
    ) : this(
        repository = repository,
        metaRepository = metaRepository,
        originalTitle = { id, type -> tmdbMetadata.cachedOriginalTitle(id, type) },
        aliases = aliases,
        server = server
    )

    private val lock = Any()
    private val recent = object : LinkedHashMap<String, MatchedFile>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MatchedFile>): Boolean = size > RECENT_FILES
    }
    private val sidecarUrls = mutableMapOf<String, List<String>>()

    val revision: Int get() = repository.uiState.value.revision

    fun sources(type: String, videoId: String, season: Int?, episode: Int?): List<ServerStreamSource> {
        if (ServerItemRef.isServerId(videoId)) return emptyList()
        val accounts = repository.enabledAccounts()
            .filter { it.indexedFiles > 0 || it.manualLinks.isNotEmpty() }
            .ifEmpty { return emptyList() }
        accounts.forEach(repository::refreshIfStale)
        return accounts.map { account ->
            ServerStreamSource(name = account.sourceLabel(), preferred = account.showFirst, limited = true) {
                streams(account, type, videoId, season, episode)
            }
        }
    }

    private suspend fun streams(account: CloudAccount, type: String, videoId: String, season: Int?, episode: Int?): List<Stream> {
        val request = request(type, videoId, season, episode) ?: return emptyList()
        return withContext(Dispatchers.Default) {
            val snapshot = repository.index(account.id) ?: return@withContext emptyList()
            val matches = CloudMatcher.match(snapshot.files, request, account.manualLinks)
            Log.d(TAG, "${matches.size} matches in a ${account.kind.id} account")
            matches.take(MAX_RESULTS).map { match ->
                val sidecars = CloudSidecarSubtitles.find(match.file, snapshot.subtitles)
                synchronized(lock) { recent[key(account.id, match.file.id)] = MatchedFile(match.file, sidecars) }
                match.toStream(account, request, sidecars)
            }
        }
    }

    internal suspend fun request(type: String, videoId: String, season: Int?, episode: Int?): CloudMatchRequest? {
        val parts = videoId.split(':')
        val parentSize = if (videoId.startsWith("tt")) 1 else 2
        if (parts.size < parentSize) return null
        val parentId = parts.take(parentSize).joinToString(":")
        val position = parts.drop(parentSize).mapNotNull { it.toIntOrNull() }
        val isSeries = !type.equals("movie", ignoreCase = true)
        // Like Kai: the cached details, or a fetch when the title was opened without them.
        val meta: Meta? = metaRepository.getCachedMeta(type, parentId)
            ?: withTimeoutOrNull(META_TIMEOUT_MS) {
                runCatching {
                    metaRepository.getMetaFromAllAddons(type, parentId)
                        .first { it !is NetworkResult.Loading }
                        .let { (it as? NetworkResult.Success)?.data }
                }.getOrNull()
            }
        val ids = parseTrackingExternalIds(parentId).let { parsed ->
            val metaImdb = meta?.imdbId?.takeIf { it.startsWith("tt") }
            if (parsed.imdb == null && metaImdb != null) parsed.copy(imdb = metaImdb) else parsed
        }
        val (requestSeason, requestEpisode) = when {
            !isSeries -> null to null
            position.size >= 2 -> (season ?: position[0]) to (episode ?: position[1])
            // Tracker ids (`kitsu:13:5`) number episodes within their own entry.
            position.size == 1 -> (season ?: 1) to (episode ?: position[0])
            else -> season to episode
        }
        if (isSeries && (requestSeason == null || requestEpisode == null)) return null
        val names = listOfNotNull(
            meta?.name,
            ids.tmdb?.let { originalTitle(it.toString(), ContentType.fromString(type)) }
        )
        val titleAliases = aliases.aliases("$type:$parentId", names, ids)
        val perSeason = meta?.videos.orEmpty()
            .filter { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 }
            .groupingBy { it.season!! }
            .eachCount()
        val video = meta?.videos.orEmpty().firstOrNull { it.season == requestSeason && it.episode == requestEpisode }
        return CloudMatchRequest(
            displayTitle = meta?.name?.takeIf { it.isNotBlank() },
            episodeTitle = video?.title?.takeIf { it.isNotBlank() && !GENERIC_EPISODE_TITLE.matches(it.trim()) },
            runtimeMinutes = video?.runtime ?: meta?.runtime?.let { DIGITS.find(it)?.value?.toIntOrNull() },
            isSeries = isSeries,
            contentIds = (listOf(parentId) + ids.catalogIds()).toSet(),
            aliases = titleAliases,
            year = listOfNotNull(meta?.releaseInfo, meta?.released)
                .firstNotNullOfOrNull { YEAR.find(it)?.value?.toIntOrNull() },
            season = requestSeason,
            episode = requestEpisode,
            absoluteEpisode = if (requestSeason != null && requestEpisode != null) {
                absoluteEpisodeNumber(perSeason, requestSeason, requestEpisode)
            } else {
                null
            }
        )
    }

    // ---- Playback ----------------------------------------------------------------------------

    fun isCloudTarget(target: ServerPlaybackTarget?): Boolean =
        target?.item?.connectionId?.startsWith(CONNECTION_PREFIX) == true

    fun isCloudUrl(url: String?): Boolean = server.isLocalUrl(url)

    /**
     * Opens the file on the loopback server: the player reads `http://127.0.0.1:<port>/v1/<token>/…`
     * and the server adds the provider's headers, forwards ranges and replaces expired links.
     * Sidecar subtitles get their own local URLs.
     */
    internal suspend fun prepare(target: ServerPlaybackTarget): ServerPlaybackSession {
        val accountId = target.item.connectionId.removePrefix(CONNECTION_PREFIX)
        val provider = repository.provider(accountId) ?: throw CloudStorageException(CloudFailure.NOT_FOUND)
        val matched = synchronized(lock) { recent[key(accountId, target.item.itemId)] }
            ?: withContext(Dispatchers.Default) {
                val snapshot = repository.index(accountId) ?: return@withContext null
                val file = snapshot.files.firstOrNull { it.id == target.item.itemId } ?: return@withContext null
                MatchedFile(file, CloudSidecarSubtitles.find(file, snapshot.subtitles))
            }
            ?: throw CloudStorageException(CloudFailure.NOT_FOUND)
        val file = matched.file
        val url = server.open(file.name, file.sizeBytes.takeIf { it > 0 }, file.mimeType ?: cloudMimeTypeFor(file.name)) { refresh ->
            provider.resolve(file, refresh)
        }
        val subtitles = matched.sidecars.mapIndexed { index, sidecar ->
            val subtitleUrl = server.open(
                sidecar.file.name,
                sidecar.file.sizeBytes.takeIf { it > 0 },
                cloudSubtitleMimeTypeFor(sidecar.file.name)
            ) { refresh -> provider.resolve(sidecar.file, refresh) }
            Subtitle(
                id = "cloud-sidecar-$index",
                url = subtitleUrl,
                lang = sidecar.language,
                addonName = sidecar.label.ifBlank { sidecar.language.uppercase() },
                addonLogo = null,
                isStreamProvided = true
            )
        }
        synchronized(lock) { sidecarUrls[url] = subtitles.map { it.url } }
        Log.i(TAG, "Serving a cloud file locally with ${subtitles.size} subtitle files")
        return ServerPlaybackSession(
            target = target,
            mediaSourceId = file.id,
            url = url,
            headers = emptyMap(),
            subtitles = subtitles,
            playSessionId = null,
            playMethod = ServerPlayMethod.DIRECT_PLAY
        )
    }

    /** Ends the local session: the server stops reading from the provider for this file. */
    fun stop(url: String?) {
        if (url == null) return
        server.close(url)
        synchronized(lock) { sidecarUrls.remove(url) }?.forEach(server::close)
    }

    private fun key(accountId: String, fileId: String) = "$accountId/$fileId"

    private class MatchedFile(val file: CloudFile, val sidecars: List<CloudSidecarSubtitle>)

    /**
     * The result card, laid out like debrid add-on results but readable from the sofa: the
     * headline carries quality, source and container; then a few short lines. The provider is not
     * repeated: the source group header already names it.
     */
    private fun CloudMatch.toStream(account: CloudAccount, request: CloudMatchRequest, sidecars: List<CloudSidecarSubtitle>): Stream {
        val lines = cloudCardLines(this, request, sidecars)
        return Stream(
            name = lines.headline.ifBlank { file.name },
            title = null,
            description = lines.details.joinToString("\n"),
            url = null,
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = "cloud:${account.id}:${release.group.orEmpty()}:${lines.quality.orEmpty()}",
                countryWhitelist = null,
                proxyHeaders = null,
                videoSize = file.sizeBytes.takeIf { it > 0 },
                filename = file.name
            ),
            addonName = account.sourceLabel(),
            addonLogo = null,
            serverTarget = ServerPlaybackTarget(ServerItemRef("$CONNECTION_PREFIX${account.id}", file.id), mediaSourceId = null)
        )
    }

    companion object {
        const val CONNECTION_PREFIX = "cloud:"
        private const val TAG = "CloudStreams"
        private const val RECENT_FILES = 48
        private const val MAX_RESULTS = 24
        private val GENERIC_EPISODE_TITLE = Regex("(?i)(episode|episodio|ep\\.?)\\s*\\d+")
        private val DIGITS = Regex("\\d+")
        private val YEAR = Regex("""(19|20)\d{2}""")
        private const val META_TIMEOUT_MS = 8_000L
    }
}

internal fun CloudAccount.sourceLabel(): String =
    if (name.equals(kind.displayName, ignoreCase = true)) kind.displayName else "${kind.displayName} · $name"

internal class CloudCardLines(val headline: String, val details: List<String>, val quality: String?)

/**
 * `1080P • BLU-RAY • MKV`, then:
 * `Title (2006) • S01 • E01 • Episode title`
 * `HEVC • FLAC • Subs: SPA · ASS • 1.23 GB • 24m`
 * `Group • folder • file`
 * `Match: title + episode`
 */
internal fun cloudCardLines(match: CloudMatch, request: CloudMatchRequest, sidecars: List<CloudSidecarSubtitle>): CloudCardLines {
    val release = match.release
    val file = match.file
    val quality = (release.resolution ?: file.height?.let { "${it}p" })?.uppercase()
    val format = (release.container ?: file.name.substringAfterLast('.', "")).uppercase().takeIf { it.isNotBlank() && it.length <= 5 }
    val headline = listOfNotNull(quality, release.source?.let(::cloudSourceLabel), format).joinToString(" • ")
    val title = request.displayTitle ?: release.title.takeIf { it.isNotBlank() }
    val year = request.year ?: release.year
    val identity = listOfNotNull(
        title?.let { it + (year?.let { value -> " ($value)" } ?: "") },
        if (request.isSeries && request.season != null && request.episode != null) {
            "S${request.season.twoDigits()} • E${request.episode.twoDigits()}" +
                (release.episode?.takeIf { it != request.episode }?.let { " (#${it.twoDigits()})" } ?: "")
        } else {
            null
        },
        request.episodeTitle
    ).joinToString(" • ")
    val runtimeMs = file.durationMs ?: request.runtimeMinutes?.let { it * 60_000L }
    val technical = listOfNotNull(
        release.videoCodec?.let(::cloudCodecLabel),
        release.audio,
        sidecars.takeIf { it.isNotEmpty() }?.let { list ->
            "Subs: " + list.joinToString(", ") { it.label.ifBlank { it.language.uppercase() } }
        },
        file.sizeBytes.takeIf { it > 0 }?.let(::cloudFormatBytes),
        runtimeMs?.let(::cloudRuntime)
    ).joinToString(" • ")
    val location = listOfNotNull(
        release.group,
        file.parentPath.substringAfterLast('/').takeIf { it.isNotBlank() },
        file.name
    ).joinToString(" • ")
    val matchType = when {
        match.manual -> "Match: manual link"
        request.isSeries && release.isSpecial -> "Match: title + special"
        request.isSeries -> "Match: title + episode"
        else -> "Match: title + year"
    }
    return CloudCardLines(
        headline = headline,
        details = listOf(identity, technical, location, matchType).filter { it.isNotBlank() },
        quality = quality
    )
}

private fun cloudSourceLabel(source: String): String = when (source) {
    "BD" -> "BLU-RAY"
    "Remux" -> "REMUX"
    else -> source.uppercase()
}

private fun cloudCodecLabel(codec: String): String = when (codec) {
    "HEVC" -> "HEVC"
    "H.264" -> "H.264"
    else -> codec
}

private fun cloudRuntime(durationMs: Long): String {
    val minutes = durationMs / 60_000
    return if (minutes >= 60) "${minutes / 60}h ${(minutes % 60).toInt().twoDigits()}m" else "${minutes}m"
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')

internal fun cloudFormatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> {
        val hundredths = (bytes + 5_000_000L) / 10_000_000L
        "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} GB"
    }
    bytes >= 1_000_000L -> "${(bytes + 500_000L) / 1_000_000L} MB"
    else -> "${(bytes + 500L) / 1_000L} KB"
}
