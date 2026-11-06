@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.cloudstorage.CloudAccount
import com.nuvio.tv.core.cloudstorage.CloudFailure
import com.nuvio.tv.core.cloudstorage.CloudProviderKind
import com.nuvio.tv.core.cloudstorage.CloudSetupMode
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/** Settings → Content discovery → Cloud storage: the list of accounts. */
@Composable
internal fun CloudStorageSettingsContent(
    initialFocusRequester: FocusRequester?,
    onOpenAccount: (String) -> Unit,
    viewModel: CloudStorageViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val accounts = state.accounts
    val focusModifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
    CloudErrorToast(viewModel)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_cloud_storage),
            subtitle = stringResource(R.string.settings_cloud_storage_subtitle)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "cloud_accounts_label") { ServerSectionLabel(stringResource(R.string.cloud_section_accounts)) }
                    if (accounts.isEmpty()) {
                        item(key = "cloud_empty") { CloudHint(stringResource(R.string.cloud_empty)) }
                    }
                    items(accounts, key = { "cloud_${it.id}" }) { account ->
                        SettingsActionRow(
                            title = account.name,
                            subtitle = account.statusText(syncing = account.id in state.syncing),
                            onClick = { onOpenAccount(account.id) },
                            leadingIcon = Icons.Default.Cloud,
                            modifier = if (account == accounts.first()) focusModifier else Modifier
                        )
                    }
                    item(key = "cloud_add_label") { ServerSectionLabel(stringResource(R.string.cloud_section_add)) }
                    item(key = "cloud_add") {
                        SettingsActionRow(
                            title = stringResource(R.string.cloud_add_from_phone),
                            subtitle = stringResource(R.string.cloud_add_from_phone_subtitle),
                            onClick = { viewModel.startSetup(CloudSetupMode.ACCOUNTS) },
                            leadingIcon = Icons.Default.QrCode,
                            modifier = if (accounts.isEmpty()) focusModifier else Modifier
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }
    CloudSetupOverlay(viewModel)
}

