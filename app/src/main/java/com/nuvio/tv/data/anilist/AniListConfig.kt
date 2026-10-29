package com.nuvio.tv.data.anilist

import com.nuvio.tv.BuildConfig
import java.net.URI

object AniListConfig {
    const val AUTHORIZE_URL = "https://anilist.co/api/v2/oauth/authorize"
    const val GRAPHQL_URL = "https://graphql.anilist.co"
    const val ARM_BASE_URL = "https://arm.haglund.dev/api/v2"
    val clientId: String get() = BuildConfig.ANILIST_CLIENT_ID
    val redirectUri: String get() = BuildConfig.ANILIST_REDIRECT_URI
    val isConfigured: Boolean get() = validClient(clientId, redirectUri)

    internal fun validClient(clientId: String, redirect: String): Boolean =
        clientId.isNotBlank() && clientId.all(Char::isDigit) && runCatching {
            val uri = URI(redirect)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawFragment == null && uri.userInfo == null
        }.getOrDefault(false)
}
