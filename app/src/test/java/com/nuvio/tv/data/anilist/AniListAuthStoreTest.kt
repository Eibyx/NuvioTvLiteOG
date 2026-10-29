package com.nuvio.tv.data.anilist

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AniListAuthStoreTest {
    private class Memory : AniListAuthPersistence {
        val data = mutableMapOf<Int, String>()
        override fun read(profileId: Int) = data[profileId]
        override fun write(profileId: Int, value: String?) { if (value == null) data.remove(profileId) else data[profileId] = value }
        override fun clear() = data.clear()
    }
    @Test fun disconnectInvalidatesInFlightScopesAndLeavesOtherProfilesUntouched() {
        val disk = Memory(); val store = AniListAuthStore(disk, now = { 100 })
        store.authorize("profile-one-token", 1000, store.scope())
        val old = store.scope()
        store.selectProfile(2); store.authorize("profile-two-token", 1000, store.scope()); store.clearAuth()
        assertNull(store.accessToken())
        assertThrows(CancellationException::class.java) { store.checkScope(old) }
        store.selectProfile(1)
        assertEquals("profile-one-token", store.accessToken())
    }
    @Test fun persistedAuthorizationSurvivesRestartAndExpires() {
        val disk = Memory(); val store = AniListAuthStore(disk, now = { 100 })
        store.authorize("access-token-value", 1000, store.scope())
        assertEquals("access-token-value", AniListAuthStore(disk, now = { 500 }).accessToken())
        val expired = AniListAuthStore(disk, now = { 1001 }); expired.refreshExpiry()
        assertNull(expired.accessToken()); assertFalse(expired.state.value.isAuthenticated)
        assertEquals(AniListAuthError.AUTHORIZATION_EXPIRED, expired.state.value.error)
    }
    @Test fun tvConfigurationRequiresPublicClientAndSafeCallback() {
        assertFalse(AniListConfig.validClient("", "https://example.org/callback"))
        assertFalse(AniListConfig.validClient("abc", "nuvio://auth/anilist"))
        assertFalse(AniListConfig.validClient("123", "http://example.org/callback"))
        assertFalse(AniListConfig.validClient("123", "other://auth/anilist"))
        assertFalse(AniListConfig.validClient("123", "nuvio://auth/anilist#x"))
        assertTrue(AniListConfig.validClient("123", "https://example.org/callback"))
        assertTrue(AniListConfig.validClient(AniListConfig.MOBILE_CLIENT_ID, AniListConfig.MOBILE_REDIRECT_URI))
    }
    @Test fun tvBrowserRequestMatchesMobile() {
        assertEquals("https://anilist.co/api/v2/oauth/authorize?client_id=53329&response_type=token",
            AniListConfig.tvBrowserAuthorizeUrl("53329"))
    }
    @Test fun parsesMobileRedirectCallback() {
        val redirect = AniListConfig.MOBILE_REDIRECT_URI
        assertNull(parseAniListCallback("https://anilist.co/login", redirect))
        assertNull(parseAniListCallback("nuvio://auth/anilistx#access_token=abcdefghijklmnopqrst", redirect))
        assertEquals(AniListCallback.Token("abcdefghijklmnopqrst.uvw", 31536000),
            parseAniListCallback("nuvio://auth/anilist#access_token=abcdefghijklmnopqrst.uvw&token_type=Bearer&expires_in=31536000", redirect))
        assertEquals(AniListCallback.Denied, parseAniListCallback("nuvio://auth/anilist?error=access_denied", redirect))
        assertEquals(AniListCallback.Invalid, parseAniListCallback("nuvio://auth/anilist#token_type=Bearer", redirect))
    }
}
