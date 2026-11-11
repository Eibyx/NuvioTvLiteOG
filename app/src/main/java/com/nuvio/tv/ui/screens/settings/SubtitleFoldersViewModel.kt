// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0) and adapted to NuvioTvLiteOG.
package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.cloudstorage.CloudAccount
import com.nuvio.tv.core.cloudstorage.CloudFolderRef
import com.nuvio.tv.core.cloudstorage.CloudProviderKind
import com.nuvio.tv.core.cloudstorage.CloudStorageRepository
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.subtitlelibrary.LocalAccessCapabilities
import com.nuvio.tv.core.subtitlelibrary.LocalAccessMethod
import com.nuvio.tv.core.subtitlelibrary.LocalAccessOption
import com.nuvio.tv.core.subtitlelibrary.LocalAccessPlanner
import com.nuvio.tv.core.subtitlelibrary.SubtitleFolder
import com.nuvio.tv.core.subtitlelibrary.SubtitleLibraryService
import com.nuvio.tv.core.subtitlelibrary.SubtitleLibrarySettings
import com.nuvio.tv.core.subtitlelibrary.SubtitleLibraryStore
import com.nuvio.tv.core.subtitlelibrary.SubtitleOrigin
import com.nuvio.tv.core.subtitlelibrary.SubtitleUploadServer
import com.nuvio.tv.core.subtitlelibrary.phoneUploadRoot
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A folder browser over a cloud account or the TV's file system, to choose the `Subtítulos` root. */
data class SubtitleFolderBrowserState(
    val origin: SubtitleOrigin,
    val accountId: String? = null,
    val method: LocalAccessMethod? = null,
    /** Folders opened so far: (id, name, path); empty = the account root or the list of volumes. */
    val stack: List<Triple<String, String, String>> = emptyList(),
    val folders: List<Triple<String, String, String>> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false
)

data class SubtitlePhoneUploadState(val url: String, val qr: Bitmap?, val received: Int = 0)

/** A document picker the TV offers, per storage volume where Android 10+ can say. */
data class SubtitleSafTarget(val label: String?, val intent: Intent)

