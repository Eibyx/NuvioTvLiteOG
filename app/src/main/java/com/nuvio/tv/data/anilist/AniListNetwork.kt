package com.nuvio.tv.data.anilist

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.IOException
import java.util.logging.Logger

// Independent API transport. No playback client, interceptors, cache or decoder settings change.
internal fun createApiHttpClient() = HttpClient(OkHttp) {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 25_000; connectTimeoutMillis = 10_000 }
}

internal suspend fun readBoundedResponseBody(channel: ByteReadChannel, contentLength: Long?, maxBytes: Int = 16 * 1024 * 1024): String {
    if (contentLength != null && contentLength > maxBytes) throw IOException("AniList response exceeds size limit")
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = channel.readAvailable(buffer, 0, buffer.size)
        if (n < 0) break
        if (out.size() + n > maxBytes) throw IOException("AniList response exceeds size limit")
        out.write(buffer, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}

internal fun <K, V> boundedAniListCache(): MutableMap<K, V> = object : LinkedHashMap<K, V>(48, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > 48
}

// Do not log transport exception messages: they can contain authenticated URLs or tokens.
internal object InAppLogger {
    fun info(tag: String, message: String) { Logger.getLogger(tag).info(message) }
    fun debug(tag: String, message: String) { Logger.getLogger(tag).fine(message) }
    fun warn(tag: String, message: String) { Logger.getLogger(tag).warning(message) }
    fun error(tag: String, message: String) { Logger.getLogger(tag).severe(message) }
    fun throwableSummary(error: Throwable): String = error.javaClass.simpleName
}
