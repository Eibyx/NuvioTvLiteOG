package com.nuvio.tv.core.cloudstorage

import kotlinx.serialization.Serializable

/**
 * Personal cloud storage a profile can play its own video files from.
 * Ported from NuvioMobileKai (`features/cloudstorage`), adapted to the TV app.
 */
@Serializable
enum class CloudProviderKind(val id: String, val displayName: String) {
    GOOGLE_DRIVE("gdrive", "Google Drive"),
    TERABOX("terabox", "TeraBox");

    companion object {
        fun fromId(id: String?): CloudProviderKind? = entries.firstOrNull { it.id == id }
    }
}

/** A folder the user chose to index. [id] is the provider's own id (Drive file id, TeraBox path). */
@Serializable
data class CloudFolderRef(
    val id: String,
    val path: String
)

/** Settings of one connected account. Holds no secrets: those live in [CloudCredentials]. */
@Serializable
data class CloudAccount(
    val id: String,
    val kind: CloudProviderKind,
    val name: String,
    val folders: List<CloudFolderRef> = emptyList(),
    val enabled: Boolean = true,
    val showFirst: Boolean = false,
    val lastSyncEpochMs: Long? = null,
    val indexedFiles: Int = 0,
    val lastError: String? = null,
    val manualLinks: List<CloudManualLink> = emptyList(),
    /** True when the last sync stopped at the device's file or folder limit. */
    val truncated: Boolean = false,
    /** Simultaneous connections when playing: null is automatic, 1 is a single stream (as before 0.6.2). */
    val connections: Int? = null
)

/**
 * The user's own association of a file to a title, for names the matcher cannot read with
 * confidence. Takes precedence over automatic matches.
 */
@Serializable
data class CloudManualLink(
    val fileId: String,
    val contentId: String,
    val season: Int? = null,
    val episode: Int? = null
)

/** A video file as listed by a provider. Never downloaded to be indexed. */
@Serializable
data class CloudFile(
    val id: String,
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedEpochSec: Long,
    val parentPath: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val mimeType: String? = null
)

/** A folder or file in a provider listing, for the folder picker. */
data class CloudEntry(
    val id: String,
    val path: String,
    val name: String,
    val isFolder: Boolean,
    val sizeBytes: Long = 0
)

/** Where a player reads a cloud file from, with the request headers it needs. */
class CloudPlaybackSource(
    val url: String,
    val headers: Map<String, String>,
    val sizeBytes: Long?
) {
    override fun toString(): String = "CloudPlaybackSource(headers=${headers.keys})"
}

/**
 * Secrets of an account. Stored only in the encrypted cloud store (Android Keystore, this device,
 * this profile), never synced, exported, logged or handed to plugins: [toString] redacts every value.
 */
@Serializable
data class CloudCredentials(
    val teraboxCookie: String? = null,
    val teraboxDomain: String? = null,
    val teraboxJsToken: String? = null,
    val teraboxBaseUrl: String? = null,
    val driveClientId: String? = null,
    val driveClientSecret: String? = null,
    val driveRefreshToken: String? = null,
    val driveAccessToken: String? = null,
    val driveAccessTokenExpiryEpochMs: Long? = null
) {
    override fun toString(): String = "CloudCredentials(<redacted>)"
}

class CloudStorageException(
    val failure: CloudFailure,
    cause: Throwable? = null,
    /** A short, secret-free reason for the setup page (`drive_api_disabled`, `invalid_client`…). */
    val detail: String? = null
) : Exception(failure.name, cause)

enum class CloudFailure {
    /** The session or token was rejected: the user has to sign in again. */
    AUTH,
    NOT_FOUND,
    RATE_LIMITED,
    NETWORK,
    UNSUPPORTED,
    FAILED
}

internal fun Throwable.cloudFailure(): CloudFailure = (this as? CloudStorageException)?.failure ?: CloudFailure.FAILED

internal val CLOUD_VIDEO_EXTENSIONS = setOf(
    "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mts", "wmv", "flv", "mpg", "mpeg", "ogv"
)

internal fun String.isCloudVideoName(): Boolean =
    substringAfterLast('.', "").lowercase() in CLOUD_VIDEO_EXTENSIONS

/** Subtitle files fansub releases ship next to the video (`Show - 02.ass`, `Show - 02.spa.srt`). */
internal val CLOUD_SUBTITLE_EXTENSIONS = setOf("ass", "ssa", "srt", "vtt")

internal fun String.isCloudSubtitleName(): Boolean =
    substringAfterLast('.', "").lowercase() in CLOUD_SUBTITLE_EXTENSIONS

internal fun cloudSubtitleMimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "ass", "ssa" -> "text/x-ssa"
    "vtt" -> "text/vtt"
    else -> "application/x-subrip"
}

internal fun cloudMimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "mov" -> "video/quicktime"
    "ts", "m2ts", "mts" -> "video/mp2t"
    else -> "application/octet-stream"
}

/**
 * How much of an account is indexed. Kai (phones) indexes up to 30 000 files in 5 000 folders;
 * TV boxes with little RAM get smaller limits so the index, parsed when streams are searched,
 * stays a few megabytes.
 */
data class CloudIndexLimits(val maxFiles: Int, val maxFolders: Int, val maxDepth: Int = 12) {
    companion object {
        val STANDARD = CloudIndexLimits(maxFiles = 30_000, maxFolders = 5_000)
        val CONSTRAINED = CloudIndexLimits(maxFiles = 15_000, maxFolders = 2_500)
        val LOW_RAM = CloudIndexLimits(maxFiles = 8_000, maxFolders = 1_500)

        fun forDevice(isLowRam: Boolean, isConstrained: Boolean): CloudIndexLimits = when {
            isLowRam -> LOW_RAM
            isConstrained -> CONSTRAINED
            else -> STANDARD
        }
    }
}

internal const val CLOUD_MAX_SUBTITLE_BYTES = 20L * 1024 * 1024
