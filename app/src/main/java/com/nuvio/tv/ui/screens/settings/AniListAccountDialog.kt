@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.data.anilist.AniListTvState
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AniListAccountDialog(state: AniListTvState, onConnect: () -> Unit, onDisconnect: () -> Unit,
    onSync: () -> Unit, onDismiss: () -> Unit) {
    val qr by produceState<android.graphics.Bitmap?>(null, state.authorizationUrl) {
        value = state.authorizationUrl?.let { url -> withContext(Dispatchers.Default) {
            runCatching { QrCodeGenerator.generate(url, 640, margin = 4) }.getOrNull()
        } }
    }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.anilist_name),
        subtitle = stringResource(R.string.anilist_description), width = 620.dp, suppressFirstKeyUp = false) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (state.connected) stringResource(R.string.anilist_connected_as, state.accountName.orEmpty())
                else stringResource(R.string.anilist_disconnected), color = NuvioTheme.colors.TextSecondary)
            if (!state.configured) Text(stringResource(R.string.anilist_missing_config))
            state.error?.let { Text(it) }
            qr?.let { bitmap ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(bitmap.asImageBitmap(), stringResource(R.string.anilist_qr_description), Modifier.size(220.dp))
                    Text(stringResource(R.string.anilist_phone_instructions), Modifier.weight(1f))
                }
            }
            state.lastSyncAt?.let { Text(stringResource(R.string.anilist_last_sync,
                DateFormat.getDateTimeInstance().format(Date(it)))) }
            if (state.pending > 0) Text(stringResource(R.string.anilist_pending, state.pending))
            if (state.syncing) Text(stringResource(R.string.anilist_syncing))
        }
        SettingsDialogActionRow {
                SettingsDialogActionButton(stringResource(R.string.action_close), onDismiss)
                if (state.connected) {
                    SettingsDialogActionButton(stringResource(R.string.anilist_sync_now), onSync)
                    SettingsDialogActionButton(stringResource(R.string.anilist_disconnect), onDisconnect)
                } else if (state.configured && state.authorizationUrl == null) {
                    SettingsDialogActionButton(stringResource(R.string.anilist_connect), onConnect, primary = true)
                }
        }
    }
}
