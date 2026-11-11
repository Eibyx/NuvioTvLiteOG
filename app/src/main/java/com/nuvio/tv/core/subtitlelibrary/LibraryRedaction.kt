// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0).
package com.nuvio.tv.core.subtitlelibrary

import java.net.URI

/**
 * What subtitle-folder code may write to the log about a link or a request. Signed download links
 * (TeraBox dlinks, Drive media URLs) can carry the credential in the path or the query, so only
 * the scheme and host survive; headers are reduced to their names.
 */
internal object LibraryRedaction {
    fun url(value: String?): String {
        if (value.isNullOrBlank()) return "<none>"
        val parsed = runCatching { URI(value.trim()) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        return when {
            parsed == null || scheme == null -> "<redacted>"
            scheme == "file" || scheme == "content" -> "$scheme://<local>"
            parsed.host == null -> "$scheme://<redacted>"
            else -> "$scheme://${parsed.host}/<redacted>"
        }
    }

    fun headers(headers: Map<String, String>?): String =
        headers.orEmpty().keys.sorted().joinToString(prefix = "[", postfix = "]")

    /** Any text that may contain a link (an exception message): every URL in it is reduced by [url]. */
    fun text(message: String?): String {
        if (message.isNullOrEmpty()) return ""
        return URL_PATTERN.replace(message) { url(it.value) }
            .replace(BEARER_PATTERN, "Bearer <redacted>")
            .replace(COOKIE_PATTERN, "$1<redacted>")
    }

    private val URL_PATTERN = Regex("""(?i)\b(?:https?|content|file)://[^\s"'<>]+""")
    private val BEARER_PATTERN = Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]+""")
    private val COOKIE_PATTERN = Regex("""(?i)((?:cookie|ndus|BDUSS|STOKEN|jsToken|access_token|refresh_token)\s*[=:]\s*)[^;\s,&]+""")
}