/** One account: switches, sync, folders, manual links and disconnect. */
@Composable
internal fun CloudAccountSettingsContent(
    accountId: String,
    initialFocusRequester: FocusRequester?,
    onBack: () -> Unit,
    viewModel: CloudStorageViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val account = state.accounts.firstOrNull { it.id == accountId }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var removeFolder by remember { mutableStateOf<String?>(null) }
    var removeLink by remember { mutableStateOf<String?>(null) }
    CloudErrorToast(viewModel)

    LaunchedEffect(account == null) { if (account == null) onBack() }
    if (account == null) return

    if (browse != null) {
        CloudFolderBrowser(viewModel)
        return
    }
    val focusModifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = account.name,
            subtitle = listOfNotNull(
                account.kind.displayName,
                account.statusText(syncing = account.id in state.syncing),
                if (account.kind == CloudProviderKind.TERABOX) stringResource(R.string.cloud_terabox_experimental) else null
            ).joinToString(" · ")
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "enabled") {
                        SettingsToggleRow(
                            title = stringResource(R.string.cloud_enabled),
                            subtitle = stringResource(R.string.cloud_enabled_subtitle),
                            checked = account.enabled,
                            onToggle = { viewModel.setEnabled(account.id, !account.enabled) },
                            modifier = focusModifier
                        )
                    }
                    item(key = "show_first") {
                        SettingsToggleRow(
                            title = stringResource(R.string.cloud_show_first),
                            subtitle = stringResource(R.string.cloud_show_first_subtitle),
                            checked = account.showFirst,
                            onToggle = { viewModel.setShowFirst(account.id, !account.showFirst) }
                        )
                    }
                    item(key = "sync") {
                        SettingsActionRow(
                            title = stringResource(R.string.cloud_sync_now),
                            subtitle = stringResource(R.string.cloud_sync_now_subtitle),
                            onClick = { viewModel.syncNow(account.id) },
                            leadingIcon = Icons.Default.Sync,
                            enabled = account.id !in state.syncing
                        )
                    }
                    item(key = "speed") {
                        val running = speed.containsKey(account.id) && speed[account.id] == null
                        SettingsActionRow(
                            title = stringResource(R.string.cloud_speed_test),
                            subtitle = when {
                                running -> stringResource(R.string.cloud_speed_running)
                                speed[account.id] != null -> speed[account.id]
                                else -> stringResource(R.string.cloud_speed_test_subtitle)
                            },
                            onClick = { viewModel.speedTest(account.id) },
                            leadingIcon = Icons.Default.Speed,
                            enabled = !running && account.indexedFiles > 0
                        )
                    }
                    item(key = "folders_label") { ServerSectionLabel(stringResource(R.string.cloud_section_folders)) }
                    items(account.folders, key = { "folder_${it.id}" }) { folder ->
                        SettingsActionRow(
                            title = if (folder.path == "/") stringResource(R.string.cloud_folder_root) else folder.path,
                            subtitle = stringResource(R.string.cloud_folder_remove_hint),
                            onClick = { removeFolder = folder.id },
                            leadingIcon = Icons.Default.Folder
                        )
                    }
                    item(key = "add_folder") {
                        SettingsActionRow(
                            title = stringResource(R.string.cloud_add_folder),
                            subtitle = stringResource(R.string.cloud_add_folder_subtitle),
                            onClick = { viewModel.openBrowser(account.id) },
                            leadingIcon = Icons.Default.Add
                        )
                    }
                    item(key = "links_label") { ServerSectionLabel(stringResource(R.string.cloud_section_links)) }
                    if (account.manualLinks.isEmpty()) {
                        item(key = "links_empty") { CloudHint(stringResource(R.string.cloud_links_empty)) }
                    }
                    items(account.manualLinks, key = { "link_${it.fileId}" }) { link ->
                        val season = link.season
                        val episode = link.episode
                        SettingsActionRow(
                            title = if (season != null && episode != null) {
                                stringResource(R.string.cloud_link_value, link.contentId, season, episode)
                            } else {
                                link.contentId
                            },
                            subtitle = stringResource(R.string.cloud_link_remove_hint),
                            onClick = { removeLink = link.fileId },
                            leadingIcon = Icons.Default.Link
                        )
                    }
                    item(key = "link_phone") {
                        SettingsActionRow(
                            title = stringResource(R.string.cloud_link_from_phone),
                            subtitle = stringResource(R.string.cloud_link_from_phone_subtitle),
                            onClick = { viewModel.startSetup(CloudSetupMode.LINK, account.id) },
                            leadingIcon = Icons.Default.QrCode
                        )
                    }
                    item(key = "disconnect") {
                        SettingsActionRow(
                            title = stringResource(R.string.cloud_disconnect),
                            subtitle = stringResource(R.string.cloud_disconnect_subtitle),
                            onClick = { confirmDisconnect = true },
                            leadingIcon = Icons.Default.Delete
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }
    CloudSetupOverlay(viewModel)

    if (confirmDisconnect) {
        CloudConfirmDialog(
            title = stringResource(R.string.cloud_disconnect_confirm_title),
            message = stringResource(R.string.cloud_disconnect_confirm_message, account.name),
            action = stringResource(R.string.cloud_disconnect),
            onDismiss = { confirmDisconnect = false },
            onConfirm = {
                confirmDisconnect = false
                viewModel.disconnect(account.id, onBack)
            }
        )
    }
    removeFolder?.let { folderId ->
        CloudConfirmDialog(
            title = stringResource(R.string.cloud_remove_folder_title),
            message = account.folders.firstOrNull { it.id == folderId }?.path.orEmpty(),
            action = stringResource(R.string.cloud_remove),
            onDismiss = { removeFolder = null },
            onConfirm = {
                removeFolder = null
                viewModel.removeFolder(account.id, folderId)
            }
        )
    }
    removeLink?.let { fileId ->
        CloudConfirmDialog(
            title = stringResource(R.string.cloud_remove_link_title),
            message = account.manualLinks.firstOrNull { it.fileId == fileId }?.contentId.orEmpty(),
            action = stringResource(R.string.cloud_remove),
            onDismiss = { removeLink = null },
            onConfirm = {
                removeLink = null
                viewModel.removeLink(account.id, fileId)
            }
        )
    }
}

