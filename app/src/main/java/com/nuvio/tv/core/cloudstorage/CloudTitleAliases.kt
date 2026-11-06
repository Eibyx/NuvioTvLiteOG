package com.nuvio.tv.core.cloudstorage

import android.util.Log
import com.nuvio.tv.core.tracking.TrackingExternalIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The names a title may carry in file names: Nuvio's title, TMDB's original title, and AniList's
 * romaji, English, native and synonym titles, each AniList entry tied to its TVDB season when ARM
 * says so (ported from NuvioMobileKai). Only public endpoints, no account: nothing about the
 * user's files is sent, only the catalogue id of the title being opened.
 */
internal class CloudTitleAliases(
    private val http: CloudHttp,
    private val timeoutMs: Long = ANILIST_TIMEOUT_MS
) {
    private val lock = Any()
    private val cache = LinkedHashMap<String, List<CloudTitleAlias>>()

    /** Asks ARM and AniList for every title, as Kai does: a non-anime title just finds nothing. */
    suspend fun aliases(cacheKey: String, names: List<String>, ids: TrackingExternalIds): List<CloudTitleAlias> {
        synchronized(lock) { cache[cacheKey] }?.let { return it }
        // Null when AniList did not answer: then the result is not cached, the next search retries.
        val aniList = withTimeoutOrNull(timeoutMs) { aniListAliases(ids) }
        val result = buildList {
            names.filter { it.isNotBlank() }.forEach { add(CloudTitleAlias(it)) }
            addAll(aniList.orEmpty())
        }.distinctBy { it.title.lowercase() to it.seasonHint }
        if (aniList != null && names.isNotEmpty()) {
            synchronized(lock) {
                cache[cacheKey] = result
                while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
            }
        }
        return result
    }

    private suspend fun aniListAliases(ids: TrackingExternalIds): List<CloudTitleAlias>? = try {
        val entries = ids.anilist?.let { listOf(it to null) } ?: armEntries(ids)
        entries.take(MAX_ANILIST_ENTRIES).flatMap { (id, season) -> aniListTitles(id).map { CloudTitleAlias(it, season) } }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.d(TAG, "AniList titles unavailable: ${error::class.java.simpleName}")
        null
    }

    /**
     * AniList entries for the title from ARM. Anime ids use `/ids?source=…`; IMDb, TVDB (what
     * AIOmetadata uses) and TMDB have endpoints of their own (`/imdb`, `/thetvdb`, `/themoviedb`):
     * `/ids` refuses them with a 400. Each entry carries its season in the numbering of the id it
     * was found by, so the season hint matches the catalogue's seasons.
     */
    private suspend fun armEntries(ids: TrackingExternalIds): List<Pair<Long, Int?>> {
        val lookups = buildList {
            ids.mal?.let { add(Triple("/ids", listOf("source" to "myanimelist", "id" to it.toString()), "thetvdb-season")) }
            ids.kitsu?.let { add(Triple("/ids", listOf("source" to "kitsu", "id" to it.toString()), "thetvdb-season")) }
            ids.anidb?.let { add(Triple("/ids", listOf("source" to "anidb", "id" to it.toString()), "thetvdb-season")) }
            ids.imdb?.takeIf { it.startsWith("tt") }?.let { add(Triple("/imdb", listOf("id" to it), "thetvdb-season")) }
            ids.tvdb?.takeIf { it.all(Char::isDigit) }?.let { add(Triple("/thetvdb", listOf("id" to it), "thetvdb-season")) }
            ids.tmdb?.let { add(Triple("/themoviedb", listOf("id" to it.toString()), "themoviedb-season")) }
        }
        for ((path, query, seasonKey) in lookups) {
            val entries = armLookup(path, query, seasonKey)
            if (entries.isNotEmpty()) return entries
        }
        return emptyList()
    }

    private suspend fun armLookup(path: String, query: List<Pair<String, String>>, seasonKey: String): List<Pair<Long, Int?>> {
        val response = http.send(CloudHttpRequest("GET", cloudUrl(ARM_BASE_URL, path, query)))
        if (response.status == 404 || response.status == 400) return emptyList()
        if (!response.isSuccess) throw CloudStorageException(CloudFailure.NETWORK)
        val objects = when (val element = Json.parseToJsonElement(response.body)) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
        return objects.mapNotNull { entry ->
            val aniList = entry.primitive("anilist")?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: return@mapNotNull null
            aniList to entry.primitive(seasonKey)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
        }.distinctBy { it.first }
    }

    private suspend fun aniListTitles(id: Long): List<String> {
        val query = buildJsonObject {
            put("query", "query(\$id:Int){Media(id:\$id,type:ANIME){title{romaji english native} synonyms}}")
            putJsonObject("variables") { put("id", id) }
        }
        val response = http.send(
            CloudHttpRequest(
                "POST",
                GRAPHQL_URL,
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                jsonBody = query.toString()
            )
        )
        if (response.status == 404) return emptyList()
        if (!response.isSuccess) throw CloudStorageException(CloudFailure.NETWORK)
        val media = (Json.parseToJsonElement(response.body) as? JsonObject)
            ?.obj("data")?.obj("Media") ?: return emptyList()
        val title = media.obj("title")
        val names = listOfNotNull(
            title?.primitive("romaji")?.contentOrNull,
            title?.primitive("english")?.contentOrNull,
            title?.primitive("native")?.contentOrNull
        ) + (media["synonyms"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return names.filter { it.isNotBlank() }.distinct().take(12)
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonElement) as? JsonPrimitive

    private companion object {
        const val TAG = "CloudTitleAliases"
        const val ARM_BASE_URL = "https://arm.haglund.dev/api/v2"
        const val GRAPHQL_URL = "https://graphql.anilist.co"
        const val CACHE_SIZE = 64
        const val MAX_ANILIST_ENTRIES = 4
        const val ANILIST_TIMEOUT_MS = 6_000L
    }
}
