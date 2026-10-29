package com.nuvio.tv.data.anilist

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AniListTransportTest {
    private class Memory : AniListAuthPersistence {
        var value: String? = null
        override fun read(profileId: Int) = value
        override fun write(profileId: Int, value: String?) { this.value = value }
        override fun clear() { value = null }
    }
    private class Harness(bodies: List<String>, status: Int = 200) : AutoCloseable {
        val requests = mutableListOf<Request>()
        private val responses = ArrayDeque(bodies)
        val auth = AniListAuthStore(Memory(), now = { 1 }).apply { authorize("test-token-never-log", 999999999, scope()) }
        private val transport = HttpClient(OkHttp) { engine {
            preconfigured = OkHttpClient.Builder().addInterceptor { chain ->
                synchronized(requests) { requests.add(chain.request()) }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Test")
                    .body(responses.removeFirst().toResponseBody("application/json".toMediaType())).build()
            }.build()
        } }
        var now = 100_000L
        val api = AniListApiClient(auth, transport, now = { now.also { now += 1000 } })
        override fun close() = transport.close()
    }
    private fun current(progress: Int, status: String = "CURRENT") = """{"data":{"Media":{"id":1,"episodes":24,"mediaListEntry":{"id":2,"mediaId":1,"status":"$status","progress":$progress}}}}"""
    private fun saved(progress: Int) = """{"data":{"SaveMediaListEntry":{"id":2,"mediaId":1,"status":"CURRENT","progress":$progress}}}"""
    @Test fun episodeThirteenReadsRemoteTwelveThenWritesThirteen() = runBlocking {
        Harness(listOf(current(12), saved(13))).use { h ->
            val result = AniListProgressSender(h.api).send(h.auth.scope(), AniListTarget(1, 13))
            assertEquals(AniListDelivery.Sent("AniList 1", 13), result)
            assertEquals(2, h.requests.size)
            val body = okio.Buffer().also { h.requests.last().body!!.writeTo(it) }.readUtf8()
            assertTrue(body.contains("SaveMediaListEntry")); assertTrue(body.contains("\"progress\":13"))
        }
    }
    @Test fun remoteProgressNeverDecreases() = runBlocking {
        Harness(listOf(current(14))).use { h ->
            assertEquals(AniListDelivery.AlreadyUpToDate, AniListProgressSender(h.api).send(h.auth.scope(), AniListTarget(1, 13)))
            assertEquals(1, h.requests.size)
        }
    }
    @Test fun completedIsNeverWrittenAgain() = runBlocking {
        Harness(listOf(current(24, "COMPLETED"))).use { h ->
            assertEquals(AniListDelivery.AlreadyUpToDate, AniListProgressSender(h.api).send(h.auth.scope(), AniListTarget(1, 25)))
            assertEquals(1, h.requests.size)
        }
    }
    @Test fun publicMetadataLookupsNeverCarryAnAccountToken() = runBlocking {
        Harness(listOf(current(1))).use { h ->
            h.api.mediaById(null, 1)
            assertNull(h.requests.single().header("Authorization"))
        }
    }
    @Test fun revokedAuthorizationClearsOnlyAniListAndStopsWrites() = runBlocking {
        Harness(listOf("""{"errors":[{"message":"Unauthorized"}]}"""), 401).use { h ->
            try { h.api.viewer(h.auth.scope()); fail("Expected revoked authorization") } catch (_: AniListAuthException) { }
            assertFalse(h.auth.state.value.isAuthenticated)
            assertEquals(1, h.requests.size)
        }
    }
    @Test fun partialGraphqlErrorsAreNotTreatedAsSuccessfulMutations() = runBlocking {
        Harness(listOf("""{"data":{},"errors":[{"message":"Denied"}]}""")).use { h ->
            try { h.api.saveEntry(h.auth.scope(), 1, progress = 13); fail("Expected GraphQL failure") } catch (_: AniListApiException) { }
        }
    }
    @Test fun episodeBeyondFiniteEntryNeverCompletesAnEarlierSeasonByClamping() = runBlocking {
        Harness(listOf(current(12))).use { h ->
            val result = AniListProgressSender(h.api).send(h.auth.scope(), AniListTarget(1, 25))
            assertTrue(result is AniListDelivery.NotFound)
            assertEquals(1, h.requests.size)
        }
    }

}
