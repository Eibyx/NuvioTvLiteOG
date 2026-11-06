package com.nuvio.tv.core.cloudstorage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeraBoxApiTest {
    @Test
    fun signMatchesRcloneExtra() {
        // Reference values from rclone-extra's Go `sign` (backend/terabox/util.go), run unchanged.
        assertEquals("o+eJTSHSmkvmd/6mMzqlR7UqTccpqV/lrn9toYq3CW/evlLL20/eyw==", teraBoxSign("d7f6b6a1c2", "1f9e8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f"))
        assertEquals("B4fMPajv0eHpCoA=", teraBoxSign("k", "hello world"))
    }

    @Test
    fun cookieAcceptsFullStringOrNdus() {
        assertEquals("ndus=abc123; lang=en", teraBoxCookie("abc123"))
        assertEquals("ndus=abc123; lang=en", teraBoxCookie("  \"abc123\" "))
        assertEquals("ndus=abc; csrfToken=x", teraBoxCookie("ndus=abc; csrfToken=x"))
    }

    @Test
    fun onlyTeraBoxDomainsAreAccepted() {
        assertEquals("https://www.terabox.com", teraBoxOrigin("www.terabox.com"))
        assertEquals("https://www.1024tera.com", teraBoxOrigin("https://www.1024tera.com/"))
        assertNull(teraBoxOrigin("https://evil.example.com"))
        assertNull(teraBoxOrigin("https://terabox.com.evil.example"))
        assertNull(teraBoxOrigin("http://127.0.0.1"))
    }

    @Test
    fun requestsCarryTheSameHeadersAndParametersAsRcloneExtra() = runTest {
        val http = FakeCloudHttp { json("""{"errno":0}""") }
        TeraBoxApi(http, "secretndus").checkLogin()
        val request = http.requests.single()
        assertTrue(request.url.startsWith("https://www.terabox.com/api/check/login?"))
        assertEquals("250528", request.url.query("app_id"))
        assertEquals("dubox", request.url.query("channel"))
        assertEquals("0", request.url.query("clienttype"))
        assertEquals("ndus=secretndus; lang=en", request.headers["Cookie"])
        assertEquals(TeraBoxApi.USER_AGENT, request.headers["User-Agent"])
        assertEquals("https://www.terabox.com", request.headers["Referer"])
        assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
        // Nothing secret when a request is printed.
        assertTrue("secretndus" !in request.toString())
    }

    @Test
    fun listReadsEveryPage() = runTest {
        val http = FakeCloudHttp { request ->
            val page = request.url.query("page")!!.toInt()
            val count = if (page == 1) 100 else 3
            val items = (1..count).joinToString(",") { i ->
                """{"fs_id":${page * 1000 + i},"server_filename":"Ep $i.mkv","path":"/ANIME/Ep $i.mkv","size":10,"isdir":0,"category":1,"server_mtime":5}"""
            }
            json("""{"errno":0,"list":[$items]}""")
        }
        val items = TeraBoxApi(http, "c").list("ANIME")
        assertEquals(103, items.size)
        assertEquals(2, http.requests.size)
        assertEquals("%2FANIME", http.requests.first().url.query("dir"))
    }

    @Test
    fun jsTokenIsFetchedAndSentAfterError4000023() = runTest {
        var saved: String? = null
        val http = FakeCloudHttp { request ->
            when {
                !request.url.contains("/api/") -> json("""<script>`function%20fn%28a%29%7Bwindow.jsToken%20%3D%20a%7D%3Bfn%28%22TOKEN123%22%29`</script>""")
                request.url.query("jsToken") == "TOKEN123" -> json("""{"errno":0}""")
                else -> json("""{"errno":4000023}""")
            }
        }
        TeraBoxApi(http, "c", onSessionChanged = { token, _ -> saved = token }).checkLogin()
        assertEquals("TOKEN123", saved)
        assertEquals(3, http.requests.size)
    }

    @Test
    fun baseUrlFollowsUrlDomainPrefix() = runTest {
        var base: String? = null
        val http = FakeCloudHttp { request ->
            if (request.url.startsWith("https://dm.terabox.com")) {
                json("""{"errno":0}""")
            } else {
                json("""{"errno":-6}""", headers = mapOf("Url-Domain-Prefix" to listOf("dm")))
            }
        }
        TeraBoxApi(http, "c", onSessionChanged = { _, url -> base = url }).checkLogin()
        assertEquals("https://dm.terabox.com", base)
    }

    @Test
    fun rejectedSessionIsAnAuthFailure() = runTest {
        val http = FakeCloudHttp { json("""{"errno":-6}""") }
        val error = assertFailsWith<CloudStorageException> { TeraBoxApi(http, "c").checkLogin() }
        assertEquals(CloudFailure.AUTH, error.failure)
    }

    @Test
    fun serverErrorsAreRetriedTwice() = runTest {
        var calls = 0
        val http = FakeCloudHttp { calls++; if (calls < 3) json("", status = 503) else json("""{"errno":0}""") }
        TeraBoxApi(http, "c", sleep = {}).checkLogin()
        assertEquals(3, calls)
    }

    @Test
    fun downloadLinkFallsBackToTheSignedDownloadApi() = runTest {
        val http = FakeCloudHttp { request ->
            when {
                "/api/filemetas" in request.url -> json("""{"errno":0,"info":[{"errno":0,"fs_id":7,"server_filename":"a.mp4","path":"/a.mp4","size":1}]}""")
                "/api/home/info" in request.url -> json("""{"errno":0,"data":{"sign1":"hello world","sign3":"k","timestamp":1}}""")
                "/api/download" in request.url -> json("""{"errno":0,"dlink":[{"fs_id":"7","dlink":"https://d.terabox.com/file/abc"}]}""")
                else -> json("""{"errno":0}""")
            }
        }
        val link = TeraBoxApi(http, "c", nowEpochSec = { 1700000000 }).downloadLink("/a.mp4", 7)
        assertEquals("https://d.terabox.com/file/abc", link)
        val download = http.requests.last()
        assertEquals("dlink", download.url.query("type"))
        assertEquals("2", download.url.query("vip"))
        assertEquals("%5B7%5D", download.url.query("fidlist"))
        assertEquals("1700000000", download.url.query("timestamp"))
        assertEquals("B4fMPajv0eHpCoA%3D", download.url.query("sign"))
    }

    @Test
    fun downloadLinkFromFileMetasWhenPresent() = runTest {
        val http = FakeCloudHttp { json("""{"errno":0,"info":[{"errno":0,"fs_id":7,"server_filename":"a.mp4","path":"/a.mp4","size":1,"dlink":"https://d.terabox.com/x"}]}""") }
        assertEquals("https://d.terabox.com/x", TeraBoxApi(http, "c").downloadLink("/a.mp4", 7))
        assertEquals(1, http.requests.size)
    }
}
