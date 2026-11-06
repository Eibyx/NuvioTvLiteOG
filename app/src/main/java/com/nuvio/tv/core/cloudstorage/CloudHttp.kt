package com.nuvio.tv.core.cloudstorage

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class CloudHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val form: Map<String, String>? = null,
    val jsonBody: String? = null
) {
    /** Header values and the query string can hold cookies or tokens: never print them. */
    override fun toString(): String = "CloudHttpRequest($method ${url.substringBefore('?')}, headers=${headers.keys})"
}

internal class CloudHttpResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    val isSuccess: Boolean get() = status in 200..299
}

/** The HTTP the cloud providers use, so tests can answer requests without a network. */
internal fun interface CloudHttp {
    suspend fun send(request: CloudHttpRequest): CloudHttpResponse
}

internal class OkHttpCloudHttp(private val client: OkHttpClient) : CloudHttp {
    override suspend fun send(request: CloudHttpRequest): CloudHttpResponse = withContext(Dispatchers.IO) {
        val body = request.form?.let { form ->
            FormBody.Builder().apply { form.forEach { (key, value) -> add(key, value) } }.build()
        } ?: request.jsonBody?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val okRequest = Request.Builder()
            .url(request.url)
            .apply { request.headers.forEach { (name, value) -> header(name, value) } }
            .method(request.method, body)
            .build()
        client.newCall(okRequest).execute().use { response ->
            val source = response.body?.source()
            val limit = MAX_BODY_BYTES
            val text = if (source == null) "" else {
                // A provider answer the size of a page of listings; refuse anything absurd.
                if (!source.request(limit + 1)) source.buffer.readUtf8() else throw IOException("Response too large")
            }
            CloudHttpResponse(response.code, response.headers.toMultimap(), text)
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 8L * 1024 * 1024
    }
}

internal fun cloudUrl(base: String, path: String = "", query: List<Pair<String, String>> = emptyList()): String =
    (base.trimEnd('/') + path).toHttpUrl().newBuilder().apply {
        query.forEach { (key, value) -> addQueryParameter(key, value) }
    }.build().toString()
