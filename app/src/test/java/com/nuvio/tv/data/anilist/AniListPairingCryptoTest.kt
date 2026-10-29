package com.nuvio.tv.data.anilist

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class AniListPairingCryptoTest {
    private fun encrypted(pair: AniListPairingCrypto, state: String): List<String> {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val aes = Cipher.getInstance("AES/GCM/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        aes.updateAAD(state.toByteArray())
        val payload = aes.doFinal("""{"token":"example-token-1234567890","expiresIn":3600,"state":"$state"}""".toByteArray())
        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
        rsa.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(AniListPairingCrypto.decode(pair.publicKey))),
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        return listOf(rsa.doFinal(key), iv, payload).map(AniListPairingCrypto::encode)
    }
    @Test fun phoneEnvelopeDecryptsAndCannotBeReplayed() {
        val pair = AniListPairingCrypto { 1000L }; val data = encrypted(pair, "expected-state")
        val result = pair.decrypt(data[0], data[1], data[2], "expected-state")
        assertEquals("example-token-1234567890", result.first); assertEquals(3601000L, result.second)
        assertThrows(IllegalArgumentException::class.java) { pair.decrypt(data[0], data[1], data[2], "expected-state") }
    }
    @Test fun wrongStateAndTamperedCiphertextNeverAuthorize() {
        val pair = AniListPairingCrypto { 1000L }; val data = encrypted(pair, "expected-state")
        assertThrows(Exception::class.java) { pair.decrypt(data[0], data[1], data[2], "wrong-state") }
        val bytes = AniListPairingCrypto.decode(data[2]); bytes[0] = (bytes[0].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { pair.decrypt(data[0], data[1], AniListPairingCrypto.encode(bytes), "expected-state") }
        assertEquals("example-token-1234567890", pair.decrypt(data[0], data[1], data[2], "expected-state").first)
    }
    @Test fun expiredPairingRejectsEvenAValidEnvelope() {
        var now = 0L; val pair = AniListPairingCrypto { now }; val data = encrypted(pair, "expected-state")
        now = 600001L
        assertThrows(IllegalArgumentException::class.java) { pair.decrypt(data[0], data[1], data[2], "expected-state") }
    }
}
