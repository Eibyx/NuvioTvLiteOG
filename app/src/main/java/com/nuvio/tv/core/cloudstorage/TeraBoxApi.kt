package com.nuvio.tv.core.cloudstorage

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * TeraBox web API, ported from the rclone-extra backend that Round Sync Extra runs
 * (gulp79/rclone-extra `backend/terabox`, taken from x1arch/rclone `backend/terabox`, MIT,
 * Copyright (C) 2012 Nick Craig-Wood and the rclone authors).
 *
 * The requests are the same: same endpoints, query parameters (`app_id`, `channel`, `clienttype`,
 * `jsToken`), headers (`Cookie`, `Referer`, `X-Requested-With`, the TeraBox for Windows
 * `User-Agent`), `jsToken` refresh on errno 4000023/450016, base URL switch on errno -6 with
 * `Url-Domain-Prefix`, retries on 429/5xx, and download links from `/api/filemetas?dlink=1` or
 * the signed `/api/download`.
 *
 * Read only: nothing here creates, moves or deletes files.
 */
internal class TeraBoxApi(
    private val http: CloudHttp,
    cookie: String,
    domain: String? = null,
    jsToken: String? = null,
    baseUrl: String? = null,
    private val onSessionChanged: (jsToken: String?, baseUrl: String?) -> Unit = { _, _ -> },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val cookie: String = teraBoxCookie(cookie)
    private val domain: String = teraBoxOrigin(domain) ?: DEFAULT_DOMAIN
    private var jsToken: String? = jsToken?.takeIf { it.isNotBlank() }
    private var baseUrl: String = teraBoxOrigin(baseUrl) ?: this.domain

    /** The headers rclone-extra's client sends with every request, the download link included. */
    val requestHeaders: Map<String, String>
        get() = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Referer" to domain,
            "X-Requested-With" to "XMLHttpRequest",
            "Cookie" to cookie,
            "User-Agent" to USER_AGENT,
        )

    suspend fun checkLogin() {
        call("/api/check/login")
    }

    /** One folder, every page (100 entries each, as rclone-extra asks). */
    suspend fun list(path: String): List<TeraBoxItem> {
        val items = mutableListOf<TeraBoxItem>()
        var page = 1
        while (true) {
            val response = call(
                "/api/list",
                listOf("dir" to teraBoxPath(path), "page" to page.toString(), "num" to PAGE_SIZE.toString()),
            )
            val pageItems = response["list"]?.let { runCatching { it.jsonArray }.getOrNull() }
                .orEmpty()
                .mapNotNull { (it as? JsonObject)?.toTeraBoxItem() }
            items += pageItems
            if (pageItems.size < PAGE_SIZE || page >= MAX_PAGES) break
            page++
        }
        return items
    }

    /**
     * A fresh download link for a file: `/api/filemetas?dlink=1` first, then the signed
     * `/api/download`, in the order rclone-extra's `Object.Open` tries them.
     */
    suspend fun downloadLink(path: String, fsId: Long): String {
        runCatching { fileMeta(path)?.downloadLink }
            .onFailure { if (it is CloudStorageException && it.failure == CloudFailure.AUTH) throw it }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val sign = call("/api/home/info")["data"] as? JsonObject
            ?: throw CloudStorageException(CloudFailure.FAILED)
        val sign1 = sign.string("sign1") ?: throw CloudStorageException(CloudFailure.FAILED)
        val sign3 = sign.string("sign3") ?: throw CloudStorageException(CloudFailure.FAILED)
        val response = call(
            "/api/download",
            listOf(
                "type" to "dlink",
                "vip" to "2",
                "sign" to teraBoxSign(sign3, sign1),
                "timestamp" to nowEpochSec().toString(),
                "need_speed" to "1",
                "fidlist" to "[$fsId]",
            ),
        )
        return (response["dlink"] as? JsonArray)
            ?.firstOrNull()
            ?.let { (it as? JsonObject)?.string("dlink") }
            ?.takeIf { it.isNotBlank() }
            ?: throw CloudStorageException(CloudFailure.NOT_FOUND)
    }

    suspend fun fileMeta(path: String): TeraBoxItem? {
        val response = call(
            "/api/filemetas",
            listOf("target" to JsonArray(listOf(JsonPrimitive(teraBoxPath(path)))).toString(), "dlink" to "1"),
            checkErrno = false,
        )
        val first = (response["info"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (first != null) {
            teraBoxError(first.int("errno") ?: 0)?.let { throw it }
            return first.toTeraBoxItem()
        }
        teraBoxError(response.int("errno") ?: 0)?.let { throw it }
        return null
    }

    private suspend fun call(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        checkErrno: Boolean = true,
    ): JsonObject {
        var retry = 0
        var jsTokenRequested = false
        var baseSwitched = false
        while (true) {
            val response = try {
                http.send(CloudHttpRequest("GET", cloudUrl(baseUrl, path, query + commonQuery()), requestHeaders))
            } catch (error: CloudStorageException) {
                throw error
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                throw CloudStorageException(CloudFailure.NETWORK, error)
            }
            if (!response.isSuccess) {
                if (response.status in RETRY_STATUS && retry < MAX_RETRIES) {
                    retry++
                    sleep(retry * 1_000L)
                    continue
                }
                throw CloudStorageException(
                    when (response.status) {
                        401, 403 -> CloudFailure.AUTH
                        404 -> CloudFailure.NOT_FOUND
                        429 -> CloudFailure.RATE_LIMITED
                        else -> CloudFailure.FAILED
                    },
                )
            }
            val json = runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrNull()
                ?: throw CloudStorageException(CloudFailure.FAILED)
            val errno = json.int("errno") ?: 0
            if ((errno == 4000023 || errno == 450016) && !jsTokenRequested) {
                jsTokenRequested = true
                refreshJsToken()
                continue
            }
            val prefix = response.header("Url-Domain-Prefix")
            if (errno == -6 && !prefix.isNullOrBlank() && !baseSwitched) {
                baseSwitched = true
                baseUrl = teraBoxPrefixedOrigin(prefix, domain) ?: throw CloudStorageException(CloudFailure.AUTH)
                onSessionChanged(jsToken, baseUrl)
                continue
            }
            if (checkErrno) teraBoxError(errno)?.let { throw it }
            return json
        }
    }

    private suspend fun refreshJsToken() {
        val response = http.send(CloudHttpRequest("GET", baseUrl, requestHeaders))
        val token = response.body.substringBetween(JS_TOKEN_START, JS_TOKEN_END)
            ?: throw CloudStorageException(CloudFailure.AUTH)
        jsToken = token
        onSessionChanged(jsToken, baseUrl)
    }

    private fun commonQuery(): List<Pair<String, String>> = buildList {
        add("app_id" to "250528")
        add("channel" to "dubox")
        add("clienttype" to "0")
        jsToken?.let { add("jsToken" to it) }
    }

    companion object {
        const val DEFAULT_DOMAIN = "https://www.terabox.com"
        const val USER_AGENT = "terabox;1.42.2.150;PC;PC-Windows;10.0.22631;WindowsTeraBox"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 200
        private const val MAX_RETRIES = 2
        private val RETRY_STATUS = setOf(429, 500, 502, 503, 504, 509)
        private const val JS_TOKEN_START = "`function%20fn%28a%29%7Bwindow.jsToken%20%3D%20a%7D%3Bfn%28%22"
        private const val JS_TOKEN_END = "%22%29`"
    }
}

internal data class TeraBoxItem(
    val fsId: Long,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val isDir: Boolean,
    val category: Int,
    val modifiedEpochSec: Long,
    val downloadLink: String? = null,
)

/** TeraBox domains a session may talk to: the setting cannot point requests anywhere else. */
private val TERABOX_HOSTS = setOf(
    "terabox.com", "terabox.app", "teraboxapp.com", "1024tera.com", "1024terabox.com", "terabox1024.com",
    "freeterabox.com", "4funbox.com", "mirrobox.com", "momerybox.com", "nephobox.com", "tibibox.com",
    "teraboxlink.com", "teraboxshare.com",
)

internal fun isTeraBoxHost(host: String): Boolean {
    val normalized = host.lowercase().trimEnd('.')
    return TERABOX_HOSTS.any { normalized == it || normalized.endsWith(".$it") }
}

/** `https://<host>` for an allowed TeraBox host, or null. */
internal fun teraBoxOrigin(value: String?): String? {
    val trimmed = value?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
    val withScheme = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) trimmed else "https://$trimmed"
    val host = withScheme.substringAfter("://").substringBefore('/').substringBefore(':')
    if (!isTeraBoxHost(host)) return null
    return "https://${host.lowercase()}"
}

