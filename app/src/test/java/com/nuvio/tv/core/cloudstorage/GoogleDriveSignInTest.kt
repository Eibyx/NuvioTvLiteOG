package com.nuvio.tv.core.cloudstorage

import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GoogleDriveSignInTest {
    private val clientId = "1234567890-abcdefghijklmnop.apps.googleusercontent.com"

    private fun query(url: String, name: String): String? =
        url.substringAfter('?').split('&').firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }

    @Test
    fun consentPageAsksOnlyForReadOnlyDriveWithPkce() {
        val url = GoogleDriveSignIn(FakeCloudHttp { json("{}") }).start(clientId, "fake-secret")
        assertTrue(url.startsWith(GoogleDriveApi.AUTH_URL))
        assertEquals(GoogleDriveApi.SCOPE, query(url, "scope"))
        assertEquals(GoogleDriveSignIn.REDIRECT_URI, query(url, "redirect_uri"))
        assertEquals("S256", query(url, "code_challenge_method"))
        assertEquals("offline", query(url, "access_type"))
        assertFalse(url.contains("fake-secret"))
    }

    @Test
    fun pastedAddressIsExchangedWithTheVerifier() = runTest {
        val http = FakeCloudHttp { json("""{"access_token":"a","refresh_token":"fake-refresh"}""") }
        val signIn = GoogleDriveSignIn(http)
        val url = signIn.start(clientId, "fake-secret")
        val state = query(url, "state")!!
        val result = signIn.finish("http://127.0.0.1:53682/?state=$state&code=4%2F0Afake&scope=x")
        assertEquals("fake-refresh", result.refreshToken)
        val form = http.requests.single().form!!
        assertEquals("4/0Afake", form["code"])
        assertEquals("authorization_code", form["grant_type"])
        assertEquals(query(url, "code_challenge"), GoogleDriveSignIn.challenge(form["code_verifier"]!!))
        // The verifier never left the TV.
        assertFalse(url.contains(form["code_verifier"]!!))
    }

    @Test
    fun aBareCodeWorksWhenOneSignInIsWaiting() = runTest {
        val signIn = GoogleDriveSignIn(FakeCloudHttp { json("""{"refresh_token":"fake-refresh"}""") })
        signIn.start(clientId, null)
        assertEquals("fake-refresh", signIn.finish("4/0Afake").refreshToken)
    }

    @Test
    fun wrongStateCancelledConsentAndGoogleErrorsAreExplained() = runTest {
        val signIn = GoogleDriveSignIn(FakeCloudHttp { json("""{"error":"invalid_grant"}""", 400) })
        signIn.start(clientId, null)
        assertEquals("state_mismatch", assertFailsWith<CloudStorageException> {
            signIn.finish("http://127.0.0.1:53682/?state=other&code=x")
        }.detail)
        assertEquals("access_denied", assertFailsWith<CloudStorageException> {
            signIn.finish("http://127.0.0.1:53682/?error=access_denied")
        }.detail)
        val url = signIn.start(clientId, null)
        assertEquals("invalid_grant", assertFailsWith<CloudStorageException> {
            signIn.finish("http://127.0.0.1:53682/?state=${query(url, "state")}&code=x")
        }.detail)
        assertEquals("bad_client_id", assertFailsWith<CloudStorageException> { signIn.start("not-a-client", null) }.detail)
    }

    @Test
    fun driveApiDisabledIsReported() = runTest {
        val http = FakeCloudHttp { request ->
            if (request.url.contains("oauth2")) json("""{"access_token":"a","expires_in":3600}""")
            else json("""{"error":{"code":403,"errors":[{"reason":"accessNotConfigured"}]}}""", 403)
        }
        val api = GoogleDriveApi(http, clientId, null, "fake-refresh")
        assertEquals("drive_api_disabled", assertFailsWith<CloudStorageException> { api.accountName() }.detail)
    }
}
