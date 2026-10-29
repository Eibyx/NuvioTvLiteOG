package com.nuvio.tv.data.anilist

import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.URLEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Exists only while the AniList account dialog is pairing. Independent of Nuvio authentication. */
internal class AniListPhonePairing(
    clientId: String,
    redirectUri: String,
    onToken: (String, Long) -> Unit
) {
    private val crypto = AniListPairingCrypto()
    private val address = NetworkInterface.getNetworkInterfaces().toList().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }
        ?.hostAddress ?: error("TV needs a local IPv4 network")
    private val port = 18767
    private val path = "/anilist/${crypto.nonce}"
    private val redirect = URI(redirectUri)
    private val expectedOrigin = "${redirect.scheme}://${redirect.rawAuthority}"
    private val state = AniListPairingCrypto.encode(buildJsonObject {
        put("v", 1); put("nonce", crypto.nonce); put("key", crypto.publicKey)
        put("endpoint", "http://$address:$port$path"); put("redirect", redirectUri)
    }.toString().toByteArray())
    val expiresAt: Long get() = crypto.expiresAt
    val authorizeUrl = AniListConfig.AUTHORIZE_URL + "?client_id=" + enc(clientId) +
        "&response_type=token&redirect_uri=" + enc(redirectUri) + "&state=" + enc(state)
    private val server = object : NanoHTTPD(address, port) {
        override fun serve(session: IHTTPSession): Response {
            fun answer(status: Response.Status, message: String) = newFixedLengthResponse(status, "text/plain; charset=utf-8", message).also {
                it.addHeader("Cache-Control", "no-store"); it.addHeader("Referrer-Policy", "no-referrer")
                it.addHeader("X-Content-Type-Options", "nosniff")
            }
            if (session.uri != path || session.method != Method.POST || session.headers["origin"] !in setOf(expectedOrigin, "null"))
                return answer(Response.Status.FORBIDDEN, "Solicitud no válida")
            val length = session.headers["content-length"]?.toLongOrNull()
            if (length == null || length !in 1..48_000) return answer(Response.Status.BAD_REQUEST, "Tamaño no válido")
            return try {
                session.parseBody(hashMapOf())
                val params = session.parameters
                val result = crypto.decrypt(params.getValue("key").single(), params.getValue("iv").single(), params.getValue("data").single(), state)
                onToken(result.first, result.second)
                answer(Response.Status.OK, "Autorización recibida. Comprueba el estado en tu TV; ya puedes cerrar esta pestaña.")
            } catch (_: Exception) { answer(Response.Status.BAD_REQUEST, "Autorización caducada o no válida. Inicia la conexión de nuevo en el TV.") }
        }
    }
    fun start() = server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    fun stop() = server.stop()
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
