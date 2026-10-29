package com.nuvio.tv.data.anilist

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import okio.ByteString.Companion.toByteString
import okio.ByteString.Companion.decodeBase64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*

/** Ephemeral encryption key. No OAuth credential crosses the LAN in plaintext. */
internal class AniListPairingCrypto(private val now: () -> Long = System::currentTimeMillis) {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val nonce: String = encode(ByteArray(32).also(SecureRandom()::nextBytes))
    val publicKey: String = encode(pair.public.encoded)
    val expiresAt: Long = now() + 10 * 60_000
    private var consumed = false

    @Synchronized
    fun decrypt(encryptedKey: String, iv: String, payload: String, expectedState: String): Pair<String, Long> {
        require(!consumed && now() < expiresAt) { "Pairing expired" }
        require(payload.length in 1..32_768 && encryptedKey.length < 1024 && iv.length < 64)
        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
        rsa.init(Cipher.DECRYPT_MODE, pair.private, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        val key = rsa.doFinal(decode(encryptedKey)); require(key.size == 32)
        val aes = Cipher.getInstance("AES/GCM/NoPadding")
        val ivBytes = decode(iv); require(ivBytes.size == 12)
        aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ivBytes))
        aes.updateAAD(expectedState.toByteArray(Charsets.UTF_8))
        val data = aniListJson.parseToJsonElement(aes.doFinal(decode(payload)).toString(Charsets.UTF_8)).jsonObject
        require(MessageDigest.isEqual(data.getValue("state").jsonPrimitive.content.toByteArray(), expectedState.toByteArray()))
        val token = data.getValue("token").jsonPrimitive.content
        require(token.length in 16..16_384 && token.none { it.isWhitespace() || it.isISOControl() })
        val seconds = data.getValue("expiresIn").jsonPrimitive.long
        require(seconds in 1..31_622_400)
        consumed = true
        return token to (now() + seconds * 1000)
    }

    companion object {
        fun encode(bytes: ByteArray): String = bytes.toByteString().base64Url().trimEnd('=')
        fun decode(text: String): ByteArray = requireNotNull(text.decodeBase64()).toByteArray()
    }
}
