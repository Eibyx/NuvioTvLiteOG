package com.nuvio.tv.data.anilist

import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.data.repository.SimklIdResolver
import com.nuvio.tv.data.simkl.SimklAnimeIdPreference
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal data class AniListTarget(
    val mediaId: Long,
    val episode: Int,
)

internal data class AniListAnimeVideoId(
    val prefix: String,
    val id: Long,
    val episode: Int?,
)

private val ANIME_VIDEO_ID_PREFIXES = setOf("mal", "anidb", "anilist", "kitsu")
private val SERIES_FORMATS = setOf("TV", "TV_SHORT", "ONA")

internal fun parseAniListAnimeVideoId(videoId: String?): AniListAnimeVideoId? {
    val trimmed = videoId?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    val prefix = trimmed.substringBefore(':').lowercase()
    if (prefix !in ANIME_VIDEO_ID_PREFIXES) return null
    val rest = trimmed.substringAfter(':', "")
    val id = rest.substringBefore(':').toLongOrNull() ?: return null
    val episode = rest.substringAfter(':', "").substringBefore(':').toIntOrNull()
    return AniListAnimeVideoId(prefix, id, episode)
}

internal fun AniListMapping.contentId(preference: SimklAnimeIdPreference): String = when (preference) {
    SimklAnimeIdPreference.MAL -> mal?.let { "mal:$it" } ?: kitsu?.let { "kitsu:$it" } ?: imdb ?: "anilist:$anilist"
    SimklAnimeIdPreference.KITSU -> kitsu?.let { "kitsu:$it" } ?: mal?.let { "mal:$it" } ?: imdb ?: "anilist:$anilist"
    SimklAnimeIdPreference.IMDB,
    SimklAnimeIdPreference.TVDB,
    -> imdb ?: kitsu?.let { "kitsu:$it" } ?: mal?.let { "mal:$it" } ?: "anilist:$anilist"
}

internal fun AniListMapping.aliases(): Set<String> = linkedSetOf<String>().apply {
    imdb?.takeIf(String::isNotBlank)?.let(::add)
    mal?.let { add("mal:$it") }
    kitsu?.let { add("kitsu:$it") }
    anidb?.let { add("anidb:$it") }
    add("anilist:$anilist")
}

internal fun AniListMapping.isImdbContent(contentId: String): Boolean =
    imdb != null && contentId.equals(imdb, ignoreCase = true)

internal fun AniListMapping.coordinates(contentId: String, episode: Int): Pair<Int, Int> {
    if (!isImdbContent(contentId)) return 1 to episode
    episodes.firstOrNull { it.anime == episode }?.let { return it.season to it.episode }
    episodes.filter { it.anime < episode }.maxByOrNull { it.anime }?.let { previous ->
        return previous.season to (previous.episode + (episode - previous.anime))
    }
    episodes.filter { it.anime > episode }.minByOrNull { it.anime }?.let { next ->
        return next.season to (next.episode - (next.anime - episode)).coerceAtLeast(1)
    }
    return (tvdbSeason ?: 1) to (episode + (episodeOffset ?: 0))
}

internal fun AniListMapping.exactAnimeEpisode(season: Int, episode: Int): Int? =
    episodes.firstOrNull { it.season == season && it.episode == episode }?.anime

internal fun AniListMapping.approximateAnimeEpisode(season: Int, episode: Int, totalEpisodes: Int): Int? {
    val inSeason = episodes.filter { it.season == season }
    val candidate = if (inSeason.isNotEmpty()) {
        val previous = inSeason.filter { it.episode <= episode }.maxByOrNull { it.episode } ?: return null
        previous.anime + (episode - previous.episode)
    } else if (episodes.isEmpty() && (tvdbSeason ?: 1) == season) {
        episode - (episodeOffset ?: 0)
    } else {
        return null
    }
    return candidate.takeIf { it >= 1 && (totalEpisodes <= 0 || it <= totalEpisodes) }
}