@HiltViewModel
class SubtitleFoldersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SubtitleLibraryStore,
    private val service: SubtitleLibraryService,
    private val cloud: CloudStorageRepository
) : ViewModel() {
    val settings: StateFlow<SubtitleLibrarySettings> = store.settings

    private val mutableBrowser = MutableStateFlow<SubtitleFolderBrowserState?>(null)
    val browser: StateFlow<SubtitleFolderBrowserState?> = mutableBrowser.asStateFlow()

    private val mutablePhone = MutableStateFlow<SubtitlePhoneUploadState?>(null)
    val phone: StateFlow<SubtitlePhoneUploadState?> = mutablePhone.asStateFlow()

    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    private val mutableCacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = mutableCacheBytes.asStateFlow()

    private var uploadServer: SubtitleUploadServer? = null

    init {
        cloud.ensureLoaded()
        refreshCacheSize()
    }

    fun accounts(kind: CloudProviderKind): List<CloudAccount> = cloud.accounts().filter { it.kind == kind }

    fun clearMessage() { mutableMessage.value = null }

    private fun refreshCacheSize() {
        viewModelScope.launch { mutableCacheBytes.value = withContext(Dispatchers.IO) { service.cacheBytes() } }
    }

    // ---- Folder list ----------------------------------------------------------------------------

    fun setEnabled(folder: SubtitleFolder, enabled: Boolean) = viewModelScope.launch { store.setFolderEnabled(folder.id, enabled) }

    fun refresh(folder: SubtitleFolder) {
        service.invalidate(folder.id)
        mutableMessage.value = context.getString(R.string.subtitle_library_refreshed)
    }

    fun remove(folder: SubtitleFolder) = viewModelScope.launch {
        store.removeFolder(folder.id)
        service.invalidate(folder.id)
        if (folder.accessMethod == LocalAccessMethod.SAF) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(folder.folderId), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    fun setAutoSelect(enabled: Boolean) = viewModelScope.launch { store.setAutoSelect(enabled) }

    fun setCacheLimit(limitMb: Int) = viewModelScope.launch {
        store.setCacheLimitMb(limitMb)
        refreshCacheSize()
    }

    fun clearCache() = viewModelScope.launch {
        withContext(Dispatchers.IO) { service.clearCache() }
        refreshCacheSize()
    }

    private suspend fun add(folder: SubtitleFolder) {
        store.addFolder(folder)
        mutableBrowser.value = null
    }

    // ---- Local storage --------------------------------------------------------------------------

    /** What this TV offers, found now (never by model). */
    internal fun localOptions(): List<LocalAccessOption> {
        val pm = context.packageManager
        val treeResolves = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).resolveActivity(pm) != null
        val allFilesResolves = Build.VERSION.SDK_INT >= 30 &&
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                .resolveActivity(pm) != null
        return LocalAccessPlanner.options(LocalAccessCapabilities(Build.VERSION.SDK_INT, treeResolves, allFilesResolves))
    }

    /** One picker per volume on Android 10+ (so the USB drive shows up directly), else the plain picker. */
    fun safTargets(): List<SubtitleSafTarget> {
        val plain = SubtitleSafTarget(null, Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
        if (Build.VERSION.SDK_INT < 29) return listOf(plain)
        val manager = context.getSystemService(StorageManager::class.java) ?: return listOf(plain)
        val volumes = runCatching { manager.storageVolumes }.getOrDefault(emptyList())
            .mapNotNull { volume ->
                runCatching { SubtitleSafTarget(volume.getDescription(context), volume.createOpenDocumentTreeIntent()) }.getOrNull()
            }
        return volumes.ifEmpty { listOf(plain) }
    }

    fun onSafPicked(uri: Uri?) {
        uri ?: return
        viewModelScope.launch {
            val ok = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            if (!ok) {
                mutableMessage.value = context.getString(R.string.subtitle_library_permission_denied)
                return@launch
            }
            val name = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.ifBlank { null }
                ?: context.getString(R.string.subtitle_library_origin_local_long)
            add(
                SubtitleFolder(
                    id = UUID.randomUUID().toString(),
                    origin = SubtitleOrigin.LOCAL.id,
                    displayName = name,
                    folderId = uri.toString(),
                    folderPath = name,
                    localMethod = LocalAccessMethod.SAF.id
                )
            )
        }
    }

    fun allFilesSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** Starts the file browser at the list of mounted volumes (internal storage, USB `/storage/<UUID>`). */
    fun openFileBrowser(method: LocalAccessMethod) {
        mutableBrowser.value = SubtitleFolderBrowserState(origin = SubtitleOrigin.LOCAL, method = method, folders = storageRoots())
    }

    private fun storageRoots(): List<Triple<String, String, String>> {
        val roots = linkedMapOf<String, String>()
        @Suppress("DEPRECATION")
        Environment.getExternalStorageDirectory()?.let { roots[it.absolutePath] = it.absolutePath }
        context.getExternalFilesDirs(null).filterNotNull().forEach { appDir ->
            val path = appDir.absolutePath.substringBefore("/Android/data/")
            if (path.isNotBlank()) roots.putIfAbsent(path, path)
        }
        File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self" }
            ?.forEach { roots.putIfAbsent(it.absolutePath, it.absolutePath) }
        return roots.keys.map { Triple(it, it.substringAfterLast('/'), it) }
    }

    // ---- Cloud ----------------------------------------------------------------------------------

    fun openCloudBrowser(account: CloudAccount) {
        val origin = if (account.kind == CloudProviderKind.TERABOX) SubtitleOrigin.TERABOX else SubtitleOrigin.DRIVE
        loadBrowser(SubtitleFolderBrowserState(origin = origin, accountId = account.id))
    }

    fun enter(folder: Triple<String, String, String>) {
        val current = mutableBrowser.value ?: return
        loadBrowser(current.copy(stack = current.stack + folder))
    }

    /** False when already at the top: the caller closes the browser. */
    fun up(): Boolean {
        val current = mutableBrowser.value ?: return false
        if (current.stack.isEmpty()) return false
        val next = current.copy(stack = current.stack.dropLast(1))
        if (next.stack.isEmpty() && next.origin == SubtitleOrigin.LOCAL) {
            mutableBrowser.value = next.copy(folders = storageRoots(), loading = false, failed = false)
        } else {
            loadBrowser(next)
        }
        return true
    }

    fun closeBrowser() { mutableBrowser.value = null }

    fun useCurrentFolder() {
        val current = mutableBrowser.value ?: return
        val (id, name, path) = current.stack.lastOrNull() ?: return
        viewModelScope.launch {
            add(
                SubtitleFolder(
                    id = UUID.randomUUID().toString(),
                    origin = current.origin.id,
                    displayName = name,
                    accountId = current.accountId,
                    folderId = id,
                    folderPath = path,
                    localMethod = current.method?.id
                )
            )
        }
    }

    private fun loadBrowser(state: SubtitleFolderBrowserState) {
        mutableBrowser.value = state.copy(loading = true, failed = false)
        viewModelScope.launch {
            val folders = try {
                val top = state.stack.lastOrNull()
                if (state.origin == SubtitleOrigin.LOCAL) {
                    withContext(Dispatchers.IO) {
                        File(top?.first ?: "/").listFiles().orEmpty()
                            .filter { it.isDirectory && !it.isHidden }
                            .sortedBy { it.name.lowercase() }
                            .map { Triple(it.absolutePath, it.name, it.absolutePath) }
                    }
                } else {
                    val accountId = state.accountId ?: return@launch
                    cloud.browse(accountId, top?.let { CloudFolderRef(id = it.first, path = it.third) })
                        .filter { it.isFolder }
                        .map { Triple(it.id, it.name, it.path) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableBrowser.update { if (it?.stack == state.stack) it.copy(loading = false, failed = true) else it }
                return@launch
            }
            mutableBrowser.update { if (it?.stack == state.stack) it.copy(folders = folders, loading = false) else it }
        }
    }

    // ---- Send from the phone --------------------------------------------------------------------

    fun startPhoneUpload() {
        stopPhoneUpload()
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            mutableMessage.value = context.getString(R.string.error_network_required)
            return
        }
        val root = phoneUploadRoot(context)
        val server = SubtitleUploadServer.start(root, onReceived = { count ->
            mutablePhone.update { it?.copy(received = count) }
        })
        if (server == null) {
            mutableMessage.value = context.getString(R.string.subtitle_library_phone_unavailable)
            return
        }
        uploadServer = server
        val url = "http://$ip:${server.listeningPort}${server.path}"
        mutablePhone.value = SubtitlePhoneUploadState(url, runCatching { QrCodeGenerator.generate(url, 420, margin = 1) }.getOrNull())
        viewModelScope.launch {
            // The uploads folder counts as internal storage; added once.
            if (store.current().folders.none { it.accessMethod == LocalAccessMethod.PHONE_UPLOAD }) {
                store.addFolder(
                    SubtitleFolder(
                        id = UUID.randomUUID().toString(),
                        origin = SubtitleOrigin.LOCAL.id,
                        displayName = context.getString(R.string.subtitle_library_phone_folder_name),
                        folderId = root.absolutePath,
                        folderPath = root.absolutePath,
                        localMethod = LocalAccessMethod.PHONE_UPLOAD.id
                    )
                )
            }
        }
    }

    fun stopPhoneUpload() {
        runCatching { uploadServer?.stop() }
        uploadServer = null
        if (mutablePhone.value != null) {
            store.settings.value.folders.firstOrNull { it.accessMethod == LocalAccessMethod.PHONE_UPLOAD }?.let { service.invalidate(it.id) }
        }
        mutablePhone.value = null
    }

    override fun onCleared() {
        stopPhoneUpload()
        super.onCleared()
    }
}
