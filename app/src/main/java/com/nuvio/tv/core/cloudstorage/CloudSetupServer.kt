package com.nuvio.tv.core.cloudstorage

import fi.iki.elonen.NanoHTTPD
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the phone page may do: add accounts, or link files of one account to titles. */
enum class CloudSetupMode { ACCOUNTS, LINK }

data class CloudSetupResult(val status: Int, val body: String)

@Serializable
internal data class CloudSetupRequest(
    val code: String = "",
    val name: String = "",
    val cookie: String = "",
    val domain: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val refreshToken: String = "",
    val config: String = "",
    val query: String = "",
    val fileId: String = "",
    val contentId: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    /** The address Google sent the phone to after consent (`http://127.0.0.1:53682/?code=…`). */
    val redirect: String = ""
) {
    override fun toString(): String = "CloudSetupRequest(<redacted>)"
}

@Serializable
internal data class CloudSetupReply(
    val ok: Boolean,
    val message: String = "",
    val done: Boolean = false,
    val files: List<CloudSetupFile> = emptyList(),
    /** Google's consent page, for the phone to open. Holds no secret. */
    val url: String? = null
)

@Serializable
internal data class CloudSetupFile(val id: String, val name: String, val folder: String)

/**
 * The phone side of cloud setup, independent of the HTTP transport so it can be tested.
 *
 * - Every request must carry the one-time code shown on the TV; after [MAX_ATTEMPTS] wrong codes
 *   the session ends ([onLocked]).
 * - Answers never contain a cookie, token, secret or anything else stored: only "added" or the
 *   kind of error, and in link mode the names of matching files.
 */