private fun teraBoxPrefixedOrigin(prefix: String, domain: String): String? {
    if (!Regex("^[A-Za-z0-9-]{1,40}$").matches(prefix)) return null
    val hostParts = domain.substringAfter("://").split('.')
    if (hostParts.size < 2) return null
    return teraBoxOrigin("https://$prefix.${hostParts[hostParts.size - 2]}.${hostParts.last()}")
}

/** rclone-extra `valuedCookie`: a full cookie string, or a bare `ndus` value. */
internal fun teraBoxCookie(value: String): String {
    var cookie = value.trim()
    if (cookie.length > 2 && cookie.first() == '"' && cookie.last() == '"') cookie = cookie.substring(1, cookie.length - 1)
    val parts = cookie.split(';')
    if (parts.size == 1 && '=' !in parts[0]) return "ndus=${parts[0].trim()}; lang=en"
    return cookie
}

internal fun teraBoxPath(path: String): String = when {
    path.isEmpty() -> "/"
    path.startsWith('/') -> path
    else -> "/$path"
}

/** rclone-extra `sign`: an RC4 keystream over sign3 applied to sign1, base64-encoded. */
@OptIn(ExperimentalEncodingApi::class)
internal fun teraBoxSign(s1: String, s2: String): String {
    val key = s1.encodeToByteArray()
    val data = s2.encodeToByteArray()
    require(key.isNotEmpty())
    val a = IntArray(256) { key[it % key.size].toInt() and 0xFF }
    val p = IntArray(256) { it }
    var u = 0
    for (q in 0 until 256) {
        u = (u + p[q] + a[q]) % 256
        val t = p[q]; p[q] = p[u]; p[u] = t
    }
    var i = 0
    u = 0
    val out = ByteArray(data.size)
    for (q in data.indices) {
        i = (i + 1) % 256
        u = (u + p[i]) % 256
        val t = p[i]; p[i] = p[u]; p[u] = t
        val k = p[(p[i] + p[u]) % 256]
        out[q] = ((data[q].toInt() and 0xFF) xor k).toByte()
    }
    return Base64.Default.encode(out)
}

