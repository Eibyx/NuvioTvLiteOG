package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.cloudstorage.CloudStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import javax.inject.Inject
import javax.inject.Singleton

class ServerStreamSource(
    val name: String,
    val preferred: Boolean,
    /** Counts within the add-on query limit (3 on low-RAM boxes, 8 otherwise). */
    val limited: Boolean = false,
    private val loader: suspend () -> List<Stream>
) {
    suspend fun load(): List<Stream> = loader()
}

@Singleton
class ServerStreams @Inject constructor(
    private val repository: ServerRepository,
    private val matcher: ServerMatcher,
    private val cloudStreams: CloudStreams? = null
) {
    val revision: Int
        get() = repository.uiState.value.revision * 31 + (cloudStreams?.revision ?: 0)

    fun isNativeRequest(videoId: String): Boolean = ServerItemRef.isServerId(videoId)

    fun canServe(type: String, videoId: String): Boolean {
        ServerItemRef.parse(videoId)?.let { ref -> return repository.connection(ref.connectionId)?.enabled == true }
        val connections = repository.enabledConnections().ifEmpty { return false }
        val request = matcher.request(type, videoId, season = null, episode = null) ?: return false
        return connections.any { matcher.supports(it, request.kind) }
    }

    fun sources(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean = false
    ): List<ServerStreamSource> {
        ServerItemRef.parse(videoId)?.let { ref ->
            val connection = repository.connection(ref.connectionId) ?: return emptyList()
            return listOf(source(connection, preferred = true) { candidates(ref) })
        }
        // Cloud storage accounts (Google Drive, TeraBox): empty when none is connected.
        val cloud = cloudStreams?.sources(type, videoId, season, episode).orEmpty()
        val connections = repository.enabledConnections().ifEmpty { return cloud }
        val request = matcher.request(type, videoId, season, episode) ?: return cloud
        return connections
            .filter { matcher.supports(it, request.kind) }
            .map { connection ->
                source(connection, preferred = connection.useCatalogMetadata) {
                    matcher.match(connection, request, forceRefresh).flatMap { candidates(it) }
                }
            } + cloud
    }

    fun preferredSourceNames(type: String, videoId: String): Set<String> =
        sources(type, videoId, season = null, episode = null)
            .filter { it.preferred }
            .mapTo(mutableSetOf()) { it.name }

    private fun source(
        connection: ServerConnection,
        preferred: Boolean,
        loader: suspend () -> List<Stream>
    ): ServerStreamSource = ServerStreamSource(repository.sourceLabel(connection), preferred, loader = loader)

    suspend fun candidates(ref: ServerItemRef): List<Stream> {
        val connection = repository.connection(ref.connectionId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val label = repository.sourceLabel(connection)
        return repository.call(ref.connectionId) { provider, session -> provider.candidates(session, ref.itemId) }
            .map { candidate ->
                Stream(
                    name = candidate.title,
                    title = null,
                    description = candidate.description,
                    url = null,
                    ytId = null,
                    infoHash = null,
                    fileIdx = null,
                    externalUrl = null,
                    behaviorHints = StreamBehaviorHints(
                        notWebReady = null,
                        bingeGroup = null,
                        countryWhitelist = null,
                        proxyHeaders = null,
                        videoSize = candidate.sizeBytes,
                        filename = candidate.filename
                    ),
                    addonName = label,
                    addonLogo = null,
                    serverTarget = candidate.target
                )
            }
    }
}