@Composable
private fun CloudFolderBrowser(viewModel: CloudStorageViewModel) {
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    val current = browse ?: return
    val firstRequester = remember { FocusRequester() }
    BackHandler { if (!viewModel.upOneLevel()) viewModel.closeBrowser() }
    LaunchedEffect(current.folder, current.loading) { runCatching { firstRequester.requestFocus() } }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.cloud_add_folder),
            subtitle = current.folder?.path ?: "/"
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (current.folder != null) {
                        item(key = "add_this") {
                            SettingsActionRow(
                                title = stringResource(R.string.cloud_browse_add_this),
                                subtitle = current.folder.path,
                                onClick = viewModel::addCurrentFolder,
                                leadingIcon = Icons.Default.Add,
                                modifier = Modifier.focusRequester(firstRequester)
                            )
                        }
                    }
                    item(key = "up") {
                        SettingsActionRow(
                            title = stringResource(R.string.cloud_browse_up),
                            subtitle = null,
                            onClick = { if (!viewModel.upOneLevel()) viewModel.closeBrowser() },
                            leadingIcon = Icons.Default.ArrowUpward,
                            modifier = if (current.folder == null) Modifier.focusRequester(firstRequester) else Modifier
                        )
                    }
                    when {
                        current.loading -> item(key = "loading") { CloudHint(stringResource(R.string.cloud_browse_loading)) }
                        current.failed -> item(key = "failed") { CloudHint(stringResource(R.string.cloud_browse_failed)) }
                        current.entries.isEmpty() -> item(key = "empty") { CloudHint(stringResource(R.string.cloud_browse_empty)) }
                    }
                    items(current.entries, key = { "entry_${it.id}" }) { entry ->
                        SettingsActionRow(
                            title = entry.name,
                            subtitle = null,
                            onClick = { viewModel.enterFolder(entry) },
                            leadingIcon = Icons.Default.Folder
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }
}

/** The QR, the URL and the one-time code, while the phone page is open. */
@Composable
private fun CloudSetupOverlay(viewModel: CloudStorageViewModel) {
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    val current = setup ?: return
    val closeRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeRequester.requestFocus() } }
    Dialog(
        onDismissRequest = viewModel::stopSetup,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.cloud_qr_instruction),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                current.qrBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_qr_code),
                        modifier = Modifier.size(240.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                Text(text = current.url, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextTertiary)
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                Text(
                    text = stringResource(R.string.cloud_qr_code, current.code.chunked(3).joinToString(" ")),
                    color = NuvioTheme.colors.TextPrimary,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold
                )
                current.message?.let {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.Secondary)
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                SettingsDialogActionButton(
                    text = stringResource(R.string.cloud_qr_close),
                    onClick = viewModel::stopSetup,
                    primary = true,
                    modifier = Modifier.focusRequester(closeRequester)
                )
            }
        }
    }
}

@Composable
private fun CloudConfirmDialog(
    title: String,
    message: String,
    action: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    NuvioDialog(onDismiss = onDismiss, title = title, subtitle = message) {
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
            SettingsDialogActionButton(text = action, onClick = onConfirm, primary = true)
        }
    }
}

@Composable
private fun CloudErrorToast(viewModel: CloudStorageViewModel) {
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(error) {
        error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }
}

@Composable
private fun CloudHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary,
        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm)
    )
}

@Composable
private fun CloudAccount.statusText(syncing: Boolean): String {
    val parts = mutableListOf<String>()
    if (!enabled) parts += stringResource(R.string.cloud_status_disabled)
    when {
        syncing -> parts += stringResource(R.string.cloud_status_syncing)
        lastSyncEpochMs == null -> parts += stringResource(R.string.cloud_status_never)
        else -> {
            parts += stringResource(R.string.cloud_status_files, indexedFiles)
            parts += stringResource(
                R.string.cloud_status_synced,
                DateUtils.getRelativeTimeSpanString(lastSyncEpochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            )
        }
    }
    when (lastError) {
        null -> Unit
        CloudFailure.AUTH.name -> parts += stringResource(R.string.cloud_status_error_auth)
        "INCOMPLETE" -> parts += stringResource(R.string.cloud_status_incomplete)
        else -> parts += stringResource(R.string.cloud_status_error)
    }
    if (truncated) parts += stringResource(R.string.cloud_status_truncated)
    return parts.joinToString(" · ")
}
