// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0).
package com.nuvio.tv.core.subtitlelibrary

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.nuvio.tv.core.cloudstorage.CLOUD_FOLDER_MIME
import com.nuvio.tv.core.cloudstorage.CloudFailure
import com.nuvio.tv.core.cloudstorage.CloudFile
import com.nuvio.tv.core.cloudstorage.CloudFolderRef
import com.nuvio.tv.core.cloudstorage.CloudStorageException
import com.nuvio.tv.core.cloudstorage.CloudStorageRepository
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Why a configured folder could not be read this time. */
enum class LibraryUnavailable { USB_DISCONNECTED, ACCOUNT_DISCONNECTED, PERMISSION_MISSING, FAILED }

internal class LibrarySourceException(val reason: LibraryUnavailable, message: String? = null) : IOException(message)

/** Read-only access to one configured root, whatever the origin. Folder ids are origin-specific. */
internal interface LibrarySource {
    val folder: SubtitleFolder
    /** The root's own folder id, as [list] takes it. */
    val rootId: String get() = folder.folderId
    suspend fun list(folderId: String, folderPath: String): List<LibraryEntry>
    /** Opens a file for reading; the caller closes it. [maxBytes] guards against huge files. */
    suspend fun open(entry: LibraryEntry): InputStream
    /** Throws [LibrarySourceException] when the root cannot be read now (USB unplugged…). */
    suspend fun checkAvailable()
}

/** TeraBox and Google Drive through the accounts of Media servers; nothing is written to the cloud. */
internal class CloudLibrarySource(
    override val folder: SubtitleFolder,
    private val repository: CloudStorageRepository,
    private val client: OkHttpClient
) : LibrarySource {
    private val accountId: String get() = folder.accountId ?: throw LibrarySourceException(LibraryUnavailable.ACCOUNT_DISCONNECTED)

    override suspend fun checkAvailable() {
        if (repository.account(accountId) == null || repository.provider(accountId) == null) {
            throw LibrarySourceException(LibraryUnavailable.ACCOUNT_DISCONNECTED)
        }
    }

    override suspend fun list(folderId: String, folderPath: String): List<LibraryEntry> = try {
        repository.listFiles(accountId, CloudFolderRef(id = folderId, path = folderPath.ifEmpty { folderId })).map { file ->
            val isFolder = file.mimeType == CLOUD_FOLDER_MIME
            LibraryEntry(
                // TeraBox lists folders by path, Drive by id.
                id = if (isFolder && folder.originKind == SubtitleOrigin.TERABOX) file.path else file.id,
                name = file.name,
                isFolder = isFolder,
                sizeBytes = file.sizeBytes,
                modifiedEpochMs = file.modifiedEpochSec * 1000,
                path = file.path
            )
        }
    } catch (error: CloudStorageException) {
        throw if (error.failure == CloudFailure.NOT_FOUND) LibrarySourceException(LibraryUnavailable.ACCOUNT_DISCONNECTED)
        else LibrarySourceException(LibraryUnavailable.FAILED, error.failure.name)
    }

    override suspend fun open(entry: LibraryEntry): InputStream = withContext(Dispatchers.IO) {
        val provider = repository.provider(accountId) ?: throw LibrarySourceException(LibraryUnavailable.ACCOUNT_DISCONNECTED)
        val file = CloudFile(
            id = entry.id,
            path = entry.path,
            name = entry.name,
            sizeBytes = entry.sizeBytes,
            modifiedEpochSec = entry.modifiedEpochMs / 1000,
            parentPath = entry.path.substringBeforeLast('/', "")
        )
        var attempt = 0
        while (true) {
            val source = provider.resolve(file, forceRefresh = attempt > 0)
            val request = Request.Builder().url(source.url).apply { source.headers.forEach { (k, v) -> header(k, v) } }.build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body ?: run { response.close(); throw IOException("empty body") }
                return@withContext body.byteStream()
            }
            val code = response.code
            response.close()
            // An expired link: one more try with a fresh one.
            if ((code == 403 || code == 401 || code == 410) && attempt == 0) {
                attempt++
                continue
            }
            throw IOException("HTTP $code")
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("unreachable")
    }
}

/** A tree picked with the Storage Access Framework (internal storage or USB). */
internal class SafLibrarySource(
    override val folder: SubtitleFolder,
    private val context: Context
) : LibrarySource {
    private val treeUri: Uri = Uri.parse(folder.folderId)
    private val rootDocumentId: String? = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()

    override suspend fun checkAvailable() = withContext(Dispatchers.IO) {
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
        if (!granted) throw LibrarySourceException(LibraryUnavailable.PERMISSION_MISSING)
        val documentId = rootDocumentId ?: throw LibrarySourceException(LibraryUnavailable.FAILED)
        val readable = runCatching {
            context.contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
        if (!readable) throw LibrarySourceException(LibraryUnavailable.USB_DISCONNECTED)
    }

    override suspend fun list(folderId: String, folderPath: String): List<LibraryEntry> = withContext(Dispatchers.IO) {
        val documentId = if (folderId == folder.folderId) rootDocumentId else folderId
        documentId ?: throw LibrarySourceException(LibraryUnavailable.FAILED)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val cursor = try {
            context.contentResolver.query(children, columns, null, null, null)
        } catch (error: Exception) {
            throw LibrarySourceException(LibraryUnavailable.USB_DISCONNECTED, error::class.java.simpleName)
        } ?: throw LibrarySourceException(LibraryUnavailable.USB_DISCONNECTED)
        cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    add(
                        LibraryEntry(
                            id = id,
                            name = it.getString(1) ?: continue,
                            isFolder = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                            sizeBytes = if (it.isNull(3)) 0 else it.getLong(3),
                            modifiedEpochMs = if (it.isNull(4)) 0 else it.getLong(4)
                        )
                    )
                }
            }
        }
    }

    override suspend fun open(entry: LibraryEntry): InputStream = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.id))
            ?: throw LibrarySourceException(LibraryUnavailable.USB_DISCONNECTED)
    }
}

/** Plain files: legacy storage permission, "All files access", or the phone uploads in the app's storage. */
internal class FileLibrarySource(
    override val folder: SubtitleFolder,
    private val permissionGranted: () -> Boolean = { true }
) : LibrarySource {
    override suspend fun checkAvailable() = withContext(Dispatchers.IO) {
        if (!permissionGranted()) throw LibrarySourceException(LibraryUnavailable.PERMISSION_MISSING)
        if (!File(folder.folderId).isDirectory) throw LibrarySourceException(LibraryUnavailable.USB_DISCONNECTED)
    }

    override suspend fun list(folderId: String, folderPath: String): List<LibraryEntry> = withContext(Dispatchers.IO) {
        val dir = File(folderId)
        val children = dir.listFiles() ?: throw LibrarySourceException(LibraryUnavailable.USB_DISCONNECTED)
        children.filter { !it.isHidden }.map { file ->
            LibraryEntry(
                id = file.absolutePath,
                name = file.name,
                isFolder = file.isDirectory,
                sizeBytes = if (file.isFile) file.length() else 0,
                modifiedEpochMs = file.lastModified()
            )
        }
    }

    override suspend fun open(entry: LibraryEntry): InputStream = withContext(Dispatchers.IO) {
        FileInputStream(File(entry.id))
    }
}

/** The app-private folder that "Send from phone" fills: `filesDir/kai-subtitles/Subtítulos`. */
internal fun phoneUploadRoot(context: Context): File = File(File(context.filesDir, "kai-subtitles"), "Subtítulos")
