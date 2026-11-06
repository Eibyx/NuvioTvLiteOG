package com.nuvio.tv.core.cloudstorage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Google Drive through its official v3 REST API, read only (`drive.readonly` is enough).
 * The OAuth client is the user's own (client id and secret from their Google Cloud project), the
 * same one an rclone `drive` remote uses, so an rclone.conf from Round Sync can be imported.
 */
internal class GoogleDriveApi(
    private val http: CloudHttp,
    private val clientId: String,
    private val clientSecret: String?,
    private val refreshToken: String,
    accessToken: String? = null,
    accessTokenExpiryEpochMs: Long? = null,
    private val onTokenRefreshed: (accessToken: String, expiryEpochMs: Long) -> Unit = { _, _ -> },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var accessToken: String? = accessToken
    private var expiryEpochMs: Long = accessTokenExpiryEpochMs ?: 0L

    /** A token valid for at least another minute. */
    suspend fun accessToken(forceRefresh: Boolean = false): String {
        val current = accessToken
        if (!forceRefresh && current != null && nowMs() < expiryEpochMs - 60_000L) return current
        val form = buildMap {
            put("client_id", clientId)
            clientSecret?.takeIf { it.isNotBlank() }?.let { put("client_secret", it) }
            put("refresh_token", refreshToken)
            put("grant_type", "refresh_token")
        }
        val response = send(CloudHttpRequest("POST", TOKEN_URL, form = form))
        if (!response.isSuccess) {
            throw CloudStorageException(
                if (response.status in 400..401) CloudFailure.AUTH else CloudFailure.FAILED,
                detail = googleTokenError(response.body)
            )
        }
        val json = parse(response.body)
        val token = json.string("access_token") ?: throw CloudStorageException(CloudFailure.AUTH)
        val expiresIn = json.long("expires_in") ?: 3_600L
        accessToken = token
        expiryEpochMs = nowMs() + expiresIn * 1_000L
        onTokenRefreshed(token, expiryEpochMs)
        return token
    }

    suspend fun accountName(): String? {
        val json = get(cloudUrl(API, "/about", listOf("fields" to "user(emailAddress,displayName)")))
        val user = json["user"] as? JsonObject
        return user?.string("emailAddress") ?: user?.string("displayName")
    }

    suspend fun file(id: String): DriveItem =
        get(cloudUrl(API, "/files/$id", listOf("fields" to FILE_FIELDS, "supportsAllDrives" to "true")))
            .toDriveItem() ?: throw CloudStorageException(CloudFailure.NOT_FOUND)

    /** Every child of a folder (`root` for My Drive), all pages. */
    suspend fun children(folderId: String): List<DriveItem> {
        val items = mutableListOf<DriveItem>()
        var pageToken: String? = null
        do {
            val json = get(
                cloudUrl(
                    API,
                    "/files",
                    buildList {
                        add("q" to "'${folderId.driveQueryEscaped()}' in parents and trashed = false")
                        add("fields" to "nextPageToken,files($FILE_FIELDS)")
                        add("pageSize" to "1000")
                        add("supportsAllDrives" to "true")
                        add("includeItemsFromAllDrives" to "true")
                        pageToken?.let { add("pageToken" to it) }
                    },
                ),
            )
            (json["files"] as? JsonArray)?.forEach { element ->
                (element as? JsonObject)?.toDriveItem()?.let(items::add)
            }
            pageToken = json.string("nextPageToken")
        } while (pageToken != null && items.size < MAX_ITEMS)
        return items
    }

    suspend fun startPageToken(): String =
        get(cloudUrl(API, "/changes/startPageToken", listOf("supportsAllDrives" to "true")))
            .string("startPageToken") ?: throw CloudStorageException(CloudFailure.FAILED)

    /** Changes since [pageToken] and the token to continue from next time. */
    suspend fun changes(pageToken: String): DriveChanges {
        val changes = mutableListOf<DriveChange>()
        var token: String = pageToken
        while (true) {
            val json = get(
                cloudUrl(
                    API,
                    "/changes",
                    listOf(
                        "pageToken" to token,
                        "fields" to "nextPageToken,newStartPageToken,changes(fileId,removed,file($FILE_FIELDS,trashed))",
                        "pageSize" to "1000",
                        "supportsAllDrives" to "true",
                        "includeItemsFromAllDrives" to "true",
                    ),
                ),
            )
            (json["changes"] as? JsonArray)?.forEach { element ->
                val change = element as? JsonObject ?: return@forEach
                val fileId = change.string("fileId") ?: return@forEach
                val file = change["file"] as? JsonObject
                val removed = change.boolean("removed") == true || file?.boolean("trashed") == true
                changes += DriveChange(fileId, removed, file?.toDriveItem())
            }
            json.string("newStartPageToken")?.let { return DriveChanges(changes, it) }
            token = json.string("nextPageToken") ?: throw CloudStorageException(CloudFailure.FAILED)
        }
    }

    /** Revokes the refresh token at Google. Best effort: the local copy is deleted either way. */
    suspend fun revoke() {
        runCatching { send(CloudHttpRequest("POST", REVOKE_URL, form = mapOf("token" to refreshToken))) }
    }

    fun playbackUrl(fileId: String): String =
        cloudUrl(API, "/files/$fileId", listOf("alt" to "media", "supportsAllDrives" to "true"))

    private suspend fun get(url: String): JsonObject {
        var response = send(CloudHttpRequest("GET", url, mapOf("Authorization" to "Bearer ${accessToken()}")))
        if (response.status == 401) {
            response = send(CloudHttpRequest("GET", url, mapOf("Authorization" to "Bearer ${accessToken(forceRefresh = true)}")))
        }
        if (!response.isSuccess) {
            val body = response.body
            val detail = when {
                response.status == 403 && ("accessNotConfigured" in body || "SERVICE_DISABLED" in body) -> "drive_api_disabled"
                response.status == 403 && ("insufficientPermissions" in body || "SCOPE_INSUFFICIENT" in body) -> "insufficient_scope"
                else -> null
            }
            throw CloudStorageException(
                failure = if (detail == "drive_api_disabled") CloudFailure.FAILED else when {
                    response.status == 401 -> CloudFailure.AUTH
                    response.status == 404 -> CloudFailure.NOT_FOUND
                    response.status == 429 || (response.status == 403 && "ateLimit" in response.body) -> CloudFailure.RATE_LIMITED
                    response.status == 403 -> CloudFailure.AUTH
                    else -> CloudFailure.FAILED
                },
                detail = detail,
            )
        }
        return parse(response.body)
    }

    private suspend fun send(request: CloudHttpRequest): CloudHttpResponse = try {
        http.send(request)
    } catch (error: CloudStorageException) {
        throw error
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        throw CloudStorageException(CloudFailure.NETWORK, error)
    }

    companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val FILE_FIELDS =
            "id,name,mimeType,size,modifiedTime,parents,videoMediaMetadata(width,height,durationMillis)"
        private const val MAX_ITEMS = 50_000
    }
}

