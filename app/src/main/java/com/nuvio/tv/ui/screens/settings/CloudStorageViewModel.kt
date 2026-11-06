package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.cloudstorage.CloudEntry
import com.nuvio.tv.core.cloudstorage.CloudFolderRef
import com.nuvio.tv.core.cloudstorage.CloudSetupHandler
import com.nuvio.tv.core.cloudstorage.CloudSetupMode
import com.nuvio.tv.core.cloudstorage.CloudSetupServer
import com.nuvio.tv.core.cloudstorage.CloudStorageRepository
import com.nuvio.tv.core.cloudstorage.CloudStorageUiState
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudSetupUiState(
    val mode: CloudSetupMode,
    val qrBitmap: Bitmap?,
    val url: String,
    val code: String,
    val message: String? = null
)

data class CloudBrowseUiState(
    val accountId: String,
    val folder: CloudFolderRef?,
    val parents: List<CloudFolderRef?> = emptyList(),
    val entries: List<CloudEntry> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false
)

@HiltViewModel
class CloudStorageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CloudStorageRepository,
    private val cloudStreams: com.nuvio.tv.core.cloudstorage.CloudStreams
) : ViewModel() {
    val uiState: StateFlow<CloudStorageUiState> = repository.uiState

    private val mutableSetup = MutableStateFlow<CloudSetupUiState?>(null)
    val setup: StateFlow<CloudSetupUiState?> = mutableSetup.asStateFlow()

    private val mutableBrowse = MutableStateFlow<CloudBrowseUiState?>(null)
    val browse: StateFlow<CloudBrowseUiState?> = mutableBrowse.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    /** accountId → the last speed test's text (null while it runs). */
    private val mutableSpeed = MutableStateFlow<Map<String, String?>>(emptyMap())
    val speed: StateFlow<Map<String, String?>> = mutableSpeed.asStateFlow()

    fun speedTest(id: String) {
        if (mutableSpeed.value.containsKey(id) && mutableSpeed.value[id] == null) return
        mutableSpeed.update { it + (id to null) }
        viewModelScope.launch {
            val text = try {
                val result = cloudStreams.speedTest(id)
                val enough = result.fileMbps?.let { result.mbps >= it * 1.2 }
                listOfNotNull(
                    context.getString(R.string.cloud_speed_result, "%.1f".format(result.mbps), "%.1f".format(result.startMs / 1000.0)),
                    result.fileMbps?.let {
                        context.getString(
                            if (enough == true) R.string.cloud_speed_enough else R.string.cloud_speed_not_enough,
                            "%.1f".format(it)
                        )
                    } ?: context.getString(R.string.cloud_speed_reference)
                ).joinToString(" · ")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                context.getString(R.string.cloud_speed_failed)
            }
            mutableSpeed.update { it + (id to text) }
        }
    }

    private var setupServer: CloudSetupServer? = null
    private var setupTimeout: Job? = null
    private var browseJob: Job? = null

    init {
        repository.ensureLoaded()
    }

    /** Starts the LAN page; it lives only while the QR is on screen. */
    fun startSetup(mode: CloudSetupMode, accountId: String? = null) {
        stopSetup()
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            mutableError.value = context.getString(R.string.error_network_required)
            return
        }
        val code = CloudSetupHandler.newCode()
        val handler = CloudSetupHandler(
            mode = mode,
            code = code,
            repository = repository,
            linkAccountId = accountId,
            onAccountsChanged = { message ->
                val text = context.getString(
                    if (mode == CloudSetupMode.LINK) R.string.cloud_qr_linked else R.string.cloud_qr_added,
                    message
                )
                if (mode == CloudSetupMode.ACCOUNTS) {
                    // Done: close the page and the QR.
                    viewModelScope.launch { stopSetup(); mutableError.value = text }
                } else {
                    mutableSetup.update { it?.copy(message = text) }
                }
            },
            onLocked = {
                viewModelScope.launch { stopSetup(); mutableError.value = context.getString(R.string.cloud_qr_locked) }
            }
        )
        val server = CloudSetupServer.startOnAvailablePort(handler)
        if (server == null) {
            mutableError.value = context.getString(R.string.error_server_ports_unavailable)
            return
        }
        setupServer = server
        val url = "http://$ip:${server.listeningPort}"
        mutableSetup.value = CloudSetupUiState(
            mode = mode,
            qrBitmap = runCatching { QrCodeGenerator.generate(url, 420, margin = 1) }.getOrNull(),
            url = url,
            code = code
        )
        setupTimeout = viewModelScope.launch {
            delay(SETUP_TIMEOUT_MS)
            stopSetup()
        }
    }

    fun stopSetup() {
        setupTimeout?.cancel()
        setupTimeout = null
        runCatching { setupServer?.stop() }
        setupServer = null
        mutableSetup.value = null
    }

    fun clearError() {
        mutableError.value = null
    }

    fun setEnabled(id: String, enabled: Boolean) = repository.setEnabled(id, enabled)

    fun setShowFirst(id: String, showFirst: Boolean) = repository.setShowFirst(id, showFirst)

    fun setConnections(id: String, connections: Int?) = repository.setConnections(id, connections)

    /** What "Automatic" means on this TV. */
    val automaticConnections: Int = if (com.nuvio.tv.core.device.DeviceMemoryTier.isLowRam) 3 else 4

    fun syncNow(id: String) = repository.syncInBackground(id, force = true)

    fun removeFolder(id: String, folderId: String) = repository.removeFolder(id, folderId)

    fun removeLink(id: String, fileId: String) = repository.removeManualLink(id, fileId)

    fun disconnect(id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                repository.disconnect(id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
            onDone()
        }
    }

    fun openBrowser(accountId: String) {
        mutableBrowse.value = CloudBrowseUiState(accountId = accountId, folder = null)
        load(accountId, null)
    }

    fun enterFolder(entry: CloudEntry) {
        val current = mutableBrowse.value ?: return
        val folder = CloudFolderRef(id = entry.id, path = entry.path)
        mutableBrowse.value = current.copy(folder = folder, parents = current.parents + listOf(current.folder), entries = emptyList(), loading = true, failed = false)
        load(current.accountId, folder)
    }

    /** False when already at the root: the caller closes the browser. */
    fun upOneLevel(): Boolean {
        val current = mutableBrowse.value ?: return false
        if (current.parents.isEmpty()) return false
        val parent = current.parents.last()
        mutableBrowse.value = current.copy(folder = parent, parents = current.parents.dropLast(1), entries = emptyList(), loading = true, failed = false)
        load(current.accountId, parent)
        return true
    }

    fun addCurrentFolder() {
        val current = mutableBrowse.value ?: return
        val folder = current.folder ?: return
        repository.addFolder(current.accountId, folder)
        closeBrowser()
    }

    fun closeBrowser() {
        browseJob?.cancel()
        mutableBrowse.value = null
    }

    private fun load(accountId: String, folder: CloudFolderRef?) {
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            val result = runCatching { repository.browse(accountId, folder) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            mutableBrowse.update { state ->
                if (state == null || state.folder != folder) state
                else state.copy(
                    entries = result.getOrDefault(emptyList()).filter { it.isFolder },
                    loading = false,
                    failed = result.isFailure
                )
            }
        }
    }

    override fun onCleared() {
        stopSetup()
        super.onCleared()
    }

    private companion object {
        const val SETUP_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
