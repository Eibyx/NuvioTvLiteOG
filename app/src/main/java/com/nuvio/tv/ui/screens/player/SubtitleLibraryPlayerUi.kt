// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0) and adapted to NuvioTvLiteOG.
package com.nuvio.tv.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.subtitlelibrary.SubtitleOrigin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import com.nuvio.tv.ui.theme.NuvioTheme

/** The "Mis carpetas de subtítulos" rows under the languages of the subtitle menu. */
internal fun LazyListScope.subtitleLibraryMenuRows(menu: SubtitleLibraryMenuState, onEvent: (PlayerEvent) -> Unit) {
    item(key = "subtitle_library_header") {
        Text(
            text = stringResource(R.string.subtitle_library_menu_title),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 8.dp, top = 8.dp)
        )
    }
    if (menu.searching) {
        item(key = "subtitle_library_searching") {
            LibraryPanelRow(title = stringResource(R.string.subtitle_library_searching), selected = false, onClick = {})
        }
    }
    if (menu.usbDisconnected) {
        item(key = "subtitle_library_usb") {
            LibraryPanelRow(title = stringResource(R.string.subtitle_library_usb_disconnected), selected = false, onClick = {})
        }
    }
    if (menu.accountUnavailable) {
        item(key = "subtitle_library_account") {
            LibraryPanelRow(title = stringResource(R.string.subtitle_library_connect_account), selected = false, onClick = {})
        }
    }
    if (menu.libassOffForFonts) {
        item(key = "subtitle_library_libass") {
            LibraryPanelRow(
                title = stringResource(R.string.subtitle_library_enable_libass),
                titleMaxLines = 2,
                selected = false,
                onClick = { onEvent(PlayerEvent.OnSubtitleLibrary(SubtitleLibraryAction.EnableLibass)) }
            )
        }
    }
    if (menu.canBrowse && !menu.searching) {
        item(key = "subtitle_library_browse") {
            LibraryPanelRow(
                title = stringResource(R.string.subtitle_library_browse),
                selected = false,
                onClick = { onEvent(PlayerEvent.OnSubtitleLibrary(SubtitleLibraryAction.OpenBrowser(SubtitleLibraryBrowserMode.PICK_FILE))) }
            )
        }
    }
    if (menu.needsLink && !menu.searching) {
        item(key = "subtitle_library_link") {
            LibraryPanelRow(
                title = stringResource(R.string.subtitle_library_link),
                selected = false,
                onClick = { onEvent(PlayerEvent.OnSubtitleLibrary(SubtitleLibraryAction.OpenBrowser(SubtitleLibraryBrowserMode.LINK_FOLDER))) }
            )
        }
    }
}

@Composable
internal fun originLabel(origin: SubtitleOrigin?): String = stringResource(
    when (origin) {
        SubtitleOrigin.TERABOX -> R.string.subtitle_library_origin_terabox_long
        SubtitleOrigin.DRIVE -> R.string.subtitle_library_origin_drive_long
        else -> R.string.subtitle_library_origin_local_long
    }
)

