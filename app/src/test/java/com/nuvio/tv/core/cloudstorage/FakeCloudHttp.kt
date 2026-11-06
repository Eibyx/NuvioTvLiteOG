package com.nuvio.tv.core.cloudstorage

/** Answers requests from a list of rules, records what was asked. */
internal class FakeCloudHttp(private val handler: (CloudHttpRequest) -> CloudHttpResponse) : CloudHttp {
    val requests = mutableListOf<CloudHttpRequest>()

    override suspend fun send(request: CloudHttpRequest): CloudHttpResponse {
        requests += request
        return handler(request)
    }
}

internal fun json(body: String, status: Int = 200, headers: Map<String, List<String>> = emptyMap()) =
    CloudHttpResponse(status, headers, body)

internal fun String.query(name: String): String? =
    substringAfter('?', "").split('&').firstOrNull { it.substringBefore('=') == name }?.substringAfter('=')
