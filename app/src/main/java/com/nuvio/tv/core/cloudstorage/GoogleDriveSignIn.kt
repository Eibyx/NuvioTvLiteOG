package com.nuvio.tv.core.cloudstorage

import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Google sign-in for the TV, finished from the phone (adapted from NuvioMobileKai's
 * `GoogleDriveOAuth`, which receives the redirect on the phone itself).
 *
 * The user's own OAuth client of type "Desktop app" accepts a loopback redirect without any setup.
 * The phone opens Google's consent page; Google then sends the phone to `http://127.0.0.1:53682/…`,
 * which does not load there, and the user pastes that address back into the setup page. The TV
 * checks the state and exchanges the code with the PKCE verifier, which never leaves the TV.
 * Only the read-only Drive scope is asked for.
 */
internal class GoogleDriveSignIn(
    private val http: CloudHttp,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    class Pending internal constructor(
        val clientId: String,
        val clientSecret: String?,
        val state: String,
        val verifier: String,
        val createdAtMs: Long
    ) {
        override fun toString(): String = "GoogleDriveSignIn.Pending(<redacted>)"
    }

    private val lock = Any()
    private val pending = LinkedHashMap<String, Pending>()

    /** The consent page for the phone. */
    fun start(clientId: String, clientSecret: String?): String {
        val id = clientId.trim()
        if (!CLIENT_ID.matches(id)) throw CloudStorageException(CloudFailure.AUTH, detail = "bad_client_id")
        val verifier = randomToken(48)
        val state = randomToken(24)
        synchronized(lock) {
            pending.entries.removeIf { nowMs() - it.value.createdAtMs > MAX_AGE_MS }
            while (pending.size >= MAX_PENDING) pending.remove(pending.keys.first())
            pending[state] = Pending(id, clientSecret?.trim()?.takeIf { it.isNotEmpty() }, state, verifier, nowMs())
        }
        return cloudUrl(
            GoogleDriveApi.AUTH_URL,
            query = listOf(
                "client_id" to id,
                "redirect_uri" to REDIRECT_URI,
                "response_type" to "code",
                "scope" to GoogleDriveApi.SCOPE,
                "code_challenge" to challenge(verifier),
                "code_challenge_method" to "S256",
                "state" to state,
                "access_type" to "offline",
                "prompt" to "consent"
            )
        )
    }

    class Result(val clientId: String, val clientSecret: String?, val refreshToken: String) {
        override fun toString(): String = "GoogleDriveSignIn.Result(<redacted>)"
    }

    /** [pasted] is the address Google sent the phone to (or just its `code`). */
    suspend fun finish(pasted: String): Result {
        val params = redirectParams(pasted)
        params["error"]?.let { throw CloudStorageException(CloudFailure.AUTH, detail = if (it == "access_denied") "access_denied" else "google_error") }
        val code = params["code"]?.takeIf { it.isNotBlank() }
            ?: throw CloudStorageException(CloudFailure.AUTH, detail = "no_code")
        val state = params["state"]
        val request = synchronized(lock) {
            pending.entries.removeIf { nowMs() - it.value.createdAtMs > MAX_AGE_MS }
            when {
                state != null -> pending.remove(state)
                // Only the code was pasted: fine when a single sign-in is waiting.
                pending.size == 1 -> pending.remove(pending.keys.first())
                else -> null
            }
        } ?: throw CloudStorageException(CloudFailure.AUTH, detail = "state_mismatch")
        val form = buildMap {
            put("code", code)
            put("client_id", request.clientId)
            request.clientSecret?.let { put("client_secret", it) }
            put("code_verifier", request.verifier)
            put("redirect_uri", REDIRECT_URI)
            put("grant_type", "authorization_code")
        }
        val response = try {
            http.send(CloudHttpRequest("POST", GoogleDriveApi.TOKEN_URL, form = form))
        } catch (error: CloudStorageException) {
            throw error
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw CloudStorageException(CloudFailure.NETWORK, error)
        }
        if (!response.isSuccess) {
            throw CloudStorageException(CloudFailure.AUTH, detail = googleTokenError(response.body) ?: "token_failed")
        }
        val refreshToken = (runCatching { Json.parseToJsonElement(response.body) }.getOrNull() as? JsonObject)
            ?.get("refresh_token")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }
            ?: throw CloudStorageException(CloudFailure.AUTH, detail = "no_refresh_token")
        return Result(request.clientId, request.clientSecret, refreshToken)
    }

    companion object {
        /** rclone's loopback address: allowed for "Desktop app" clients without registering it. */
        const val REDIRECT_URI = "http://127.0.0.1:53682/"
        private const val MAX_PENDING = 3
        private const val MAX_AGE_MS = 15 * 60 * 1000L
        private val CLIENT_ID = Regex("""^[0-9]{4,30}-[a-z0-9]{8,64}\.apps\.googleusercontent\.com$""")
        private val random = SecureRandom()

        // kotlin.io Base64 instead of java.util.Base64, which needs API 26 (minSdk is 24).
        @OptIn(ExperimentalEncodingApi::class)
        private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        @OptIn(ExperimentalEncodingApi::class)
        private fun randomToken(bytes: Int): String = urlSafe.encode(ByteArray(bytes).also(random::nextBytes))

        @OptIn(ExperimentalEncodingApi::class)
        internal fun challenge(verifier: String): String =
            urlSafe.encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        /** `code`, `state` and `error` from a pasted address, query string or bare code. */
        internal fun redirectParams(pasted: String): Map<String, String> {
            val text = pasted.trim()
            if (text.isEmpty()) return emptyMap()
            if ('=' !in text && !text.contains(' ')) return mapOf("code" to text)
            val query = text.substringAfter('?', text).substringBefore('#')
            return query.split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                key to runCatching { URLDecoder.decode(pair.substringAfter('='), "UTF-8") }.getOrDefault("")
            }.toMap()
        }
    }
}
