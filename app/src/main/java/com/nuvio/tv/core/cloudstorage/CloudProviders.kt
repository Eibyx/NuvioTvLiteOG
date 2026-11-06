package com.nuvio.tv.core.cloudstorage

import kotlinx.serialization.Serializable

/** The index of one account: the video files under its selected folders. */
@Serializable
internal data class CloudIndexSnapshot(
    val files: List<CloudFile> = emptyList(),
    /** Subtitle files in the same folders, attached to the videos they belong to. */
    val subtitles: List<CloudFile> = emptyList(),
    val folderIds: List<String> = emptyList(),
    /** Drive: the changes page token to continue from. */
    val cursor: String? = null,
    /** Drive: id → path of every indexed folder, to place changed files. */
    val folderPaths: Map<String, String> = emptyMap(),
    val builtAtEpochMs: Long = 0L,
    /** The walk stopped at the device's file or folder limit: part of the library is missing. */
    val truncated: Boolean = false,
)

internal interface CloudProvider {
    /** Checks the session and returns a label for the account (e-mail, or null). */
    suspend fun verify(): String?

    /** A folder's subfolders and videos, for the folder picker. Null is the root. */
    suspend fun browse(folder: CloudFolderRef?): List<CloudEntry>

    /** Indexes [folders]; reuses [previous] when the provider can tell what changed. */
    suspend fun index(folders: List<CloudFolderRef>, previous: CloudIndexSnapshot?): CloudIndexSnapshot

    /** Where to read [file] from right now. [forceRefresh] asks for a new link after a failure. */
    suspend fun resolve(file: CloudFile, forceRefresh: Boolean): CloudPlaybackSource

    /** Revokes what can be revoked at the provider. Local secrets are deleted by the caller. */
    suspend fun disconnect() = Unit
}

private const val MAX_SUBTITLE_BYTES = CLOUD_MAX_SUBTITLE_BYTES

