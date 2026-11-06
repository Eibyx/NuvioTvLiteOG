package com.nuvio.tv.core.cloudstorage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleDriveApiTest {
    private fun api(http: CloudHttp, now: Long = 1_000_000L, onToken: (String, Long) -> Unit = { _, _ -> }) =
        GoogleDriveApi(http, "client", "secret", "refresh", onTokenRefreshed = onToken, nowMs = { now })

    @Test
    fun refreshesTheAccessTokenWithTheUsersClient() = runTest {
        var stored: Pair<String, Long>? = null
        val http = FakeCloudHttp { request ->
            if (request.url == GoogleDriveApi.TOKEN_URL) json("""{"access_token":"at1","expires_in":3600}""")
            else json("""{"user":{"emailAddress":"me@example.com"}}""")
        }
        val name = api(http, onToken = { token, expiry -> stored = token to expiry }).accountName()
        assertEquals("me@example.com", name)
        val token = http.requests.first()
        assertEquals("refresh_token", token.form?.get("grant_type"))
        assertEquals("client", token.form?.get("client_id"))
        assertEquals("Bearer at1", http.requests.last().headers["Authorization"])
        assertEquals("at1" to 1_000_000L + 3_600_000L, stored)
        assertTrue("at1" !in http.requests.last().toString())
    }

    @Test
    fun aRejectedRefreshTokenIsAnAuthFailure() = runTest {
        val http = FakeCloudHttp { json("""{"error":"invalid_grant"}""", status = 400) }
        val error = assertFailsWith<CloudStorageException> { api(http).accountName() }
        assertEquals(CloudFailure.AUTH, error.failure)
    }

    @Test
    fun aRevokedAccessTokenIsRefreshedOnce() = runTest {
        var tokens = 0
        val http = FakeCloudHttp { request ->
            when {
                request.url == GoogleDriveApi.TOKEN_URL -> { tokens++; json("""{"access_token":"at$tokens","expires_in":3600}""") }
                request.headers["Authorization"] == "Bearer at1" -> json("", status = 401)
                else -> json("""{"user":{"displayName":"Me"}}""")
            }
        }
        assertEquals("Me", api(http).accountName())
        assertEquals(2, tokens)
    }

    @Test
    fun childrenReadsEveryPage() = runTest {
        val http = FakeCloudHttp { request ->
            when {
                request.url == GoogleDriveApi.TOKEN_URL -> json("""{"access_token":"at","expires_in":3600}""")
                request.url.query("pageToken") == null -> json("""{"nextPageToken":"p2","files":[{"id":"a","name":"A.mkv","mimeType":"video/x-matroska","size":"5","modifiedTime":"2026-01-01T00:00:00Z","videoMediaMetadata":{"width":1920,"height":1080,"durationMillis":"1427000"}}]}""")
                else -> json("""{"files":[{"id":"f","name":"Season 2","mimeType":"application/vnd.google-apps.folder"}]}""")
            }
        }
        val items = api(http).children("root")
        assertEquals(listOf("a", "f"), items.map { it.id })
        assertEquals(1080, items.first().height)
        assertEquals(1_427_000L, items.first().durationMs)
        assertTrue(items.last().isFolder)
    }

    @Test
    fun incrementalSyncAppliesChangesWithoutWalkingAgain() = runTest {
        val http = FakeCloudHttp { request ->
            when {
                request.url == GoogleDriveApi.TOKEN_URL -> json("""{"access_token":"at","expires_in":3600}""")
                "/changes?" in request.url -> json(
                    """{"newStartPageToken":"c2","changes":[
                        {"fileId":"new","file":{"id":"new","name":"Show - 02.mkv","mimeType":"video/x-matroska","size":"9","parents":["folder"]}},
                        {"fileId":"old","removed":true},
                        {"fileId":"elsewhere","file":{"id":"elsewhere","name":"x.mkv","mimeType":"video/x-matroska","parents":["other"]}}
                    ]}""",
                )
                else -> error("Unexpected ${request.url.substringBefore('?')}")
            }
        }
        val previous = CloudIndexSnapshot(
            files = listOf(CloudFile("old", "/Show/Show - 01.mkv", "Show - 01.mkv", 1, 0, "/Show")),
            folderIds = listOf("folder"),
            cursor = "c1",
            folderPaths = mapOf("folder" to "/Show"),
        )
        val snapshot = GoogleDriveCloudProvider(api(http)).index(listOf(CloudFolderRef("folder", "/Show")), previous)
        assertEquals(listOf("new"), snapshot.files.map { it.id })
        assertEquals("/Show/Show - 02.mkv", snapshot.files.single().path)
        assertEquals("c2", snapshot.cursor)
        assertNull(http.requests.firstOrNull { "/files?" in it.url })
    }

    @Test
    fun playbackUsesTheOfficialMediaEndpointWithABearerToken() = runTest {
        val http = FakeCloudHttp { json("""{"access_token":"at","expires_in":3600}""") }
        val source = GoogleDriveCloudProvider(api(http)).resolve(CloudFile("abc", "/a.mkv", "a.mkv", 10, 0, "/"), forceRefresh = false)
        assertTrue(source.url.startsWith("https://www.googleapis.com/drive/v3/files/abc?alt=media"))
        assertEquals("Bearer at", source.headers["Authorization"])
        assertTrue("Bearer" !in source.toString())
    }
}