class CloudSetupHandler internal constructor(
    val mode: CloudSetupMode,
    val code: String,
    private val repository: CloudStorageRepository,
    private val linkAccountId: String?,
    private val onAccountsChanged: (message: String) -> Unit,
    private val onLocked: () -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var failedAttempts = 0
    private val lock = Any()

    @Volatile
    var finished = false
        private set

    fun page(): CloudSetupResult = CloudSetupResult(200, CloudSetupPage.html(mode))

    suspend fun post(path: String, body: String): CloudSetupResult {
        if (finished) return reply(410, CloudSetupReply(false, "closed"))
        if (body.length > MAX_BODY_CHARS) return reply(413, CloudSetupReply(false, "too_large"))
        val request = runCatching { json.decodeFromString(CloudSetupRequest.serializer(), body) }.getOrNull()
            ?: return reply(400, CloudSetupReply(false, "bad_request"))
        if (!checkCode(request.code)) return reply(403, CloudSetupReply(false, "wrong_code"))
        return try {
            when (mode to path) {
                CloudSetupMode.ACCOUNTS to "/api/terabox" -> {
                    val account = repository.addTeraBox(request.name, request.cookie, request.domain.ifBlank { null })
                    finish(account.name)
                }
                CloudSetupMode.ACCOUNTS to "/api/drive/start" -> {
                    if (request.clientId.isBlank()) return reply(422, CloudSetupReply(false, "missing_client_id"))
                    val url = repository.driveSignIn.start(request.clientId, request.clientSecret.ifBlank { null })
                    reply(200, CloudSetupReply(true, "signin_started", url = url))
                }
                CloudSetupMode.ACCOUNTS to "/api/drive/finish" -> {
                    if (request.redirect.isBlank()) return reply(422, CloudSetupReply(false, "no_code"))
                    val result = repository.driveSignIn.finish(request.redirect)
                    val account = repository.addGoogleDrive(request.name, result.clientId, result.clientSecret, result.refreshToken)
                    finish(account.name)
                }
                CloudSetupMode.ACCOUNTS to "/api/drive" -> {
                    if (request.clientId.isBlank()) return reply(422, CloudSetupReply(false, "missing_client_id"))
                    if (request.refreshToken.isBlank()) return reply(422, CloudSetupReply(false, "missing_refresh_token"))
                    val account = repository.addGoogleDrive(
                        request.name,
                        request.clientId,
                        request.clientSecret.ifBlank { null },
                        request.refreshToken
                    )
                    finish(account.name)
                }
                CloudSetupMode.ACCOUNTS to "/api/rclone" -> {
                    val result = repository.importRclone(request.config)
                    if (result.added.isEmpty()) {
                        reply(422, CloudSetupReply(false, "nothing_added"))
                    } else {
                        finish(result.added.joinToString(", ") { it.name })
                    }
                }
                CloudSetupMode.LINK to "/api/files" -> reply(200, CloudSetupReply(true, files = searchFiles(request.query)))
                CloudSetupMode.LINK to "/api/link" -> link(request)
                else -> reply(404, CloudSetupReply(false, "not_found"))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RcloneConfigException) {
            reply(422, CloudSetupReply(false, "rclone_${error.message}"))
        } catch (error: CloudStorageException) {
            reply(422, CloudSetupReply(false, error.detail ?: error.failure.name.lowercase()))
        } catch (error: Exception) {
            reply(422, CloudSetupReply(false, error.cloudFailure().name.lowercase()))
        }
    }

    private fun checkCode(candidate: String): Boolean = synchronized(lock) {
        if (finished) return false
        if (constantTimeEquals(candidate.trim(), code)) return true
        failedAttempts++
        if (failedAttempts >= MAX_ATTEMPTS) {
            finished = true
            onLocked()
        }
        false
    }

    private fun finish(message: String): CloudSetupResult {
        finished = true
        onAccountsChanged(message)
        return reply(200, CloudSetupReply(true, "added", done = true))
    }

    private fun searchFiles(query: String): List<CloudSetupFile> {
        val accountId = linkAccountId ?: return emptyList()
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val files = repository.index(accountId)?.files.orEmpty()
        return files.asSequence()
            .filter { file -> val name = file.path.lowercase(); words.all { it in name } }
            .take(MAX_FILES)
            .map { CloudSetupFile(it.id, it.name, it.parentPath.substringAfterLast('/')) }
            .toList()
    }

    private fun link(request: CloudSetupRequest): CloudSetupResult {
        val accountId = linkAccountId ?: return reply(404, CloudSetupReply(false, "not_found"))
        val contentId = request.contentId.trim()
        if (!CONTENT_ID.matches(contentId)) return reply(422, CloudSetupReply(false, "bad_content_id"))
        val season = request.season?.takeIf { it in 0..999 }
        val episode = request.episode?.takeIf { it in 0..99_999 }
        if ((season == null) != (episode == null)) return reply(422, CloudSetupReply(false, "bad_episode"))
        val file = repository.index(accountId)?.files?.firstOrNull { it.id == request.fileId }
            ?: return reply(404, CloudSetupReply(false, "not_found"))
        repository.setManualLink(accountId, CloudManualLink(file.id, contentId, season, episode))
        onAccountsChanged(file.name)
        return reply(200, CloudSetupReply(true, "linked"))
    }

    private fun reply(status: Int, reply: CloudSetupReply) =
        CloudSetupResult(status, json.encodeToString(CloudSetupReply.serializer(), reply))

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val MAX_BODY_CHARS = 256 * 1024
        private const val MAX_FILES = 40
        private val CONTENT_ID = Regex("""^(tt\d{3,12}|(tmdb|tvdb|anilist|kitsu|mal|anidb|trakt|simkl):\d{1,12})$""")
        private val random = SecureRandom()

        /** Six digits, read off the TV. */
        fun newCode(): String = (random.nextInt(900_000) + 100_000).toString()

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}

/**
 * The LAN page the phone opens from the QR code. It runs only while the TV shows the setup
 * screen and stops as soon as an account is added (or after five wrong codes, or ten minutes).
 */
class CloudSetupServer internal constructor(
    private val handler: CloudSetupHandler,
    port: Int
) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        val result = when {
            session.method == Method.GET && session.uri == "/" -> handler.page()
            session.method == Method.POST && session.uri.startsWith("/api/") -> {
                val body = readBody(session) ?: return text(413, "{\"ok\":false,\"message\":\"too_large\"}")
                runBlocking {
                    withTimeoutOrNull(REQUEST_TIMEOUT_MS) { handler.post(session.uri, body) }
                } ?: CloudSetupResult(504, "{\"ok\":false,\"message\":\"network\"}")
            }
            else -> CloudSetupResult(404, "{\"ok\":false}")
        }
        val type = if (result.body.startsWith("<")) "text/html; charset=utf-8" else "application/json; charset=utf-8"
        return newFixedLengthResponse(status(result.status), type, result.body).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Referrer-Policy", "no-referrer")
        }
    }

    private fun text(code: Int, body: String): Response =
        newFixedLengthResponse(status(code), "application/json; charset=utf-8", body)

    private fun readBody(session: IHTTPSession): String? {
        val length = session.headers["content-length"]?.toIntOrNull() ?: return ""
        if (length > MAX_BODY_BYTES) return null
        if (length <= 0) return ""
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = session.inputStream.read(buffer, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return String(buffer, 0, offset, StandardCharsets.UTF_8)
    }

    private fun status(code: Int): Response.IStatus = Response.Status.lookup(code) ?: Response.Status.INTERNAL_ERROR

    companion object {
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val REQUEST_TIMEOUT_MS = 60_000L

        fun startOnAvailablePort(handler: CloudSetupHandler, startPort: Int = 8110, maxAttempts: Int = 10): CloudSetupServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    return CloudSetupServer(handler, port).also { it.start(SOCKET_READ_TIMEOUT, false) }
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}
