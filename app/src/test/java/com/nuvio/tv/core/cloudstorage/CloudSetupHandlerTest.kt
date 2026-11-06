package com.nuvio.tv.core.cloudstorage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

class CloudSetupHandlerTest {
    private class MemoryPersistence : CloudPersistence {
        val values = mutableMapOf<Int, String>()
        override fun read(profileId: Int) = values[profileId]
        override fun write(profileId: Int, value: String?) {
            if (value == null) values.remove(profileId) else values[profileId] = value
        }
        override fun clear() = values.clear()
    }

    private class MemoryIndex : CloudIndexStore {
        val values = mutableMapOf<String, String>()
        override fun read(profileId: Int, accountId: String) = values["$profileId/$accountId"]
        override fun write(profileId: Int, accountId: String, value: String) { values["$profileId/$accountId"] = value }
        override fun delete(profileId: Int, accountId: String) { values.remove("$profileId/$accountId") }
        override fun deleteProfile(profileId: Int) { values.keys.removeAll { it.startsWith("$profileId/") } }
        override fun clear() = values.clear()
    }

    private val fakeCookie = "fake-session-for-tests"
    private val http = FakeCloudHttp { request ->
        when {
            request.url.contains("/api/check/login") -> json("""{"errno":0}""")
            request.url.contains("/api/list") -> json("""{"errno":0,"list":[]}""")
            else -> json("{}", 404)
        }
    }

    private fun repository(persistence: CloudPersistence = MemoryPersistence(), index: CloudIndexStore = MemoryIndex()) =
        CloudStorageRepository(persistence, index, http, CoroutineScope(Dispatchers.Unconfined), CloudIndexLimits.LOW_RAM)

    @Test
    fun wrongCodesLockThePage() = runTest {
        var locked = false
        val handler = CloudSetupHandler(CloudSetupMode.ACCOUNTS, "123456", repository(), null, {}, { locked = true })
        repeat(4) {
            assertEquals(403, handler.post("/api/terabox", """{"code":"000000","cookie":"x"}""").status)
        }
        assertFalse(locked)
        handler.post("/api/terabox", """{"code":"000000","cookie":"x"}""")
        assertTrue(locked)
        // Even the right code is refused once locked.
        assertEquals(410, handler.post("/api/terabox", """{"code":"123456","cookie":"x"}""").status)
    }

    @Test
    fun addingAnAccountNeverEchoesTheSecret() = runTest {
        val persistence = MemoryPersistence()
        val repo = repository(persistence)
        var added: String? = null
        val handler = CloudSetupHandler(CloudSetupMode.ACCOUNTS, "123456", repo, null, { added = it }, {})
        val result = handler.post("/api/terabox", """{"code":"123456","name":"Mine","cookie":"$fakeCookie"}""")
        assertEquals(200, result.status)
        assertFalse(result.body.contains(fakeCookie))
        assertEquals("Mine", added)
        assertTrue(handler.finished)
        assertEquals(1, repo.accounts().size)
        // Stored, and only in the encrypted store.
        assertTrue(persistence.values.values.single().contains(fakeCookie))
        assertFalse(repo.accounts().single().toString().contains(fakeCookie))
    }

    @Test
    fun linkModeCannotAddAccounts() = runTest {
        val handler = CloudSetupHandler(CloudSetupMode.LINK, "123456", repository(), "id", {}, {})
        assertEquals(404, handler.post("/api/terabox", """{"code":"123456","cookie":"x"}""").status)
    }

    @Test
    fun disconnectDeletesCredentialsIndexAndLinks() = runTest {
        val persistence = MemoryPersistence()
        val index = MemoryIndex()
        val repo = repository(persistence, index)
        val account = repo.addTeraBox("Mine", fakeCookie)
        repo.setManualLink(account.id, CloudManualLink("1", "tt0000001", 1, 1))
        assertTrue(index.values.isNotEmpty())
        repo.disconnect(account.id)
        assertTrue(repo.accounts().isEmpty())
        assertTrue(persistence.values.isEmpty())
        assertTrue(index.values.isEmpty())
    }

    @Test
    fun signOutClearsEveryProfile() = runTest {
        val persistence = MemoryPersistence()
        val index = MemoryIndex()
        val repo = repository(persistence, index)
        repo.addTeraBox("Mine", fakeCookie)
        repo.clearAllProfiles()
        assertTrue(persistence.values.isEmpty())
        assertTrue(index.values.isEmpty())
        assertTrue(repo.accounts().isEmpty())
    }

    @Test
    fun foreignTeraBoxDomainsAreRefused() = runTest {
        val handler = CloudSetupHandler(CloudSetupMode.ACCOUNTS, "123456", repository(), null, {}, {})
        val result = handler.post("/api/terabox", """{"code":"123456","cookie":"x","domain":"https://evil.example.com"}""")
        assertTrue(result.body.contains("unsupported"))
    }

    @Test
    fun driveWithoutRefreshTokenSaysSo() = runTest {
        val handler = CloudSetupHandler(CloudSetupMode.ACCOUNTS, "123456", repository(), null, {}, {})
        val result = handler.post("/api/drive", """{"code":"123456","clientId":"1-abcdefgh.apps.googleusercontent.com","clientSecret":"x"}""")
        assertTrue(result.body.contains("missing_refresh_token"))
        assertFalse(handler.finished)
    }

    @Test
    fun signInStartReturnsOnlyTheConsentPage() = runTest {
        val handler = CloudSetupHandler(CloudSetupMode.ACCOUNTS, "123456", repository(), null, {}, {})
        val result = handler.post(
            "/api/drive/start",
            """{"code":"123456","clientId":"1234567890-abcdefghijk.apps.googleusercontent.com","clientSecret":"fake-secret-value"}"""
        )
        assertEquals(200, result.status)
        assertTrue(result.body.contains("accounts.google.com"))
        assertFalse(result.body.contains("fake-secret-value"))
        assertFalse(handler.finished)
    }

    @Test
    fun anEmptyBackgroundWalkDoesNotWipeTheIndex() = runTest {
        var files = 12
        val listing = FakeCloudHttp { request ->
            when {
                request.url.contains("/api/check/login") -> json("""{"errno":0}""")
                request.url.contains("/api/list") && request.url.contains("page=1") -> json(
                    """{"errno":0,"list":[""" + (1..files).joinToString(",") {
                        """{"fs_id":$it,"server_filename":"Show - ${"%02d".format(it)}.mkv","path":"/Show - ${"%02d".format(it)}.mkv","size":100,"isdir":0,"category":1}"""
                    } + "]}"
                )
                request.url.contains("/api/list") -> json("""{"errno":0,"list":[]}""")
                else -> json("{}", 404)
            }
        }
        val repo = CloudStorageRepository(MemoryPersistence(), MemoryIndex(), listing, CoroutineScope(Dispatchers.Unconfined), CloudIndexLimits.LOW_RAM)
        val account = repo.addTeraBox("Mine", fakeCookie)
        assertEquals(12, repo.sync(account.id))
        files = 0
        // A background sync that suddenly sees nothing keeps the old index.
        assertEquals(12, repo.sync(account.id))
        assertEquals("INCOMPLETE", repo.account(account.id)!!.lastError)
        assertEquals(12, repo.account(account.id)!!.indexedFiles)
        // "Sync now" accepts it.
        assertEquals(0, repo.sync(account.id, force = true))
    }
}
