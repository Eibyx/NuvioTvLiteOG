package com.nuvio.tv.core.cloudstorage

import android.util.Log
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

data class CloudStorageUiState(
    val accounts: List<CloudAccount> = emptyList(),
    val syncing: Set<String> = emptySet(),
    /** Bumps whenever accounts, folders, links or an index change, so stream searches restart. */
    val revision: Int = 0
)

/**
 * Cloud accounts of the active profile (ported from NuvioMobileKai's `CloudStorageRepository`).
 *
 * Accounts and secrets go to one encrypted value per profile ([CloudPersistence]); the file index
 * of each account to a file on disk ([CloudIndexStore]), read only while streams are searched and
 * not kept in memory afterwards. Nothing here is synced to Supabase, put in a URL, exported or
 * handed to plugins. With no accounts nothing runs in the background.
 */
class CloudStorageRepository internal constructor(
    private val persistence: CloudPersistence,
    private val indexStore: CloudIndexStore,
    private val http: CloudHttp,
    private val scope: CoroutineScope,
    private val limits: CloudIndexLimits,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    initialProfileId: Int = 1
) : ProfileScopedCredentialStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val mutableState = MutableStateFlow(CloudStorageUiState())
    val uiState: StateFlow<CloudStorageUiState> = mutableState.asStateFlow()

    @Volatile
    private var activeProfileId = initialProfileId

    @Volatile
    private var loadedProfileId: Int? = null
    private var credentials: Map<String, CloudCredentials> = emptyMap()
    private val providers = mutableMapOf<String, CloudProvider>()

    /** Google sign-in finished from the phone; created only when the setup page asks for it. */
    internal val driveSignIn: GoogleDriveSignIn by lazy { GoogleDriveSignIn(http) }

    fun selectProfile(profileId: Int) {
        activeProfileId = profileId
        ensureLoaded()
        refreshStaleInBackground()
    }

    fun ensureLoaded() {
        val profileId = activeProfileId
        if (loadedProfileId != profileId) load(profileId)
    }

    fun accounts(): List<CloudAccount> {
        ensureLoaded()
        return mutableState.value.accounts
    }

    fun account(id: String): CloudAccount? = accounts().firstOrNull { it.id == id }

    fun enabledAccounts(): List<CloudAccount> = accounts().filter { it.enabled && it.folders.isNotEmpty() }

    val hasAccounts: Boolean get() = accounts().isNotEmpty()

    /** Verifies the cookie with TeraBox before anything is stored. */
    suspend fun addTeraBox(name: String, cookie: String, domain: String? = null): CloudAccount {
        val trimmed = cookie.trim().takeIf { it.isNotEmpty() } ?: throw CloudStorageException(CloudFailure.AUTH)
        if (!domain.isNullOrBlank() && teraBoxOrigin(domain) == null) throw CloudStorageException(CloudFailure.UNSUPPORTED)
        var stored = CloudCredentials(teraboxCookie = trimmed, teraboxDomain = teraBoxOrigin(domain))
        val api = TeraBoxApi(http, trimmed, stored.teraboxDomain, onSessionChanged = { token, base ->
            stored = stored.copy(teraboxJsToken = token, teraboxBaseUrl = base)
        })
        api.checkLogin()
        return save(CloudProviderKind.TERABOX, name.ifBlank { CloudProviderKind.TERABOX.displayName }, stored)
    }

    /** Verifies the user's own OAuth client and refresh token with Google before anything is stored. */
    suspend fun addGoogleDrive(
        name: String,
        clientId: String,
        clientSecret: String?,
        refreshToken: String,
        rootFolderId: String? = null
    ): CloudAccount {
        if (clientId.isBlank() || refreshToken.isBlank()) throw CloudStorageException(CloudFailure.AUTH)
        var stored = CloudCredentials(
            driveClientId = clientId.trim(),
            driveClientSecret = clientSecret?.trim()?.takeIf { it.isNotEmpty() },
            driveRefreshToken = refreshToken.trim()
        )
        val api = GoogleDriveApi(
            http, stored.driveClientId!!, stored.driveClientSecret, stored.driveRefreshToken!!,
            onTokenRefreshed = { token, expiry ->
                stored = stored.copy(driveAccessToken = token, driveAccessTokenExpiryEpochMs = expiry)
            }
        )
        val label = api.accountName()
        return save(
            CloudProviderKind.GOOGLE_DRIVE,
            name.ifBlank { label ?: CloudProviderKind.GOOGLE_DRIVE.displayName },
            stored,
            rootFolderId?.let { CloudFolderRef(id = it, path = "/") }
        )
    }

    /**
     * Adds every `terabox` and `drive` remote of a pasted rclone.conf. Drive remotes without their
     * own client id belong to rclone's OAuth client and are skipped. Returns what was added and
     * the names that were not.
     */
    suspend fun importRclone(text: String): RcloneImportResult {
        val remotes = RcloneConfigImport.parse(text)
        val added = mutableListOf<CloudAccount>()
        val skipped = mutableListOf<String>()
        for (remote in remotes) {
            try {
                when (remote) {
                    is RcloneRemote.TeraBox -> added += addTeraBox(remote.name, remote.cookie, remote.domain)
                    is RcloneRemote.GoogleDrive -> {
                        val clientId = remote.clientId
                        val refreshToken = remote.refreshToken
                        if (clientId.isNullOrBlank() || refreshToken.isNullOrBlank()) {
                            skipped += remote.name
                        } else {
                            added += addGoogleDrive(remote.name, clientId, remote.clientSecret, refreshToken, remote.rootFolderId)
                        }
                    }
                    is RcloneRemote.Unsupported -> skipped += remote.name
                }
            } catch (error: CloudStorageException) {
                skipped += remote.name
            }
        }
        return RcloneImportResult(added, skipped)
    }

    /**
     * Stores a verified account with its whole drive selected (from the root) and indexes it right
     * away; the user can narrow the folders later.
     */
    private fun save(kind: CloudProviderKind, name: String, stored: CloudCredentials, folder: CloudFolderRef? = null): CloudAccount {
        ensureLoaded()
        val root = folder ?: when (kind) {
            CloudProviderKind.GOOGLE_DRIVE -> CloudFolderRef(id = "root", path = "/")
            CloudProviderKind.TERABOX -> CloudFolderRef(id = "/", path = "/")
        }
        val account = CloudAccount(
            id = UUID.randomUUID().toString(),
            kind = kind,
            name = name.trim().take(60),
            folders = listOf(root)
        )
        mutate(credentialChange = { it + (account.id to stored) }) { it + account }
        syncInBackground(account.id)
        return account
    }

    /** Re-indexes accounts whose index is older than six hours, without waiting. */
    fun refreshStaleInBackground() {
        accounts().filter { it.enabled }.forEach(::refreshIfStale)
    }

    fun refreshIfStale(account: CloudAccount) {
        val last = account.lastSyncEpochMs ?: 0L
        if (account.folders.isEmpty() || nowMs() - last < STALE_AFTER_MS) return
        syncInBackground(account.id)
    }

    /** [force]: the user asked (Sync now): accept the result even if it shrank. */
    fun syncInBackground(id: String, force: Boolean = false) {
        if (id in mutableState.value.syncing) return
        scope.launch {
            runCatching { sync(id, force) }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Background sync failed: ${error.cloudFailure()}")
            }
        }
    }

    fun update(id: String, change: (CloudAccount) -> CloudAccount) {
        mutate { accounts -> accounts.map { if (it.id == id) change(it) else it } }
    }

    fun setEnabled(id: String, enabled: Boolean) = update(id) { it.copy(enabled = enabled) }

    fun setShowFirst(id: String, showFirst: Boolean) = update(id) { it.copy(showFirst = showFirst) }

    /** null: automatic; 1–4 simultaneous connections when playing. Applies from the next play. */
    fun setConnections(id: String, connections: Int?) =
        update(id) { it.copy(connections = connections?.coerceIn(1, LocalCloudStreamServer.MAX_CONNECTIONS)) }

    fun setFolders(id: String, folders: List<CloudFolderRef>) {
        update(id) { it.copy(folders = folders.distinctBy { folder -> folder.id }) }
        syncInBackground(id)
    }

    fun addFolder(id: String, folder: CloudFolderRef) {
        val account = account(id) ?: return
        // Adding a folder narrows a whole-drive selection to the folders the user picks.
        val current = account.folders.filterNot { it.path == "/" && (it.id == "root" || it.id == "/") }
        setFolders(id, current + folder)
    }

    fun removeFolder(id: String, folderId: String) {
        val account = account(id) ?: return
        setFolders(id, account.folders.filterNot { it.id == folderId })
    }

    fun setManualLink(id: String, link: CloudManualLink) = update(id) { account ->
        account.copy(manualLinks = account.manualLinks.filterNot { it.fileId == link.fileId } + link)
    }

    fun removeManualLink(id: String, fileId: String) = update(id) { account ->
        account.copy(manualLinks = account.manualLinks.filterNot { it.fileId == fileId })
    }

    /** Revokes the Google token, then deletes the account, its secrets, its index and its links. */
    suspend fun disconnect(id: String) {
        runCatching { provider(id)?.disconnect() }.onFailure { if (it is CancellationException) throw it }
        val profileId = activeProfileId
        runCatching { indexStore.delete(profileId, id) }
        synchronized(lock) { providers.remove(id) }
        mutate(credentialChange = { it - id }) { accounts -> accounts.filterNot { it.id == id } }
    }

    suspend fun verify(id: String): String? {
        val provider = provider(id) ?: throw CloudStorageException(CloudFailure.NOT_FOUND)
        return try {
            provider.verify().also { update(id) { it.copy(lastError = null) } }
        } catch (error: CloudStorageException) {
            update(id) { it.copy(lastError = error.failure.name) }
            throw error
        }
    }

    suspend fun browse(id: String, folder: CloudFolderRef?): List<CloudEntry> =
        (provider(id) ?: throw CloudStorageException(CloudFailure.NOT_FOUND)).browse(folder)

    /** Indexes the account's selected folders. Never downloads, moves or changes a file. */
    suspend fun sync(id: String, force: Boolean = false): Int = syncSnapshot(id, force).files.size

    private suspend fun syncSnapshot(id: String, force: Boolean = false): CloudIndexSnapshot {
        val account = account(id) ?: throw CloudStorageException(CloudFailure.NOT_FOUND)
        val provider = provider(id) ?: throw CloudStorageException(CloudFailure.NOT_FOUND)
        val profileId = activeProfileId
        var started = false
        mutableState.update { state ->
            started = id !in state.syncing
            if (started) state.copy(syncing = state.syncing + id) else state
        }
        if (!started) return index(id) ?: CloudIndexSnapshot()
        try {
            val previous = index(id)
            val snapshot = provider.index(account.folders, previous)
            // The account may have been removed or the profile switched while indexing.
            if (profileId != activeProfileId || account(id) == null) return snapshot
            // A background walk that suddenly finds far fewer videos (an expired session or a
            // provider hiccup can answer with empty folders) must not wipe a good index.
            val before = previous?.takeIf { it.folderIds == snapshot.folderIds }?.files?.size ?: 0
            if (!force && before >= SHRINK_GUARD_MIN_FILES && snapshot.files.size < before / 2) {
                Log.w(TAG, "Kept the previous index: ${snapshot.files.size} videos found, $before before")
                update(id) {
                    // Retry in about an hour instead of the usual six.
                    it.copy(lastError = INCOMPLETE, lastSyncEpochMs = nowMs() - STALE_AFTER_MS + RETRY_AFTER_SHRINK_MS)
                }
                return previous!!
            }
            indexStore.write(profileId, id, json.encodeToString(CloudIndexSnapshot.serializer(), snapshot))
            update(id) {
                it.copy(
                    lastSyncEpochMs = nowMs(),
                    indexedFiles = snapshot.files.size,
                    lastError = null,
                    truncated = snapshot.truncated
                )
            }
            Log.i(TAG, "Indexed ${snapshot.files.size} videos for a ${account.kind.id} account")
            return snapshot
        } catch (error: CloudStorageException) {
            update(id) { it.copy(lastError = error.failure.name) }
            throw error
        } finally {
            mutableState.update { it.copy(syncing = it.syncing - id) }
        }
    }

    /** Reads an account's index from disk. Callers keep it only as long as they need it. */
    internal fun index(id: String): CloudIndexSnapshot? {
        val stored = runCatching { indexStore.read(activeProfileId, id) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching { json.decodeFromString(CloudIndexSnapshot.serializer(), stored) }.getOrNull()
    }

    internal fun provider(id: String): CloudProvider? {
        synchronized(lock) { providers[id] }?.let { return it }
        val account = account(id) ?: return null
        val profileId = activeProfileId
        val stored = synchronized(lock) { credentials[id] } ?: return null
        val provider: CloudProvider = when (account.kind) {
            CloudProviderKind.TERABOX -> TeraBoxCloudProvider(
                TeraBoxApi(
                    http = http,
                    cookie = stored.teraboxCookie ?: return null,
                    domain = stored.teraboxDomain,
                    jsToken = stored.teraboxJsToken,
                    baseUrl = stored.teraboxBaseUrl,
                    onSessionChanged = { token, base ->
                        updateCredentials(profileId, id) { it.copy(teraboxJsToken = token, teraboxBaseUrl = base) }
                    }
                ),
                limits = limits
            )
            CloudProviderKind.GOOGLE_DRIVE -> GoogleDriveCloudProvider(
                GoogleDriveApi(
                    http = http,
                    clientId = stored.driveClientId ?: return null,
                    clientSecret = stored.driveClientSecret,
                    refreshToken = stored.driveRefreshToken ?: return null,
                    accessToken = stored.driveAccessToken,
                    accessTokenExpiryEpochMs = stored.driveAccessTokenExpiryEpochMs,
                    onTokenRefreshed = { token, expiry ->
                        updateCredentials(profileId, id) {
                            it.copy(driveAccessToken = token, driveAccessTokenExpiryEpochMs = expiry)
                        }
                    }
                ),
                limits = limits
            )
        }
        synchronized(lock) { providers[id] = provider }
        return provider
    }

    /** Deleting a profile deletes its cloud accounts, secrets and indexes. */
    override fun removeProfile(profileId: Int) {
        runCatching { persistence.write(profileId, null) }
        runCatching { indexStore.deleteProfile(profileId) }
        if (profileId == loadedProfileId) load(profileId)
    }

    /** Signing out of Nuvio wipes every profile's cloud accounts on this device. */
    override fun clearAllProfiles() {
        runCatching { persistence.clear() }.onFailure { Log.w(TAG, "Unable to clear cloud storage") }
        runCatching { indexStore.clear() }
        synchronized(lock) {
            providers.clear()
            credentials = emptyMap()
        }
        loadedProfileId = null
        mutableState.update { CloudStorageUiState(revision = it.revision + 1) }
    }

    private fun load(profileId: Int) {
        val stored = readStored(profileId)
        synchronized(lock) {
            providers.clear()
            credentials = stored.credentials
        }
        loadedProfileId = profileId
        mutableState.update { CloudStorageUiState(accounts = stored.accounts, revision = it.revision + 1) }
    }

    private fun mutate(
        credentialChange: (Map<String, CloudCredentials>) -> Map<String, CloudCredentials> = { it },
        change: (List<CloudAccount>) -> List<CloudAccount>
    ) {
        val profileId = activeProfileId
        if (loadedProfileId != profileId) load(profileId)
        synchronized(lock) {
            val accounts = change(mutableState.value.accounts)
            val ids = accounts.map { it.id }.toSet()
            val updatedCredentials = credentialChange(credentials).filterKeys { it in ids }
            persistence.write(
                profileId,
                if (accounts.isEmpty()) null
                else json.encodeToString(StoredCloud.serializer(), StoredCloud(accounts, updatedCredentials))
            )
            credentials = updatedCredentials
            providers.keys.retainAll(ids)
            mutableState.update { it.copy(accounts = accounts, revision = it.revision + 1) }
        }
    }

    private fun updateCredentials(profileId: Int, accountId: String, change: (CloudCredentials) -> CloudCredentials) {
        if (profileId != loadedProfileId) return
        val current = synchronized(lock) { credentials[accountId] } ?: return
        val updated = change(current)
        if (updated == current) return
        mutate(credentialChange = { it + (accountId to updated) }) { it }
    }

    private fun readStored(profileId: Int): StoredCloud =
        runCatching { persistence.read(profileId) }.getOrNull()
            ?.let { runCatching { json.decodeFromString(StoredCloud.serializer(), it) }.getOrNull() }
            ?: StoredCloud()

    private companion object {
        const val TAG = "CloudStorage"
        const val STALE_AFTER_MS = 6 * 60 * 60 * 1000L
        const val SHRINK_GUARD_MIN_FILES = 10
        const val RETRY_AFTER_SHRINK_MS = 60 * 60 * 1000L
        /** [CloudAccount.lastError] when a sync found far fewer videos and the old index was kept. */
        const val INCOMPLETE = "INCOMPLETE"
    }
}

data class RcloneImportResult(val added: List<CloudAccount>, val skipped: List<String>)