internal data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedEpochSec: Long,
    val parents: List<String>,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
) {
    val isFolder: Boolean get() = mimeType == GoogleDriveApi.FOLDER_MIME
}

internal data class DriveChange(val fileId: String, val removed: Boolean, val file: DriveItem?)

internal data class DriveChanges(val changes: List<DriveChange>, val newStartPageToken: String)

/** The OAuth error code Google returns from its token endpoint (`invalid_client`, `invalid_grant`…). */
internal fun googleTokenError(body: String): String? =
    runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonPrimitive }
        .getOrNull()?.contentOrNull
        ?.takeIf { it.matches(Regex("^[a-z_]{3,40}$")) }

private fun parse(body: String): JsonObject =
    runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: throw CloudStorageException(CloudFailure.FAILED)

private fun JsonObject.toDriveItem(): DriveItem? {
    val id = string("id") ?: return null
    val video = this["videoMediaMetadata"] as? JsonObject
    return DriveItem(
        id = id,
        name = string("name") ?: return null,
        mimeType = string("mimeType").orEmpty(),
        sizeBytes = long("size") ?: 0L,
        modifiedEpochSec = string("modifiedTime")
            ?.let { runCatching { java.time.Instant.parse(it).epochSecond }.getOrNull() } ?: 0L,
        parents = (this["parents"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
        width = video?.long("width")?.toInt(),
        height = video?.long("height")?.toInt(),
        durationMs = video?.long("durationMillis"),
    )
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun String.driveQueryEscaped(): String = replace("\\", "\\\\").replace("'", "\\'")