private fun teraBoxError(errno: Int): CloudStorageException? = when (errno) {
    0 -> null
    -6, -1, 104, 200002, 200003, 200004, 200005, 4000023, 450016 -> CloudStorageException(CloudFailure.AUTH)
    -9 -> CloudStorageException(CloudFailure.NOT_FOUND)
    else -> CloudStorageException(CloudFailure.FAILED)
}

private fun JsonObject.toTeraBoxItem(): TeraBoxItem? {
    val name = string("server_filename") ?: return null
    return TeraBoxItem(
        fsId = long("fs_id") ?: return null,
        name = name,
        path = string("path") ?: return null,
        sizeBytes = long("size") ?: 0L,
        isDir = (int("isdir") ?: 0) != 0,
        category = int("category") ?: 0,
        modifiedEpochSec = long("server_mtime") ?: 0L,
        downloadLink = string("dlink"),
    )
}

private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonElement) as? JsonPrimitive
private fun JsonObject.string(key: String): String? = primitive(key)?.contentOrNull
private fun JsonObject.long(key: String): Long? = primitive(key)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
private fun JsonObject.int(key: String): Int? = primitive(key)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

private fun String.substringBetween(start: String, end: String): String? {
    val from = indexOf(start).takeIf { it >= 0 }?.plus(start.length) ?: return null
    val to = indexOf(end, from).takeIf { it >= 0 } ?: return null
    return substring(from, to).takeIf { it.isNotBlank() }
}