/** "Buscar en mis carpetas…" / "Vincular carpeta…": a D-pad folder browser over the configured roots. */
@Composable
internal fun SubtitleLibraryBrowserOverlay(
    state: SubtitleLibraryBrowserState?,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    fun send(action: SubtitleLibraryAction) = onEvent(PlayerEvent.OnSubtitleLibrary(action))
    PlayerOverlayScaffold(
        visible = state != null,
        onDismiss = { send(SubtitleLibraryAction.Dismiss) },
        modifier = modifier,
        captureKeys = false,
        contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 28.dp)
    ) {
        val current = state ?: return@PlayerOverlayScaffold
        BackHandler(enabled = true) { send(SubtitleLibraryAction.Up) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(current.root?.id, current.path.size, current.loading) {
            kotlinx.coroutines.delay(80)
            runCatching { focus.requestFocus() }
        }
        Column(
            modifier = Modifier
                .width(400.dp)
                .align(Alignment.BottomEnd)
                .heightIn(max = 620.dp)
                .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(NuvioTheme.radii.lg))
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            LibraryPanelEyebrow(
                text = when (current.mode) {
                    SubtitleLibraryBrowserMode.PICK_FILE -> stringResource(R.string.subtitle_library_browse)
                    SubtitleLibraryBrowserMode.LINK_FOLDER -> stringResource(R.string.subtitle_library_link)
                }
            )
            val title = current.root?.let { root ->
                (listOf(root.displayName) + current.path.map { it.name }).joinToString(" / ")
            } ?: stringResource(R.string.subtitle_library_browser_choose_root)
            Text(text = title, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 2)
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)
            ) {
                if (current.root == null) {
                    items(current.roots, key = { "root:${it.id}" }) { root ->
                        LibraryPanelRow(
                            title = root.displayName,
                            subtitle = originLabel(root.originKind),
                            selected = false,
                            onClick = { send(SubtitleLibraryAction.SelectRoot(root.id)) },
                            focusRequester = if (root == current.roots.first()) focus else null
                        )
                    }
                    return@LazyColumn
                }
                item(key = "up") {
                    LibraryPanelRow(
                        title = stringResource(R.string.subtitle_library_browser_up),
                        selected = false,
                        onClick = { send(SubtitleLibraryAction.Up) },
                        focusRequester = if (current.entries.isEmpty() || current.loading) focus else null
                    )
                }
                if (current.mode == SubtitleLibraryBrowserMode.LINK_FOLDER && current.path.isNotEmpty()) {
                    item(key = "link") {
                        LibraryPanelRow(
                            title = stringResource(R.string.subtitle_library_link_this),
                            subtitle = current.path.last().name,
                            selected = false,
                            onClick = { send(SubtitleLibraryAction.LinkCurrentFolder) }
                        )
                    }
                }
                when {
                    current.loading -> item(key = "loading") {
                        LibraryPanelRow(title = stringResource(R.string.subtitle_library_loading), selected = false, onClick = {})
                    }
                    current.error != null -> item(key = "error") {
                        LibraryPanelRow(title = current.error, titleMaxLines = 2, selected = false, onClick = {})
                    }
                    current.entries.isEmpty() -> item(key = "empty") {
                        LibraryPanelRow(title = stringResource(R.string.subtitle_library_browser_empty), selected = false, onClick = {})
                    }
                    else -> {
                        val shown = if (current.mode == SubtitleLibraryBrowserMode.LINK_FOLDER) current.entries.filter { it.isFolder } else current.entries
                        items(shown, key = { "entry:${it.id}" }) { entry ->
                            LibraryPanelRow(
                                title = if (entry.isFolder) "📁 ${entry.name}" else entry.name,
                                titleMaxLines = 2,
                                selected = false,
                                onClick = { send(SubtitleLibraryAction.OpenEntry(entry)) },
                                focusRequester = if (entry == shown.first()) focus else null
                            )
                        }
                    }
                }
            }
        }
    }
}


/** A focusable row of the subtitle menu and the folder browser, styled like LiteOG's menu cards. */
@Composable
internal fun LibraryPanelRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    titleMaxLines: Int = 1,
    focusRequester: FocusRequester? = null
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.Secondary else Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.16f)
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        border = CardDefaults.border(
            border = Border(border = BorderStroke(NuvioTheme.spacing.xxs, Color.Transparent), shape = RoundedCornerShape(NuvioTheme.radii.md)),
            focusedBorder = Border(border = BorderStroke(NuvioTheme.spacing.xxs, Color.White), shape = RoundedCornerShape(NuvioTheme.radii.md))
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = NuvioTheme.spacing.sm)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LibraryPanelEyebrow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = NuvioTheme.colors.TextTertiary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}
