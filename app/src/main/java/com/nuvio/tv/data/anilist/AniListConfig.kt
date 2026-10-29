package com.nuvio.tv.data.anilist

import com.nuvio.tv.BuildConfig
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object AniListConfig {
    const val AUTHORIZE_URL = "https://anilist.co/api/v2/oauth/authorize"
    const val GRAPHQL_URL = "https://graphql.anilist.co"
    const val ARM_BASE_URL = "https://arm.haglund.dev/api/v2"
    /** Public client registered for NuvioMobileKai; its only redirect is the app scheme below. */
    const val MOBILE_CLIENT_ID = "53329"
    const val MOBILE_REDIRECT_URI = "nuvio://auth/anilist"
    val clientId: String get() = BuildConfig.ANILIST_CLIENT_ID
    val redirectUri: String get() = BuildConfig.ANILIST_REDIRECT_URI
    val isConfigured: Boolean get() = validClient(clientId, redirectUri)
    /** HTTPS callbacks use the phone QR page; the Mobile app-scheme callback is caught by the TV browser. */
    val usesPhonePairing: Boolean get() = isHttps(redirectUri)

    internal fun validClient(clientId: String, redirect: String): Boolean =
        clientId.isNotBlank() && clientId.all(Char::isDigit) && (isHttps(redirect) || isAppScheme(redirect))

    private fun isHttps(redirect: String) = runCatching {
        val uri = URI(redirect)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawFragment == null && uri.userInfo == null
    }.getOrDefault(false)

    private fun isAppScheme(redirect: String) = runCatching {
        val uri = URI(redirect)
        uri.scheme == "nuvio" && !uri.host.isNullOrBlank() && uri.rawFragment == null && uri.rawQuery == null
    }.getOrDefault(false)

    /** Same request Mobile sends: AniList redirects to the client's single registered URI. */
    internal fun tvBrowserAuthorizeUrl(clientId: String = this.clientId): String =
        "$AUTHORIZE_URL?client_id=${URLEncoder.encode(clientId, "UTF-8")}&response_type=token"
}

internal sealed interface AniListCallback {
    data class Token(val token: String, val expiresInSeconds: Long?) : AniListCallback
    data object Denied : AniListCallback
    data object Invalid : AniListCallback
}

/** Mobile's `handleAuthCallback` rules; returns null when [url] is not the registered redirect. */
internal fun parseAniListCallback(url: String, redirectUri: String): AniListCallback? {
    val trimmed = url.trim()
    if (!trimmed.startsWith(redirectUri, ignoreCase = true)) return null
    val remainder = trimmed.substring(redirectUri.length)
    if (remainder.isNotEmpty() && remainder.first() !in setOf('#', '?', '/')) return null
    val parameters = remainder.trimStart('/', '?', '#').split('&', '#').mapNotNull { part ->
        val key = part.substringBefore('=', "").takeIf(String::isNotBlank) ?: return@mapNotNull null
        runCatching { key to URLDecoder.decode(part.substringAfter('='), "UTF-8") }.getOrNull()
    }.toMap()
    if (parameters["error"] != null) return AniListCallback.Denied
    val token = parameters["access_token"]?.takeIf { it.length in 16..16_384 && it.none { c -> c.isWhitespace() || c.isISOControl() } }
        ?: return AniListCallback.Invalid
    return AniListCallback.Token(token, parameters["expires_in"]?.toLongOrNull()?.takeIf { it in 1..31_622_400 })
}