internal fun aniListVideoId(contentId: String, season: Int, episode: Int): String {
    val prefix = contentId.substringBefore(':').lowercase()
    return if (prefix in ANIME_VIDEO_ID_PREFIXES && contentId.contains(':')) {
        "$contentId:$episode"
    } else {
        "$contentId:$season:$episode"
    }
}

internal fun fallbackAniListMapping(media: AniListMedia): AniListMapping =
    AniListMapping(anilist = media.id, mal = media.idMal)

/** A network or server failure while resolving: the send is retried, not reported as "not found". */
internal class AniListTransientException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One row of ARM's /imdb answer: an AniList entry and the TVDB season it belongs to. */
internal data class ArmImdbEntry(
    val anilist: Long,
    val tvdbSeason: Int?,
    val media: String?,
)

internal class AniListIdResolver(
    private val api: AniListApiClient,
    private val simklResolver: SimklIdResolver,
    private val client: HttpClient = createApiHttpClient(),
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    /** The show's episode list, to turn a season episode into an absolute one (One Piece). */
    private val showEpisodes: suspend (imdb: String) -> List<Video>? = { null },
) {
    private val cacheLock = Mutex()
    private val animeIdCache = boundedAniListCache<String, Long?>()
    private val imdbCache = boundedAniListCache<String, List<Long>>()
    private val imdbSeasonCache = boundedAniListCache<String, List<ArmImdbEntry>>()
    private val mediaCache = boundedAniListCache<Long, AniListMedia>()

    /** The saved link of a title and season (see [AniListLink]); used as is, never guessed again. */
    @kotlin.concurrent.Volatile
    internal var storedLink: (TrackingMediaReference) -> AniListLink? = { null }

    /** The entry and episode to write for [reference]: from its saved link, else freshly bound. */
    suspend fun resolve(reference: TrackingMediaReference, snapshot: AniListSyncSnapshot?): AniListTarget? {
        val link = storedLink(reference) ?: link(reference, snapshot) ?: return null
        return targetFor(link, reference)
    }

    /** [reference]'s episode on the entries of [link]. */
    internal suspend fun targetFor(link: AniListLink, reference: TrackingMediaReference): AniListTarget? {
        if (link.parts.isEmpty()) return null
        if (reference.episode?.number?.let { it <= 0 } == true) return null
        if (reference.isMovie()) return AniListTarget(link.parts.first().mediaId, 1)
        val animeVideo = parseAniListAnimeVideoId(reference.catalog?.videoId)
        val episode = animeVideo?.episode ?: reference.episode?.number
        // Per-video AIOmetadata anime IDs already identify the part and its own episode.
        if (animeVideo != null) {
            val mediaId = animeIdToAniList(animeVideo.prefix, animeVideo.id, null) ?: return null
            if ((episode ?: 0) <= 0) return null
            if (reference.episode?.season == 0 && mediaFor(listOf(mediaId))[mediaId]?.format !in setOf("SPECIAL", "OVA")) return null
            return AniListTarget(mediaId, episode!!)
        }
        val absolute = if (link.absolute) {
            val imdb = reference.ids.imdb?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
            val season = reference.episode?.season
            if (imdb != null && season != null && episode != null) absoluteEpisode(imdb, season, episode) else null
        } else {
            null
        }
        if (link.absolute && (reference.episode?.season ?: 1) > 1 && absolute == null) return null
        return targetIn(link.parts, link.absolute, episode, absolute)
    }

    /**
     * Binds [reference]'s title and season to its AniList entries: anime ids map straight to one
     * entry, IMDb shows go through ARM's season table (parts, cours, specials, absolute numbering).
     * Returns null when nothing matches; network and server failures throw
     * [AniListTransientException] so the caller retries instead of reporting "not found".
     */
    suspend fun link(reference: TrackingMediaReference, snapshot: AniListSyncSnapshot?): AniListLink? {
        if (reference.episode?.number?.let { it <= 0 } == true) return null
        val key = reference.toPendingSend().key
        val isMovie = reference.isMovie()
        val season = reference.episode?.season

        parseAniListAnimeVideoId(reference.catalog?.videoId)?.let { parts ->
            animeIdToAniList(parts.prefix, parts.id, snapshot)?.let { return singleLink(key, it) }
        }
        val ids = reference.ids
        if (!isMovie && (season ?: 1) != 1 && (ids.anilist != null || ids.mal != null || ids.kitsu != null || ids.anidb != null)) return null
        ids.anilist?.let { return singleLink(key, it) }
        ids.mal?.let { mal -> animeIdToAniList("mal", mal, snapshot)?.let { return singleLink(key, it) } }
        ids.kitsu?.let { kitsu -> animeIdToAniList("kitsu", kitsu, snapshot)?.let { return singleLink(key, it) } }
        ids.anidb?.let { anidb -> animeIdToAniList("anidb", anidb, snapshot)?.let { return singleLink(key, it) } }
        val imdb = ids.imdb?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: return null

        val entries = imdbSeasonEntries(imdb)
        if (entries.isNotEmpty()) {
            val media = mediaFor(entries.map(ArmImdbEntry::anilist))
            val candidates = entries.map { entry ->
                val details = media[entry.anilist]
                AniListSeasonCandidate(
                    mediaId = entry.anilist,
                    format = details?.format ?: entry.media,
                    tvdbSeason = entry.tvdbSeason,
                    episodes = details?.episodes,
                    startKey = details?.startKey() ?: Long.MAX_VALUE,
                    title = details?.displayTitle() ?: "AniList ${entry.anilist}",
                )
            }
            val plan = verifiedAniListLinkPlan(candidates, season, isMovie) ?: return null
            return AniListLink(key, plan.parts, manual = false, absolute = plan.absolute)
        }
        return simklLink(key, imdb, season, reference.episode?.number, isMovie, snapshot)
            ?: snapshotLink(key, imdb, season, isMovie, snapshot)
    }

    private suspend fun singleLink(key: String, mediaId: Long): AniListLink {
        val title = mediaFor(listOf(mediaId))[mediaId]?.displayTitle() ?: "AniList $mediaId"
        return AniListLink(key, listOf(AniListLinkPart(mediaId, title)), manual = false)
    }

    /** ARM has no row for the show: Simkl's episode table, when Simkl is configured. */
    private suspend fun simklLink(
        key: String,
        imdb: String,
        season: Int?,
        episode: Int?,
        isMovie: Boolean,
        snapshot: AniListSyncSnapshot?,
    ): AniListLink? {
        if (isMovie || BuildConfig.SIMKL_CLIENT_ID.isBlank()) return null
        val wantedSeason = season ?: 1
        val wantedEpisode = (episode ?: 1).coerceAtLeast(1)
        val ids = try {
            simklResolver.resolveIdsForImdbEpisode(imdb, wantedSeason, wantedEpisode)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        } ?: return null
        if (ids.type != "anime") return null
        val anilist = ids.anilist?.toLongOrNull()
            ?: ids.mal?.toLongOrNull()?.let { animeIdToAniList("mal", it, snapshot) }
            ?: return null
        val episodes = try {
            simklResolver.getEpisodeMapping(ids.simklId, ids.type)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            emptyList()
        }
        val animeEpisode = episodes.firstOrNull { it.tvdbSeason == wantedSeason && it.tvdbEpisode == wantedEpisode }
            ?.animeEpisode ?: return null
        if (animeEpisode > wantedEpisode) return null // Offset cannot be represented by a verified prefix link.
        val single = singleLink(key, anilist)
        return single.copy(parts = single.parts.map { it.copy(firstEpisode = (wantedEpisode - animeEpisode + 1).coerceAtLeast(1)) })
    }

    /** Last resort: the season data the AniList list sync already saved for entries in the list. */
    private fun snapshotLink(
        key: String,
        imdb: String,
        season: Int?,
        isMovie: Boolean,
        snapshot: AniListSyncSnapshot?,
    ): AniListLink? {
        val snap = snapshot ?: return null
        val candidates = snap.mappings.values.filter { it.imdb.equals(imdb, ignoreCase = true) }
        fun titleOf(mediaId: Long) = snap.entries.firstOrNull { it.mediaId == mediaId }?.media?.displayTitle() ?: "AniList $mediaId"
        if (isMovie) {
            val only = candidates.singleOrNull() ?: return null
            return AniListLink(key, listOf(AniListLinkPart(only.anilist, titleOf(only.anilist))), manual = false)
        }
        val parts = candidates
            .filter { it.seasonResolved && (it.tvdbSeason ?: 1) == (season ?: 1) }
            .sortedBy { it.episodeOffset ?: 0 }
            .map { mapping ->
                AniListLinkPart(
                    mediaId = mapping.anilist,
                    title = titleOf(mapping.anilist),
                    firstEpisode = 1 + (mapping.episodeOffset ?: 0).coerceAtLeast(0),
                    episodes = snap.entries.firstOrNull { it.mediaId == mapping.anilist }?.media?.episodes,
                )
            }
        return parts.takeIf { it.isNotEmpty() }?.let { AniListLink(key, it, manual = false) }
    }

    private fun TrackingMediaReference.isMovie(): Boolean =
        kind == TrackingMediaKind.MOVIE || catalog?.contentType.equals("movie", ignoreCase = true)

    suspend fun mapping(media: AniListMedia, existing: AniListMapping?, wantSeason: Boolean): AniListMapping? {
        val nowMs = now()
        var mapping = existing ?: fallbackAniListMapping(media)
        var changed = existing == null
        val stale = existing == null || existing.resolvedAtEpochMs <= 0L ||
            nowMs - existing.resolvedAtEpochMs > MAPPING_TTL_MS
        if (stale) {
            val result = getJson("${AniListConfig.ARM_BASE_URL}/ids?source=anilist&id=${media.id}&include=myanimelist,kitsu,anidb,imdb")
            if (result != null) {
                val value = result as? JsonObject
                mapping = mapping.copy(
                    mal = value?.longValue("myanimelist") ?: media.idMal ?: mapping.mal,
                    kitsu = value?.longValue("kitsu") ?: mapping.kitsu,
                    anidb = value?.longValue("anidb") ?: mapping.anidb,
                    imdb = value?.stringValue("imdb")?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: mapping.imdb,
                    seasonResolved = if (existing == null) false else mapping.seasonResolved,
                    resolvedAtEpochMs = nowMs,
                )
                changed = true
            }
        }
        if (wantSeason && !mapping.seasonResolved && !media.isMovie) {
            val ids = if (BuildConfig.SIMKL_CLIENT_ID.isNotBlank()) {
                try {
                    simklResolver.resolveIds("anilist", media.id.toString(), "anime")
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
            mapping = if (ids != null) {
                val episodes = try {
                    simklResolver.getEpisodeMapping(ids.simklId, ids.type)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    emptyList()
                }
                val coordinates = episodes
                    .map { AniListEpisodeCoordinate(anime = it.animeEpisode, season = it.tvdbSeason, episode = it.tvdbEpisode) }
                    .distinctBy(AniListEpisodeCoordinate::anime)
                    .sortedBy(AniListEpisodeCoordinate::anime)
                val first = coordinates.firstOrNull()
                mapping.copy(
                    imdb = mapping.imdb ?: ids.imdb?.takeIf { it.startsWith("tt", ignoreCase = true) },
                    tvdbSeason = first?.season ?: ids.tvdbSeason,
                    episodeOffset = first?.let { it.episode - it.anime },
                    episodes = coordinates,
                    seasonResolved = true,
                )
            } else {
                val imdb = mapping.imdb
                if (imdb == null) {
                    mapping.copy(seasonResolved = true)
                } else {
                    mapping.copy(
                        tvdbSeason = imdbSeasonEntries(imdb).firstOrNull { it.anilist == media.id }?.tvdbSeason,
                        episodeOffset = 0,
                        episodes = emptyList(),
                        seasonResolved = imdbSeasonEntries(imdb).any { it.anilist == media.id && it.tvdbSeason != null },
                    )
                }
            }
            changed = true
        }
        return mapping.takeIf { changed }
    }

    private suspend fun guessImdbSeason(imdb: String, mediaId: Long): Int? {
        val candidates = imdbCandidates(imdb)
        if (candidates.size <= 1) return 1
        val media = try {
            api.mediaPage(null, candidates)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return null
        }
        val ordered = orderedSeriesMedia(media)
        val index = ordered.indexOfFirst { it.id == mediaId }
        return if (index >= 0) index + 1 else null
    }

    private fun orderedSeriesMedia(media: List<AniListMedia>): List<AniListMedia> = media
        .filter { it.format?.uppercase() in SERIES_FORMATS }
        .sortedWith(
            compareBy<AniListMedia>(
                { it.startDate?.year ?: Int.MAX_VALUE },
                { it.startDate?.month ?: 12 },
                { it.startDate?.day ?: 31 },
            ),
        )

    private suspend fun animeIdToAniList(prefix: String, id: Long, snapshot: AniListSyncSnapshot?): Long? {
        if (prefix == "anilist") return id
        snapshot?.let { snap ->
            snap.mappings.values.firstOrNull { mapping ->
                when (prefix) {
                    "mal" -> mapping.mal == id
                    "kitsu" -> mapping.kitsu == id
                    "anidb" -> mapping.anidb == id
                    else -> false
                }
            }?.let { return it.anilist }
            if (prefix == "mal") {
                snap.entries.firstOrNull { it.media?.idMal == id }?.let { return it.mediaId }
            }
        }
        val key = "$prefix:$id"
        cacheLock.withLock {
            if (animeIdCache.containsKey(key)) return animeIdCache[key]
        }
        val resolved: Long? = when (prefix) {
            "mal" -> try {
                api.mediaByMalId(null, id)?.first?.id
            } catch (error: CancellationException) {
                throw error
            } catch (_: AniListNotFoundException) {
                null
            } catch (_: Throwable) {
                return armIds(prefix, id)
            } ?: armIds(prefix, id)
            else -> armIds(prefix, id)
        }
        cacheLock.withLock { animeIdCache[key] = resolved }
        return resolved
    }

    private suspend fun armIds(prefix: String, id: Long): Long? {
        val source = when (prefix) {
            "mal" -> "myanimelist"
            else -> prefix
        }
        val value = getJsonStrict("${AniListConfig.ARM_BASE_URL}/ids?source=$source&id=$id&include=anilist") as? JsonObject
        return value?.longValue("anilist")
    }

    private suspend fun absoluteEpisode(imdb: String, season: Int, episode: Int): Int? {
        if (season <= 1) return episode
        val videos = try {
            showEpisodes(imdb)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        } ?: return null
        val regular = videos
            .mapNotNull { video ->
                val s = video.season?.takeIf { it > 0 } ?: return@mapNotNull null
                val e = video.episode?.takeIf { it > 0 } ?: return@mapNotNull null
                s to e
            }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
        // A partial/gapped metadata list cannot establish an absolute offset safely.
        for (s in 1..season) {
            val numbers = regular.filter { it.first == s }.map { it.second }
            if (numbers.isEmpty() || numbers != (1..numbers.last()).toList()) return null
        }
        val index = regular.indexOf(season to episode)
        return if (index >= 0) index + 1 else null
    }

    /** ARM's season table of [imdb] (cached): which AniList entries each TVDB season has. */
    internal suspend fun imdbSeasonEntries(imdb: String): List<ArmImdbEntry> {
        val key = imdb.lowercase()
        cacheLock.withLock { imdbSeasonCache[key]?.let { return it } }
        val element = getJsonStrict("${AniListConfig.ARM_BASE_URL}/imdb?id=$imdb")
        val values = when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonObject)?.toArmImdbEntry() }
            is JsonObject -> listOfNotNull(element.toArmImdbEntry())
            else -> emptyList()
        }.distinctBy(ArmImdbEntry::anilist)
        cacheLock.withLock { imdbSeasonCache[key] = values }
        return values
    }

    private fun JsonObject.toArmImdbEntry(): ArmImdbEntry? {
        val anilist = longValue("anilist") ?: return null
        return ArmImdbEntry(
            anilist = anilist,
            tvdbSeason = longValue("thetvdb-season")?.toInt(),
            media = stringValue("media"),
        )
    }

    private suspend fun mediaFor(ids: List<Long>): Map<Long, AniListMedia> {
        val missing = cacheLock.withLock { ids.filterNot(mediaCache::containsKey) }
        if (missing.isNotEmpty()) {
            val loaded = try {
                api.mediaPage(null, missing)
            } catch (error: CancellationException) {
                throw error
            } catch (error: AniListAuthException) {
                throw error
            } catch (error: Throwable) {
                throw AniListTransientException("AniList media lookup failed", error)
            }
            cacheLock.withLock { loaded.forEach { mediaCache[it.id] = it } }
        }
        return cacheLock.withLock { ids.mapNotNull { id -> mediaCache[id]?.let { id to it } }.toMap() }
    }

    /** Like [getJson], but a network failure or server error throws instead of reading as "no data". */
    private suspend fun getJsonStrict(url: String): kotlinx.serialization.json.JsonElement {
        val outcome = try {
            client.prepareGet(url) {
                header("Accept", "application/json")
            }.execute { response ->
                response.status.value to readBoundedResponseBody(response.bodyAsChannel(), response.contentLength())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw AniListTransientException("ARM unreachable", error)
        }
        val (status, body) = outcome
        return when {
            status == 404 || body.isBlank() -> kotlinx.serialization.json.JsonNull
            status == 429 || status >= 500 -> throw AniListTransientException("ARM answered $status")
            status !in 200..299 -> kotlinx.serialization.json.JsonNull
            else -> runCatching { aniListJson.parseToJsonElement(body) }.getOrElse {
                throw AniListTransientException("ARM sent an unreadable answer", it)
            }
        }
    }

    private suspend fun imdbCandidates(imdb: String): List<Long> {
        val key = imdb.lowercase()
        cacheLock.withLock { imdbCache[key]?.let { return it } }
        val element = getJson("${AniListConfig.ARM_BASE_URL}/imdb?id=$imdb&include=anilist") ?: return emptyList()
        val values = when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonObject)?.longValue("anilist") }
            is JsonObject -> listOfNotNull(element.longValue("anilist"))
            else -> emptyList()
        }.distinct()
        cacheLock.withLock { imdbCache[key] = values }
        return values
    }

    private suspend fun getJson(url: String): kotlinx.serialization.json.JsonElement? = try {
        client.prepareGet(url) {
            header("Accept", "application/json")
        }.execute { response ->
            val status = response.status.value
            val body = readBoundedResponseBody(response.bodyAsChannel(), response.contentLength())
            when {
                status == 404 -> kotlinx.serialization.json.JsonNull
                status !in 200..299 -> null
                body.isBlank() -> kotlinx.serialization.json.JsonNull
                else -> runCatching { aniListJson.parseToJsonElement(body) }.getOrNull()
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }

    private fun JsonObject.longValue(key: String): Long? =
        (this[key] as? JsonPrimitive)?.let { primitive -> primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull() }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

    companion object {
        const val MAPPING_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
    }
}