internal class TeraBoxCloudProvider(
    private val api: TeraBoxApi,
    private val limits: CloudIndexLimits = CloudIndexLimits.STANDARD,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : CloudProvider {
    private val links = mutableMapOf<String, Pair<String, Long>>()

    override suspend fun verify(): String? {
        api.checkLogin()
        return null
    }

    override suspend fun browse(folder: CloudFolderRef?): List<CloudEntry> =
        api.list(folder?.path ?: "/")
            .filter { it.isDir || it.name.isCloudVideoName() }
            .map { CloudEntry(id = it.path, path = it.path, name = it.name, isFolder = it.isDir, sizeBytes = it.sizeBytes) }
            .sortedWith(compareByDescending<CloudEntry> { it.isFolder }.thenBy { it.name.lowercase() })

    /** TeraBox has no change feed, so every sync walks the selected folders again. */
    override suspend fun index(folders: List<CloudFolderRef>, previous: CloudIndexSnapshot?): CloudIndexSnapshot {
        val files = mutableListOf<CloudFile>()
        val subtitles = mutableListOf<CloudFile>()
        val queue = ArrayDeque(folders.map { teraBoxPath(it.path) to 0 })
        val seen = mutableSetOf<String>()
        while (queue.isNotEmpty() && files.size < limits.maxFiles && seen.size < limits.maxFolders) {
            val (path, depth) = queue.removeFirst()
            if (!seen.add(path)) continue
            api.list(path).forEach { item ->
                when {
                    item.isDir -> if (depth < limits.maxDepth) queue.addLast(item.path to depth + 1)
                    item.category == 1 || item.name.isCloudVideoName() -> files += item.toCloudFile(cloudMimeTypeFor(item.name))
                    item.name.isCloudSubtitleName() && item.sizeBytes < MAX_SUBTITLE_BYTES ->
                        subtitles += item.toCloudFile(cloudSubtitleMimeTypeFor(item.name))
                }
            }
        }
        return CloudIndexSnapshot(
            files = files.take(limits.maxFiles),
            subtitles = subtitles.take(limits.maxFiles),
            folderIds = folders.map { it.id },
            builtAtEpochMs = nowMs(),
            truncated = queue.isNotEmpty() || files.size > limits.maxFiles,
        )
    }

    private fun TeraBoxItem.toCloudFile(mime: String) = CloudFile(
        id = fsId.toString(),
        path = path,
        name = name,
        sizeBytes = sizeBytes,
        modifiedEpochSec = modifiedEpochSec,
        parentPath = path.substringBeforeLast('/', ""),
        mimeType = mime,
    )

    /**
     * A download link, as rclone-extra's `Object.Open` gets it, sent with the same headers.
     * Links are reused for a while and replaced on [forceRefresh] (a 403/expired link).
     */
    override suspend fun resolve(file: CloudFile, forceRefresh: Boolean): CloudPlaybackSource {
        val cached = links[file.id]?.takeIf { !forceRefresh && nowMs() - it.second < LINK_REUSE_MS }?.first
        val link = cached ?: api.downloadLink(file.path, file.id.toLongOrNull() ?: throw CloudStorageException(CloudFailure.NOT_FOUND))
            .also { links[file.id] = it to nowMs() }
        return CloudPlaybackSource(url = link, headers = api.requestHeaders, sizeBytes = file.sizeBytes)
    }

    private companion object {
        const val LINK_REUSE_MS = 20 * 60 * 1000L
    }
}

internal class GoogleDriveCloudProvider(
    private val api: GoogleDriveApi,
    private val limits: CloudIndexLimits = CloudIndexLimits.STANDARD,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : CloudProvider {
    override suspend fun verify(): String? = api.accountName()

    override suspend fun browse(folder: CloudFolderRef?): List<CloudEntry> {
        val parentId = folder?.id ?: "root"
        val parentPath = folder?.path?.trimEnd('/') ?: ""
        return api.children(parentId)
            .filter { it.isFolder || it.name.isCloudVideoName() || it.mimeType.startsWith("video/") }
            .map { CloudEntry(id = it.id, path = "$parentPath/${it.name}", name = it.name, isFolder = it.isFolder, sizeBytes = it.sizeBytes) }
            .sortedWith(compareByDescending<CloudEntry> { it.isFolder }.thenBy { it.name.lowercase() })
    }

    override suspend fun index(folders: List<CloudFolderRef>, previous: CloudIndexSnapshot?): CloudIndexSnapshot {
        val folderIds = folders.map { it.id }
        if (previous?.cursor != null && previous.folderIds == folderIds) {
            incremental(previous)?.let { return it }
        }
        return full(folders)
    }

    private suspend fun full(folders: List<CloudFolderRef>): CloudIndexSnapshot {
        // Take the change token first, so nothing that changes during the walk is missed later.
        val cursor = runCatching { api.startPageToken() }.getOrNull()
        val files = mutableListOf<CloudFile>()
        val subtitles = mutableListOf<CloudFile>()
        val folderPaths = mutableMapOf<String, String>()
        val queue = ArrayDeque(folders.map { Triple(it.id, it.path.trimEnd('/'), 0) })
        while (queue.isNotEmpty() && files.size < limits.maxFiles && folderPaths.size < limits.maxFolders) {
            val (id, path, depth) = queue.removeFirst()
            if (folderPaths.put(id, path) != null) continue
            api.children(id).forEach { item ->
                when {
                    item.isFolder -> if (depth < limits.maxDepth) queue.addLast(Triple(item.id, "$path/${item.name}", depth + 1))
                    item.isVideo() -> files += item.toCloudFile(path)
                    item.isSubtitle() -> subtitles += item.toCloudFile(path)
                }
            }
        }
        return CloudIndexSnapshot(
            files = files.take(limits.maxFiles),
            subtitles = subtitles.take(limits.maxFiles),
            folderIds = folders.map { it.id },
            cursor = cursor,
            folderPaths = folderPaths,
            builtAtEpochMs = nowMs(),
            truncated = queue.isNotEmpty() || files.size > limits.maxFiles,
        )
    }

    /** Applies Drive's change feed. Null when a folder moved or changed: then a full walk is safer. */
    private suspend fun incremental(previous: CloudIndexSnapshot): CloudIndexSnapshot? {
        val cursor = previous.cursor ?: return null
        val result = try {
            api.changes(cursor)
        } catch (error: CloudStorageException) {
            if (error.failure == CloudFailure.AUTH) throw error
            return null
        }
        val files = previous.files.associateBy { it.id }.toMutableMap()
        val subtitles = previous.subtitles.associateBy { it.id }.toMutableMap()
        for (change in result.changes) {
            val item = change.file
            if (change.removed || item == null) {
                if (change.fileId in previous.folderPaths) return null
                files.remove(change.fileId)
                subtitles.remove(change.fileId)
                continue
            }
            if (item.isFolder) {
                val known = item.id in previous.folderPaths
                val underIndex = item.parents.any { it in previous.folderPaths }
                if (known || underIndex) return null
                continue
            }
            val parentPath = item.parents.firstNotNullOfOrNull { previous.folderPaths[it] }
            when {
                parentPath != null && item.isVideo() -> files[item.id] = item.toCloudFile(parentPath)
                parentPath != null && item.isSubtitle() -> subtitles[item.id] = item.toCloudFile(parentPath)
                else -> {
                    files.remove(item.id)
                    subtitles.remove(item.id)
                }
            }
        }
        return previous.copy(
            files = files.values.take(limits.maxFiles),
            subtitles = subtitles.values.take(limits.maxFiles),
            truncated = previous.truncated || files.size > limits.maxFiles,
            cursor = result.newStartPageToken,
            builtAtEpochMs = nowMs(),
        )
    }

    override suspend fun resolve(file: CloudFile, forceRefresh: Boolean): CloudPlaybackSource =
        CloudPlaybackSource(
            url = api.playbackUrl(file.id),
            headers = mapOf("Authorization" to "Bearer ${api.accessToken(forceRefresh)}"),
            sizeBytes = file.sizeBytes,
        )

    override suspend fun disconnect() = api.revoke()

    private fun DriveItem.isVideo(): Boolean = mimeType.startsWith("video/") || name.isCloudVideoName()

    private fun DriveItem.isSubtitle(): Boolean = name.isCloudSubtitleName() && sizeBytes < MAX_SUBTITLE_BYTES

    private fun DriveItem.toCloudFile(parentPath: String) = CloudFile(
        id = id,
        path = "$parentPath/$name",
        name = name,
        sizeBytes = sizeBytes,
        modifiedEpochSec = modifiedEpochSec,
        parentPath = parentPath,
        width = width,
        height = height,
        durationMs = durationMs,
        mimeType = when {
            name.isCloudSubtitleName() -> cloudSubtitleMimeTypeFor(name)
            mimeType.startsWith("video/") -> mimeType
            else -> cloudMimeTypeFor(name)
        },
    )
}
