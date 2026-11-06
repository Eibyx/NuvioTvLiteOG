@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Power
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun ContentDiscoverySettingsContent(
    onNavigateToAddons: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    showPlugins: Boolean,
    initialFocusRequester: FocusRequester?
) {
    var serverPage by rememberSaveable { mutableStateOf<String?>(null) }
    var focusPluginsOnReturn by rememberSaveable { mutableStateOf(false) }
    val hubServersRequester = remember { FocusRequester() }
    val serversRequester = remember { FocusRequester() }
    val serverRequester = remember { FocusRequester() }
    val hubCloudRequester = remember { FocusRequester() }
    val cloudRequester = remember { FocusRequester() }
    val cloudAccountRequester = remember { FocusRequester() }
    var returningToHub by remember { mutableStateOf(false) }
    var returningFromCloud by remember { mutableStateOf(false) }

    BackHandler(enabled = serverPage != null) {
        val page = serverPage
        serverPage = when {
            page == SERVERS_PAGE || page == CLOUD_PAGE -> null
            page?.startsWith(CLOUD_ACCOUNT_PREFIX) == true -> CLOUD_PAGE
            else -> SERVERS_PAGE
        }
        if (serverPage == null) {
            returningToHub = true
            returningFromCloud = page == CLOUD_PAGE
        }
    }

    LaunchedEffect(serverPage) {
        val page = serverPage
        val requester = when {
            page == null -> (if (returningFromCloud) hubCloudRequester else hubServersRequester).takeIf { returningToHub }
            page == SERVERS_PAGE -> serversRequester
            page == CLOUD_PAGE -> cloudRequester
            page.startsWith(CLOUD_ACCOUNT_PREFIX) -> cloudAccountRequester
            else -> serverRequester
        } ?: return@LaunchedEffect
        returningToHub = false
        runCatching { requester.requestFocus() }
    }

    when (val page = serverPage) {
        null -> ContentDiscoveryHub(
            onNavigateToAddons = {
                focusPluginsOnReturn = false
                onNavigateToAddons()
            },
            onNavigateToPlugins = {
                focusPluginsOnReturn = true
                onNavigateToPlugins()
            },
            onNavigateToServers = { serverPage = SERVERS_PAGE },
            onNavigateToCloud = { serverPage = CLOUD_PAGE },
            showPlugins = showPlugins,
            initialFocusRequester = initialFocusRequester,
            focusPluginsOnReturn = focusPluginsOnReturn && showPlugins,
            serversFocusRequester = hubServersRequester,
            cloudFocusRequester = hubCloudRequester
        )
        CLOUD_PAGE -> CloudStorageSettingsContent(
            initialFocusRequester = cloudRequester,
            onOpenAccount = { serverPage = CLOUD_ACCOUNT_PREFIX + it }
        )
        SERVERS_PAGE -> MediaServersSettingsContent(
            initialFocusRequester = serversRequester,
            onOpenServer = { serverPage = it }
        )
        else -> if (page.startsWith(CLOUD_ACCOUNT_PREFIX)) {
            CloudAccountSettingsContent(
                accountId = page.removePrefix(CLOUD_ACCOUNT_PREFIX),
                initialFocusRequester = cloudAccountRequester,
                onBack = { serverPage = CLOUD_PAGE }
            )
        } else MediaServerSettingsContent(
            connectionId = page,
            initialFocusRequester = serverRequester,
            onOpenServer = { serverPage = it },
            onBack = { serverPage = SERVERS_PAGE }
        )
    }
}

@Composable
private fun ContentDiscoveryHub(
    onNavigateToAddons: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToServers: () -> Unit,
    onNavigateToCloud: () -> Unit,
    showPlugins: Boolean,
    initialFocusRequester: FocusRequester?,
    focusPluginsOnReturn: Boolean,
    serversFocusRequester: FocusRequester,
    cloudFocusRequester: FocusRequester
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_content_discovery),
            subtitle = stringResource(R.string.settings_content_discovery_subtitle)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = stringResource(R.string.addon_title),
                subtitle = stringResource(R.string.settings_content_discovery_addons_subtitle),
                onClick = onNavigateToAddons,
                leadingIcon = Icons.Default.Extension,
                modifier = if (initialFocusRequester != null && !focusPluginsOnReturn) {
                    Modifier.focusRequester(initialFocusRequester)
                } else {
                    Modifier
                }
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_media_servers),
                subtitle = stringResource(R.string.settings_media_servers_subtitle),
                onClick = onNavigateToServers,
                leadingIcon = Icons.Default.Dns,
                modifier = Modifier.focusRequester(serversFocusRequester)
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_cloud_storage),
                subtitle = stringResource(R.string.settings_cloud_storage_subtitle),
                onClick = onNavigateToCloud,
                leadingIcon = Icons.Default.Cloud,
                modifier = Modifier.focusRequester(cloudFocusRequester)
            )
            if (showPlugins) {
                SettingsActionRow(
                    title = stringResource(R.string.plugin_title),
                    subtitle = stringResource(R.string.settings_content_discovery_plugins_subtitle),
                    onClick = onNavigateToPlugins,
                    leadingIcon = Icons.Default.Power,
                    modifier = if (initialFocusRequester != null && focusPluginsOnReturn) {
                        Modifier.focusRequester(initialFocusRequester)
                    } else {
                        Modifier
                    }
                )
            }
        }
    }
}

private const val SERVERS_PAGE = "servers"
private const val CLOUD_PAGE = "cloud"
private const val CLOUD_ACCOUNT_PREFIX = "cloud-account:"
