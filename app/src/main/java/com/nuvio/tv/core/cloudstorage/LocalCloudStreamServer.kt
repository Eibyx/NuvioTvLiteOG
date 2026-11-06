package com.nuvio.tv.core.cloudstorage

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Serves cloud files to the player on 127.0.0.1, the way `rclone serve http` does for Round Sync
 * Extra: every player request (HEAD or GET with Range) becomes a ranged GET to the provider with
 * the provider's headers, so seeking reads only the bytes the player asks for.
 *
 * - Bound to the loopback address only; each file gets an unguessable token in its path, and the
 *   `Host` header must be the loopback address (no DNS rebinding from a web page).
 * - A 401/403/404/410 or an HTML answer from the provider asks for a fresh link once, then retries.
 * - Redirects are followed by hand: `Cookie` and `Authorization` go only to the same host or its
 *   subdomains (Go's rule, which rclone follows), and never to loopback or private addresses.
 * - At most [maxConnections] requests at a time; the upstream read stops as soon as the player
 *   closes the connection.
 */
internal class LocalCloudStreamServer(
    baseClient: OkHttpClient,
    private val maxConnections: Int = 8,
    private val idleStopMs: Long = 5 * 60 * 1000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Tests serve the provider from loopback; the app never allows it. */
    private val allowPrivateUpstream: Boolean = false,
    /**
     * Parallel ranged reads to the provider. TeraBox caps the speed of each connection, Premium
     * included (its own apps download with several), so one long read cannot keep up with a
     * Blu-ray episode. Blocks are fetched [parallelReads] at a time and written to the player in
     * order: at most `parallelReads × chunkBytes` in memory per stream.
     */
    private val parallelReads: Int = 3,
    private val chunkBytes: Int = 2 * 1024 * 1024,
    /**
     * A block whose connection sends nothing for this long is dropped and asked again on a new
     * connection. Blocks reach the player in order, so one stalled connection would otherwise hold
     * playback until the general read timeout (a minute) while the player's buffer runs dry.
     */
    private val blockStallMs: Long = 8_000,
    /** The most a single block request may take, data included, before it is retried. */
    private val blockDeadlineMs: Long = 25_000,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    internal class Session(
        val fileName: String,
        val sizeBytes: Long?,
        val mimeType: String,
        val resolve: suspend (Boolean) -> CloudPlaybackSource,
        /** Parallel block reads for this file; 1 streams each request on a single connection. */
        val parallelReads: Int,
    ) {
        @Volatile var source: CloudPlaybackSource? = null
        /** Where the provider's link redirects to, reused until refused (saves a hop per read). */
        @Volatile var finalUrl: HttpUrl? = null
        @Volatile var finalTrusted: Boolean = false
        @Volatile var finalFor: CloudPlaybackSource? = null
        @Volatile var lastUsedMs: Long = 0L
        val bytesServed = AtomicLong()
        val upstreamRequests = AtomicLong()
        val refreshes = AtomicLong()
    }

    /**
     * Redirects are followed by hand (to drop credentials across hosts) and the resolver refuses
     * loopback, private and link-local addresses, so neither a redirect nor a DNS answer can point
     * the provider's cookie or token at the TV's own network.
     */
    private val client: OkHttpClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .apply { if (!allowPrivateUpstream) dns(PublicOnlyDns) }
        .build()

    /** The client for parallel blocks: short stall and per-request limits, see [blockStallMs]. */
    private val blockClient: OkHttpClient = client.newBuilder()
        .readTimeout(blockStallMs, TimeUnit.MILLISECONDS)
        .callTimeout(blockDeadlineMs, TimeUnit.MILLISECONDS)
        .build()

    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val activeCalls = ConcurrentHashMap<Response, Pair<Session, okhttp3.Call>>()
    private val pendingCalls = ConcurrentHashMap<okhttp3.Call, Session>()
    private val chunkPool: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "NuvioCloudChunk").apply { isDaemon = true }
    }
    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var executor: ThreadPoolExecutor? = null
    @Volatile private var lastEmptyAtMs: Long = 0L

    val port: Int? get() = synchronized(lock) { serverSocket?.takeIf { !it.isClosed }?.localPort }

    /** [connections]: the account's choice (1–4), or null for the device default. */
    fun open(
        fileName: String,
        sizeBytes: Long?,
        mimeType: String,
        connections: Int? = null,
        resolve: suspend (Boolean) -> CloudPlaybackSource
    ): String {
        val port = ensureStarted()
        val token = newToken()
        val reads = connections?.coerceIn(1, MAX_CONNECTIONS) ?: parallelReads
        sessions[token] = Session(fileName, sizeBytes, mimeType, resolve, reads).also { it.lastUsedMs = nowMs() }
        val name = URLEncoder.encode(fileName.take(120), "UTF-8").replace("+", "%20")
        return "http://127.0.0.1:$port/v1/$token/$name"
    }

    fun close(url: String?) {
        val token = tokenOf(url) ?: return
        val session = sessions.remove(token)
        // The player is gone: stop every read from the provider for this file.
        activeCalls.entries.filter { it.value.first === session }.forEach { entry ->
            activeCalls.remove(entry.key)?.second?.cancel()
        }
        pendingCalls.entries.filter { it.value === session }.forEach { entry ->
            pendingCalls.remove(entry.key)
            entry.key.cancel()
        }
        if (sessions.isEmpty()) lastEmptyAtMs = nowMs()
    }

    fun isLocalUrl(url: String?): Boolean = tokenOf(url)?.let(sessions::containsKey) == true

    internal fun session(url: String?): Session? = tokenOf(url)?.let(sessions::get)

    fun stop() = synchronized(lock) {
        runCatching { serverSocket?.close() }
        serverSocket = null
        executor?.shutdownNow()
        executor = null
        sessions.clear()
        activeCalls.values.forEach { it.second.cancel() }
        activeCalls.clear()
        pendingCalls.keys.forEach { it.cancel() }
        pendingCalls.clear()
    }

    private fun tokenOf(url: String?): String? {
        val port = port ?: return null
        val prefix = "http://127.0.0.1:$port/v1/"
        if (url == null || !url.startsWith(prefix)) return null
        return url.removePrefix(prefix).substringBefore('/').takeIf { it.isNotEmpty() }
    }

    private fun newToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun ensureStarted(): Int = synchronized(lock) {
        serverSocket?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val pool = ThreadPoolExecutor(0, maxConnections, 30, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
            Thread(runnable, "NuvioCloudStream").apply { isDaemon = true }
        }
        serverSocket = socket
        executor = pool
        thread(name = "NuvioCloudStreamAccept", isDaemon = true) { acceptLoop(socket, pool) }
        socket.localPort
    }

    private fun acceptLoop(socket: ServerSocket, pool: ThreadPoolExecutor) {
        socket.soTimeout = 60_000
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: java.net.SocketTimeoutException) {
                stopIfIdle(socket)
                continue
            } catch (_: IOException) {
                break
            }
            try {
                pool.execute { handle(client) }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                runCatching {
                    client.getOutputStream().writeHead(503, "Service Unavailable", mapOf("Content-Length" to "0"))
                    client.close()
                }
            }
        }
    }

    private fun stopIfIdle(socket: ServerSocket) {
        sessions.entries.removeIf { nowMs() - it.value.lastUsedMs > SESSION_IDLE_MS }
        if (sessions.isEmpty() && lastEmptyAtMs > 0 && nowMs() - lastEmptyAtMs > idleStopMs) {
            synchronized(lock) {
                if (serverSocket === socket && sessions.isEmpty()) stop()
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { connection ->
            connection.soTimeout = 30_000
            val input = BufferedInputStream(connection.getInputStream())
            val output = connection.getOutputStream()
            val request = readRequest(input) ?: return
            val port = port ?: return
            val host = request.headers["host"]
            if (host != "127.0.0.1:$port" && host != "localhost:$port") {
                output.writeHead(403, "Forbidden", mapOf("Content-Length" to "0"))
                return
            }
            val token = request.path.removePrefix("/v1/").substringBefore('/')
            val session = if (request.path.startsWith("/v1/")) sessions[token] else null
            if (session == null) {
                output.writeHead(404, "Not Found", mapOf("Content-Length" to "0"))
                return
            }
            session.lastUsedMs = nowMs()
            when (request.method) {
                "HEAD" -> head(session, output)
                "GET" -> get(session, request.headers["range"], output)
                else -> output.writeHead(405, "Method Not Allowed", mapOf("Allow" to "GET, HEAD", "Content-Length" to "0"))
            }
        }
    }

    private fun head(session: Session, output: OutputStream) {
        val size = session.sizeBytes ?: probeSize(session)
        val headers = buildMap {
            put("Content-Type", session.mimeType)
            put("Accept-Ranges", "bytes")
            size?.let { put("Content-Length", it.toString()) }
            put("Cache-Control", "no-store")
        }
        output.writeHead(200, "OK", headers)
    }

    private fun probeSize(session: Session): Long? = runCatching {
        upstream(session, "bytes=0-0").use { response ->
            response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
        }
    }.getOrNull()

    private fun get(session: Session, range: String?, output: OutputStream) {
        val validRange = range?.trim()?.takeIf { RANGE.matches(it) }
        val size = session.sizeBytes
        if (size != null && size > 0 && session.parallelReads > 1) {
            val span = byteSpan(validRange, size)
            if (span == null) {
                output.writeHead(416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$size", "Content-Length" to "0"))
                return
            }
            if (span.last - span.first + 1 > chunkBytes) {
                getParallel(session, span, partial = validRange != null, size = size, output = output)
                return
            }
        }
        val response = try {
            upstream(session, validRange)
        } catch (error: CloudStorageException) {
            output.writeHead(if (error.failure == CloudFailure.AUTH) 502 else 504, "Bad Gateway", mapOf("Content-Length" to "0"))
            return
        } catch (_: IOException) {
            output.writeHead(504, "Gateway Timeout", mapOf("Content-Length" to "0"))
            return
        }
        response.use {
            val code = response.code
            if (code != 200 && code != 206 && code != 416) {
                output.writeHead(502, "Bad Gateway", mapOf("Content-Length" to "0"))
                return
            }
            val headers = buildMap {
                put("Content-Type", contentType(response, session))
                put("Accept-Ranges", "bytes")
                response.header("Content-Length")?.let { put("Content-Length", it) }
                response.header("Content-Range")?.let { put("Content-Range", it) }
                put("Cache-Control", "no-store")
            }
            output.writeHead(code, if (code == 206) "Partial Content" else if (code == 416) "Range Not Satisfiable" else "OK", headers)
            if (code == 416) return
            val body = response.body?.byteStream() ?: return
            if (!copy(body, output, session)) {
                // Cancel instead of letting OkHttp drain the rest of the body to reuse the connection.
                activeCalls.remove(response)?.second?.cancel()
            }
        }
        activeCalls.remove(response)
    }

    /** The requested bytes of a [size]-byte file, or null when the range is outside it. */
    private fun byteSpan(range: String?, size: Long): LongRange? {
        if (range == null) return 0L..(size - 1)
        val (startText, endText) = range.removePrefix("bytes=").split('-', limit = 2)
        val span = when {
            startText.isEmpty() -> {
                val suffix = endText.toLongOrNull() ?: return null
                (size - suffix).coerceAtLeast(0L)..(size - 1)
            }
            else -> {
                val start = startText.toLongOrNull() ?: return null
                val end = endText.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
                start..end
            }
        }
        return span.takeIf { it.first in 0 until size && it.last >= it.first }
    }

    /**
     * Serves [span] from blocks fetched in parallel, in order. The first block is fetched before
     * the answer starts, so a refused link becomes a 502 instead of a broken response.
     */
    private fun getParallel(session: Session, span: LongRange, partial: Boolean, size: Long, output: OutputStream) {
        val startedAt = nowMs()
        val blocks = generateSequence(span.first) { it + chunkBytes }
            .takeWhile { it <= span.last }
            .map { start -> start..minOf(start + chunkBytes - 1, span.last) }
            .iterator()
        val inFlight = ArrayDeque<Future<ByteArray>>()
        fun refill(limit: Int = session.parallelReads) {
            while (inFlight.size < limit && blocks.hasNext()) {
                val block = blocks.next()
                inFlight.addLast(chunkPool.submit<ByteArray> { readBlock(session, block) })
            }
        }
        var served = 0L
        try {
            // The first block alone: it follows the provider's redirect (and refreshes an expired
            // link) once, and the parallel blocks reuse the result.
            refill(limit = 1)
            val first = try {
                inFlight.removeFirst().get()
            } catch (error: ExecutionException) {
                val cause = error.cause
                val code = if (cause is CloudStorageException && cause.failure != CloudFailure.AUTH) 504 else 502
                output.writeHead(code, "Bad Gateway", mapOf("Content-Length" to "0"))
                return
            }
            val headers = buildMap {
                put("Content-Type", session.mimeType)
                put("Accept-Ranges", "bytes")
                put("Content-Length", (span.last - span.first + 1).toString())
                if (partial) put("Content-Range", "bytes ${span.first}-${span.last}/$size")
                put("Cache-Control", "no-store")
            }
            output.writeHead(if (partial) 206 else 200, if (partial) "Partial Content" else "OK", headers)
            var block: ByteArray = first
            while (true) {
                refill()
                output.write(block)
                served += block.size
                session.bytesServed.addAndGet(block.size.toLong())
                session.lastUsedMs = nowMs()
                val next = inFlight.removeFirstOrNull() ?: break
                block = next.get()
            }
            output.flush()
        } catch (_: IOException) {
            // The player closed the connection (seek, pause, exit).
        } catch (_: ExecutionException) {
            // A block failed twice: end the answer; the player asks again from where it stopped.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            inFlight.forEach { it.cancel(true) }
            val seconds = (nowMs() - startedAt).coerceAtLeast(1) / 1000.0
            Log.i(
                TAG,
                "Served ${served / 1024} KiB in ${"%.1f".format(seconds)} s " +
                    "(${"%.1f".format(served * 8 / seconds / 1_000_000)} Mbit/s, ${session.parallelReads} reads, " +
                    "${session.refreshes.get()} link refreshes)"
            )
        }
    }

    /**
     * One block, exactly. A stalled, cut or refused read is retried on a new connection (with a
     * fresh link when the provider refuses the current one) a few times before the answer ends.
     */
    private fun readBlock(session: Session, block: LongRange): ByteArray {
        val length = (block.last - block.first + 1).toInt()
        var lastError: Exception? = null
        for (attempt in 0 until BLOCK_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted || session !in sessions.values) break
            if (attempt > 0) {
                Log.w(TAG, "Block at ${block.first / (1024 * 1024)} MiB retried (${lastError?.javaClass?.simpleName})")
                try {
                    sleep(BLOCK_RETRY_DELAYS_MS[minOf(attempt - 1, BLOCK_RETRY_DELAYS_MS.lastIndex)])
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            try {
                upstream(session, "bytes=${block.first}-${block.last}", blockClient).use { response ->
                    if (response.code != 206 && !(response.code == 200 && block.first == 0L)) {
                        throw CloudStorageException(if (response.code in 401..403) CloudFailure.AUTH else CloudFailure.FAILED)
                    }
                    val body = response.body?.byteStream() ?: throw IOException("No body")
                    val bytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val read = body.read(bytes, offset, length - offset)
                        if (read < 0) throw IOException("Short block")
                        offset += read
                    }
                    activeCalls.remove(response)
                    return bytes
                }
            } catch (error: CloudStorageException) {
                lastError = error
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastError ?: IOException("Block failed")
    }

    /** False when the player went away before the end. */
    private fun copy(input: InputStream, output: OutputStream, session: Session): Boolean {
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                session.bytesServed.addAndGet(read.toLong())
                session.lastUsedMs = nowMs()
            }
            output.flush()
            return true
        } catch (_: SocketException) {
            // The player closed the connection (seek, pause, exit): stop reading from the provider.
            return false
        } catch (_: IOException) {
            return false
        }
    }

    /** A ranged GET to the provider; a fresh link once when the current one is refused. */
    private fun upstream(session: Session, range: String?, http: OkHttpClient = client): Response {
        val first = session.source ?: synchronized(session) { session.source ?: resolve(session, forceRefresh = false) }
        val usedFinal = session.finalFor === first && session.finalUrl != null
        val response = fetch(first, range, session, http)
        if (!response.needsFreshLink()) return response
        response.close()
        if (usedFinal) {
            // The redirect target expired: ask the provider's link again before replacing it.
            session.finalUrl = null
            val again = fetch(first, range, session, http)
            if (!again.needsFreshLink()) return again
            again.close()
        }
        val fresh = synchronized(session) {
            // Parallel reads hit the same expired link: refresh it once.
            session.source?.takeIf { it !== first } ?: resolve(session, forceRefresh = true).also {
                session.refreshes.incrementAndGet()
            }
        }
        return fetch(fresh, range, session, http)
    }

    private fun resolve(session: Session, forceRefresh: Boolean): CloudPlaybackSource =
        runBlocking(Dispatchers.IO) { session.resolve(forceRefresh) }.also {
            session.source = it
            session.finalUrl = null
        }

    private fun fetch(source: CloudPlaybackSource, range: String?, session: Session, http: OkHttpClient = client): Response {
        val cached = session.finalUrl?.takeIf { session.finalFor === source }
        var url = cached ?: source.url.toHttpUrlOrNull() ?: throw CloudStorageException(CloudFailure.FAILED)
        val originHost = source.url.toHttpUrlOrNull()?.host ?: throw CloudStorageException(CloudFailure.FAILED)
        repeat(MAX_REDIRECTS + 1) {
            if (!url.isSafeUpstream()) throw CloudStorageException(CloudFailure.FAILED)
            val trusted = if (url === cached) session.finalTrusted else url.host == originHost || url.host.endsWith(".$originHost")
            val request = Request.Builder().url(url).get().apply {
                source.headers.forEach { (name, value) ->
                    val sensitive = name.equals("Cookie", true) || name.equals("Authorization", true)
                    if (!sensitive || trusted) header(name, value)
                }
                header("Accept-Encoding", "identity")
                range?.let { header("Range", it) }
            }.build()
            session.upstreamRequests.incrementAndGet()
            val call = http.newCall(request)
            pendingCalls[call] = session
            val response = try {
                call.execute()
            } finally {
                pendingCalls.remove(call)
            }
            if (response.code !in REDIRECT_CODES) {
                if (response.code == 200 || response.code == 206) {
                    session.finalUrl = url
                    session.finalTrusted = trusted
                    session.finalFor = source
                }
                activeCalls[response] = session to call
                return response
            }
            val next = response.header("Location")?.let { url.resolve(it) }
            response.close()
            url = next ?: throw CloudStorageException(CloudFailure.FAILED)
        }
        throw CloudStorageException(CloudFailure.FAILED)
    }

    private fun Response.needsFreshLink(): Boolean =
        code in setOf(401, 403, 404, 410) ||
            (code == 200 && header("Content-Type")?.startsWith("text/html", ignoreCase = true) == true)

    private fun contentType(response: Response, session: Session): String {
        val upstream = response.header("Content-Type")?.substringBefore(';')?.trim()
        return if (upstream.isNullOrEmpty() || upstream == "application/octet-stream" || upstream.startsWith("text/")) {
            session.mimeType
        } else {
            upstream
        }
    }

    private class LocalRequest(val method: String, val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): LocalRequest? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            total++
            if (total > MAX_HEADER_BYTES) return null
            if (byte == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                line.clear()
                if (text.isEmpty()) break
                lines += text
            } else {
                line.append(byte.toChar())
            }
        }
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 3) return null
        val headers = lines.drop(1).mapNotNull { header ->
            val separator = header.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            header.substring(0, separator).trim().lowercase() to header.substring(separator + 1).trim()
        }.toMap()
        val path = runCatching { URLDecoder.decode(parts[1].substringBefore('?'), "UTF-8") }.getOrNull() ?: return null
        return LocalRequest(parts[0].uppercase(), path, headers)
    }

    private fun OutputStream.writeHead(code: Int, reason: String, headers: Map<String, String>) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        write(head.toByteArray(Charsets.ISO_8859_1))
        flush()
    }

    private fun HttpUrl.isSafeUpstream(): Boolean {
        if (scheme != "https" && scheme != "http") return false
        if (allowPrivateUpstream) return true
        val host = host.lowercase()
        if (host == "localhost" || host.endsWith(".localhost")) return false
        val literal = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host) || ':' in host
        if (!literal) return true
        return runCatching { !InetAddress.getByName(host).isPrivateOrLocal() }.getOrDefault(false)
    }

    /** Resolves only to public addresses. */
    private object PublicOnlyDns : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = okhttp3.Dns.SYSTEM.lookup(hostname).filterNot { it.isPrivateOrLocal() }
            if (addresses.isEmpty()) throw java.net.UnknownHostException("No public address")
            return addresses
        }
    }

    companion object {
        private const val TAG = "CloudStream"
        private const val BLOCK_ATTEMPTS = 5
        const val MAX_CONNECTIONS = 4
        private val BLOCK_RETRY_DELAYS_MS = longArrayOf(0, 500, 1_000, 2_000)
        internal fun InetAddress.isPrivateOrLocal(): Boolean {
            if (isLoopbackAddress || isSiteLocalAddress || isLinkLocalAddress || isAnyLocalAddress || isMulticastAddress) {
                return true
            }
            val bytes = address
            // IPv6 unique local (fc00::/7) and IPv4 carrier-grade NAT (100.64.0.0/10).
            if (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC) return true
            if (bytes.size == 4 && (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 64) return true
            return false
        }

        private val RANGE = Regex("""^bytes=\d*-\d*$""")
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val MAX_REDIRECTS = 5
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val SESSION_IDLE_MS = 12 * 60 * 60 * 1000L
    }
}
