// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0) and adapted to NuvioTvLiteOG.
package com.nuvio.tv.ui.screens.settings

import androidx.compose.material.icons.filled.Cloud
import android.Manifest
import android.content.pm.PackageManager
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Usb
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.cloudstorage.CloudProviderKind
import com.nuvio.tv.core.subtitlelibrary.LocalAccessMethod
import com.nuvio.tv.core.subtitlelibrary.LocalAccessPlanner
import com.nuvio.tv.core.subtitlelibrary.SubtitleFolder
import com.nuvio.tv.core.subtitlelibrary.SubtitleLibrarySettings
import com.nuvio.tv.core.subtitlelibrary.SubtitleOrigin

private enum class FoldersPage { MAIN, ORIGIN, LOCAL, FOLDER_ACTIONS }

/** Ajustes → Reproducción → Subtítulos → "Mis carpetas de subtítulos". */
@Composable
internal fun SubtitleFoldersDialog(onDismiss: () -> Unit) {
    val viewModel: SubtitleFoldersViewModel = hiltViewModel()
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val browser by viewModel.browser.collectAsStateWithLifecycle()
    val phone by viewModel.phone.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val cacheBytes by viewModel.cacheBytes.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(FoldersPage.MAIN) }
    var selected by remember { mutableStateOf<SubtitleFolder?>(null) }
    var adbHint by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.clearMessage() }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onSafPicked(result.data?.data)
        page = FoldersPage.MAIN
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.openFileBrowser(LocalAccessMethod.LEGACY_FILES)
        else Toast.makeText(context, context.getString(R.string.subtitle_library_permission_denied), Toast.LENGTH_SHORT).show()
    }
    val allFilesScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (viewModel.hasAllFilesAccess()) viewModel.openFileBrowser(LocalAccessMethod.ALL_FILES)
        else Toast.makeText(context, context.getString(R.string.subtitle_library_permission_denied), Toast.LENGTH_SHORT).show()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center
        ) {
            Column(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(32.dp)) {
                val current = browser
                when {
                    phone != null -> PhoneUploadPanel(phone!!.url, phone!!.qr, phone!!.received) { viewModel.stopPhoneUpload() }
                    current != null -> FolderBrowserPanel(current, viewModel)
                    else -> {
                        BackHandler(enabled = page != FoldersPage.MAIN) { page = FoldersPage.MAIN; adbHint = false }
                        when (page) {
                            FoldersPage.MAIN -> MainPage(settings, cacheBytes, viewModel,
                                onAdd = { page = FoldersPage.ORIGIN },
                                onFolder = { selected = it; page = FoldersPage.FOLDER_ACTIONS })
                            FoldersPage.ORIGIN -> OriginPage(viewModel,
                                onCloud = { account -> viewModel.openCloudBrowser(account); page = FoldersPage.MAIN },
                                onLocal = { page = FoldersPage.LOCAL })
                            FoldersPage.LOCAL -> LocalPage(viewModel, adbHint,
                                onSaf = { target -> runCatching { treePicker.launch(target) } },
                                onLegacy = {
                                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                                        PackageManager.PERMISSION_GRANTED
                                    if (granted) viewModel.openFileBrowser(LocalAccessMethod.LEGACY_FILES)
                                    else storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                },
                                onAllFiles = { needsAdb ->
                                    when {
                                        viewModel.hasAllFilesAccess() -> viewModel.openFileBrowser(LocalAccessMethod.ALL_FILES)
                                        needsAdb -> adbHint = true
                                        else -> runCatching { allFilesScreen.launch(viewModel.allFilesSettingsIntent()) }
                                    }
                                },
                                onPhone = { viewModel.startPhoneUpload(); page = FoldersPage.MAIN })
                            FoldersPage.FOLDER_ACTIONS -> selected?.let { folder ->
                                FolderActionsPage(folder, viewModel) { page = FoldersPage.MAIN }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun firstFocus(): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    return Modifier.focusRequester(requester)
}

@Composable
private fun MainPage(
    settings: SubtitleLibrarySettings,
    cacheBytes: Long,
    viewModel: SubtitleFoldersViewModel,
    onAdd: () -> Unit,
    onFolder: (SubtitleFolder) -> Unit
) {
    val context = LocalContext.current
    SettingsDetailHeader(
        title = stringResource(R.string.subtitle_library_settings_title),
        subtitle = stringResource(R.string.subtitle_library_settings_desc)
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item(key = "add") {
            SettingsActionRow(
                title = stringResource(R.string.subtitle_library_add),
                subtitle = null,
                leadingIcon = Icons.Default.Add,
                onClick = onAdd,
                modifier = firstFocus()
            )
        }
        if (settings.folders.isEmpty()) {
            item(key = "none") { Text(stringResource(R.string.subtitle_library_none), color = Color.White.copy(alpha = 0.6f)) }
        }
        items(settings.folders, key = { it.id }) { folder ->
            SettingsActionRow(
                title = folder.displayName,
                subtitle = folderSubtitle(folder),
                value = stringResource(if (folder.enabled) R.string.subtitle_library_enabled else R.string.subtitle_library_disabled),
                leadingRawIconRes = folderLogo(folder),
                leadingIcon = if (folderLogo(folder) == null) folderIcon(folder) else null,
                onClick = { onFolder(folder) }
            )
        }
        item(key = "auto") {
            SettingsToggleRow(
                title = stringResource(R.string.subtitle_library_auto_select),
                subtitle = stringResource(R.string.subtitle_library_auto_select_desc),
                checked = settings.autoSelect,
                onToggle = { viewModel.setAutoSelect(!settings.autoSelect) }
            )
        }
        item(key = "cache") {
            val options = SubtitleLibrarySettings.CACHE_LIMIT_OPTIONS_MB
            SettingsActionRow(
                title = stringResource(R.string.subtitle_library_cache_limit),
                subtitle = null,
                value = stringResource(R.string.subtitle_library_cache_value, settings.cacheLimitMb, Formatter.formatShortFileSize(context, cacheBytes)),
                onClick = {
                    val next = options.getOrNull(options.indexOf(settings.cacheLimitMb) + 1) ?: options.first()
                    viewModel.setCacheLimit(next)
                }
            )
        }
        item(key = "clear") {
            SettingsActionRow(
                title = stringResource(R.string.subtitle_library_clear_cache),
                subtitle = null,
                leadingIcon = Icons.Default.Delete,
                onClick = viewModel::clearCache
            )
        }
    }
}

@Composable
private fun folderSubtitle(folder: SubtitleFolder): String {
    val origin = when (folder.originKind) {
        SubtitleOrigin.TERABOX -> stringResource(R.string.subtitle_library_origin_terabox_long)
        SubtitleOrigin.DRIVE -> stringResource(R.string.subtitle_library_origin_drive_long)
        else -> stringResource(R.string.subtitle_library_origin_local_long)
    }
    val path = folder.folderPath.takeIf { it.isNotBlank() && folder.accessMethod != LocalAccessMethod.SAF }
    return listOfNotNull(origin, path).joinToString(" · ")
}

// LiteOG ships no TeraBox/Drive logos: cloud folders use the generic cloud icon instead.
private fun folderLogo(folder: SubtitleFolder): Int? = null

private fun folderIcon(folder: SubtitleFolder) =
    if (folder.accessMethod == LocalAccessMethod.PHONE_UPLOAD) Icons.Default.PhoneAndroid else Icons.Default.Usb

@Composable
private fun OriginPage(
    viewModel: SubtitleFoldersViewModel,
    onCloud: (com.nuvio.tv.core.cloudstorage.CloudAccount) -> Unit,
    onLocal: () -> Unit
) {
    SettingsDetailHeader(title = stringResource(R.string.subtitle_library_add), subtitle = stringResource(R.string.subtitle_library_choose_origin))
    val terabox = viewModel.accounts(CloudProviderKind.TERABOX)
    val drive = viewModel.accounts(CloudProviderKind.GOOGLE_DRIVE)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item(key = "local") {
            SettingsActionRow(
                title = stringResource(R.string.subtitle_library_origin_local_long),
                subtitle = null,
                leadingIcon = Icons.Default.Usb,
                onClick = onLocal,
                modifier = firstFocus()
            )
        }
        if (terabox.isEmpty()) {
            item(key = "terabox-none") {
                SettingsActionRow(
                    title = stringResource(R.string.subtitle_library_origin_terabox_long),
                    subtitle = stringResource(R.string.subtitle_library_connect_account),
                    leadingIcon = androidx.compose.material.icons.Icons.Default.Cloud,
                    enabled = false,
                    onClick = {}
                )
            }
        }
        items(terabox, key = { "tb-${it.id}" }) { account ->
            SettingsActionRow(
                title = stringResource(R.string.subtitle_library_origin_terabox_long),
                subtitle = account.name,
                leadingIcon = androidx.compose.material.icons.Icons.Default.Cloud,
                onClick = { onCloud(account) }
            )
        }
        if (drive.isEmpty()) {
            item(key = "drive-none") {
                SettingsActionRow(
                    title = stringResource(R.string.subtitle_library_origin_drive_long),
                    subtitle = stringResource(R.string.subtitle_library_connect_account),
                    leadingIcon = androidx.compose.material.icons.Icons.Default.Cloud,
                    enabled = false,
                    onClick = {}
                )
            }
        }
        items(drive, key = { "gd-${it.id}" }) { account ->
            SettingsActionRow(
                title = stringResource(R.string.subtitle_library_origin_drive_long),
                subtitle = account.name,
                leadingIcon = androidx.compose.material.icons.Icons.Default.Cloud,
                onClick = { onCloud(account) }
            )
        }
    }
}

@Composable
private fun LocalPage(
    viewModel: SubtitleFoldersViewModel,
    adbHint: Boolean,
    onSaf: (android.content.Intent) -> Unit,
    onLegacy: () -> Unit,
    onAllFiles: (needsAdb: Boolean) -> Unit,
    onPhone: () -> Unit
) {
    SettingsDetailHeader(title = stringResource(R.string.subtitle_library_origin_local_long), subtitle = stringResource(R.string.subtitle_library_local_method_title))
    val options = remember { viewModel.localOptions() }
    val safTargets = remember { viewModel.safTargets() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        var focused = false
        options.forEach { option ->
            when (option.method) {
                LocalAccessMethod.SAF -> items(safTargets, key = { "saf-${it.label}" }) { target ->
                    SettingsActionRow(
                        title = target.label?.let { stringResource(R.string.subtitle_library_method_saf_volume, it) }
                            ?: stringResource(R.string.subtitle_library_method_saf),
                        subtitle = null,
                        leadingIcon = Icons.Default.Folder,
                        onClick = { onSaf(target.intent) },
                        modifier = if (target == safTargets.first()) firstFocus() else Modifier
                    )
                }.also { focused = true }
                LocalAccessMethod.LEGACY_FILES -> item(key = "legacy") {
                    SettingsActionRow(
                        title = stringResource(R.string.subtitle_library_method_legacy),
                        subtitle = null,
                        leadingIcon = Icons.Default.Folder,
                        onClick = onLegacy,
                        modifier = firstFocus()
                    )
                }.also { focused = true }
                LocalAccessMethod.ALL_FILES -> item(key = "allfiles") {
                    SettingsActionRow(
                        title = stringResource(R.string.subtitle_library_method_all_files),
                        subtitle = if (adbHint || option.needsAdb) {
                            stringResource(R.string.subtitle_library_method_adb, LocalAccessPlanner.ADB_GRANT_COMMAND)
                        } else {
                            null
                        },
                        leadingIcon = Icons.Default.Folder,
                        onClick = { onAllFiles(option.needsAdb) },
                        modifier = firstFocus()
                    )
                }.also { focused = true }
                LocalAccessMethod.PHONE_UPLOAD -> item(key = "phone") {
                    SettingsActionRow(
                        title = stringResource(R.string.subtitle_library_method_phone),
                        subtitle = null,
                        leadingIcon = Icons.Default.PhoneAndroid,
                        onClick = onPhone,
                        modifier = if (!focused) firstFocus() else Modifier
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderActionsPage(folder: SubtitleFolder, viewModel: SubtitleFoldersViewModel, onDone: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val current = settings.folders.firstOrNull { it.id == folder.id } ?: run { onDone(); return }
    SettingsDetailHeader(title = current.displayName, subtitle = folderSubtitle(current))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item(key = "enabled") {
            SettingsToggleRow(
                title = stringResource(if (current.enabled) R.string.subtitle_library_enabled else R.string.subtitle_library_disabled),
                subtitle = null,
                checked = current.enabled,
                onToggle = { viewModel.setEnabled(current, !current.enabled) },
                modifier = firstFocus()
            )
        }
        item(key = "refresh") {
            SettingsActionRow(title = stringResource(R.string.subtitle_library_refresh), subtitle = null, leadingIcon = Icons.Default.Sync, onClick = { viewModel.refresh(current) })
        }
        if (current.accessMethod == LocalAccessMethod.PHONE_UPLOAD) {
            item(key = "phone") {
                SettingsActionRow(title = stringResource(R.string.subtitle_library_method_phone), subtitle = null, leadingIcon = Icons.Default.PhoneAndroid, onClick = viewModel::startPhoneUpload)
            }
        }
        item(key = "remove") {
            SettingsActionRow(
                title = stringResource(R.string.subtitle_library_remove),
                subtitle = null,
                leadingIcon = Icons.Default.Delete,
                onClick = { viewModel.remove(current); onDone() }
            )
        }
    }
}

@Composable
private fun FolderBrowserPanel(state: SubtitleFolderBrowserState, viewModel: SubtitleFoldersViewModel) {
    BackHandler { if (!viewModel.up()) viewModel.closeBrowser() }
    val title = state.stack.lastOrNull()?.third ?: stringResource(R.string.subtitle_library_storage_roots)
    SettingsDetailHeader(title = stringResource(R.string.subtitle_library_add), subtitle = title)
    val requester = remember { FocusRequester() }
    LaunchedEffect(state.stack.size, state.loading) { runCatching { requester.requestFocus() } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        if (state.stack.isNotEmpty()) {
            item(key = "use") {
                SettingsActionRow(
                    title = stringResource(R.string.subtitle_library_pick_this),
                    subtitle = state.stack.last().second,
                    leadingIcon = Icons.Default.Add,
                    onClick = viewModel::useCurrentFolder,
                    modifier = Modifier.focusRequester(requester)
                )
            }
        }
        item(key = "up") {
            SettingsActionRow(
                title = stringResource(R.string.subtitle_library_browser_up),
                subtitle = null,
                leadingIcon = Icons.Default.ArrowUpward,
                onClick = { if (!viewModel.up()) viewModel.closeBrowser() },
                modifier = if (state.stack.isEmpty()) Modifier.focusRequester(requester) else Modifier
            )
        }
        when {
            state.loading -> item(key = "loading") { Text(stringResource(R.string.subtitle_library_loading), color = Color.White.copy(alpha = 0.6f)) }
            state.failed -> item(key = "failed") { Text(stringResource(R.string.subtitle_library_read_failed), color = Color.White.copy(alpha = 0.6f)) }
            state.folders.isEmpty() -> item(key = "empty") { Text(stringResource(R.string.subtitle_library_browser_empty), color = Color.White.copy(alpha = 0.6f)) }
        }
        if (!state.loading) {
            items(state.folders, key = { "f-${it.first}" }) { folder ->
                SettingsActionRow(title = folder.second, subtitle = null, leadingIcon = Icons.Default.Folder, onClick = { viewModel.enter(folder) })
            }
        }
    }
}

@Composable
private fun PhoneUploadPanel(url: String, qr: android.graphics.Bitmap?, received: Int, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.subtitle_library_phone_title), style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Text(
            stringResource(R.string.subtitle_library_phone_steps),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        qr?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(260.dp)) }
        Text(stringResource(R.string.subtitle_library_phone_address, url), color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 12.dp))
        Text(stringResource(R.string.subtitle_library_phone_received, received), color = Color.White, modifier = Modifier.padding(vertical = 12.dp))
        SettingsActionRow(title = stringResource(R.string.subtitle_library_done), subtitle = null, onClick = onClose, modifier = firstFocus())
    }
}
