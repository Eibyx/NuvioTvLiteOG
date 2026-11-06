package com.nuvio.tv.core.cloudstorage

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The loopback server against a fake provider: Range and 206, HEAD, seeking without reading the
 * whole file, link refresh after 403, header forwarding on redirects, access control, closing.
 */
class LocalCloudStreamServerTest {
    private val size = 48 * 1024 * 1024
    private fun byteAt(position: Int): Byte = (position % 251).toByte()
    private lateinit var upstream: FakeUpstream
    private lateinit var server: LocalCloudStreamServer
    private val upstreamBytes = AtomicLong()
    private val expiredHits = AtomicInteger()
    private val redirectHits = AtomicInteger()
    private val stalls = AtomicInteger()
    private val seenCookies = CopyOnWriteArrayList<String?>()
    private val player = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

    private val base get() = "http://127.0.0.1:${upstream.port}"

    /** A minimal HTTP/1.1 provider on loopback (no com.sun.net.httpserver on Android's classpath). */
    private inner class FakeUpstream {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: IOException) { break }
                    thread(isDaemon = true) { client.use(::handle) }
                }
            }
        }

        fun stop() = socket.close()

        private fun handle(client: Socket) {
            val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
                headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
            }
            val path = requestLine.split(' ')[1]
            val out = client.getOutputStream()
            fun head(status: String, extra: Map<String, String>) {
                val text = buildString {
                    append("HTTP/1.1 ").append(status).append("\r\n")
                    extra.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                    append("Connection: close\r\n\r\n")
                }
                out.write(text.toByteArray(Charsets.ISO_8859_1))
            }
            when (path) {
                "/expired" -> { expiredHits.incrementAndGet(); head("403 Forbidden", mapOf("Content-Length" to "0")) }
                "/redirect-same" -> redirectHits.incrementAndGet().let { head("302 Found", mapOf("Location" to "$base/file", "Content-Length" to "0")) }
                "/redirect-other" -> head("302 Found", mapOf("Location" to "http://localhost:$port/file", "Content-Length" to "0"))
                "/stall-once" -> {
                    val range = headers["range"]
                    // The block at 2 MiB: its first connection sends nothing, like a stuck CDN node.
                    if (range?.startsWith("bytes=${2 * 1024 * 1024}-") == true && stalls.getAndIncrement() == 0) {
                        Thread.sleep(10_000)
                        return
                    }
                    val (startText, endText) = range!!.removePrefix("bytes=").split('-')
                    val start = startText.toInt()
                    val end = endText.toIntOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
                    head(
                        "206 Partial Content",
                        mapOf("Content-Range" to "bytes $start-$end/$size", "Content-Type" to "video/mp4", "Content-Length" to (end - start + 1).toString())
                    )
                    try { writeCounted(out, start, end + 1) } catch (_: IOException) {}
                }
                "/file" -> {
                    seenCookies += headers["cookie"]
                    val range = headers["range"]
                    try {
                        if (range == null) {
                            head("200 OK", mapOf("Content-Type" to "application/octet-stream", "Content-Length" to size.toString()))
                            writeCounted(out, 0, size)
                        } else {
                            val (startText, endText) = range.removePrefix("bytes=").split('-')
                            val start = startText.toInt()
                            val end = endText.toIntOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
                            head(
                                "206 Partial Content",
                                mapOf(
                                    "Content-Range" to "bytes $start-$end/$size",
                                    "Content-Type" to "video/mp4",
                                    "Content-Length" to (end - start + 1).toString()
                                )
                            )
                            writeCounted(out, start, end + 1)
                        }
                    } catch (_: IOException) {
                        // The proxy stopped reading.
                    }
                }
                else -> head("404 Not Found", mapOf("Content-Length" to "0"))
            }
            out.flush()
        }
    }

    @Before
    fun setUp() {
        upstream = FakeUpstream()
        server = LocalCloudStreamServer(OkHttpClient.Builder().followRedirects(false).build(), allowPrivateUpstream = true)
    }

    @After
    fun tearDown() {
        server.stop()
        upstream.stop()
    }

    private fun writeCounted(out: java.io.OutputStream, from: Int, to: Int) {
        val buffer = ByteArray(16 * 1024)
        var position = from
        while (position < to) {
            val count = minOf(buffer.size, to - position)
            for (i in 0 until count) buffer[i] = byteAt(position + i)
            out.write(buffer, 0, count)
            upstreamBytes.addAndGet(count.toLong())
            position += count
        }
    }

    private fun open(path: String, headers: Map<String, String> = mapOf("Cookie" to "ndus=test")): String {
        var calls = 0
        return server.open("Kotoura san 01.mp4", size.toLong(), "video/mp4") { forceRefresh ->
            calls++
            CloudPlaybackSource(if (forceRefresh) "$base/file" else "$base$path", headers, size.toLong())
        }
    }

    @Test
    fun listensOnLoopbackOnly() {
        val url = open("/file")
        assertTrue(url.startsWith("http://127.0.0.1:"))
        assertTrue(url.contains("/v1/") && url.substringAfter("/v1/").substringBefore('/').length == 64)
    }

    @Test
    fun headReportsSizeAndRangesWithoutTouchingTheProvider() {
        val url = open("/file")
        player.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals(size.toString(), response.header("Content-Length"))
            assertEquals("bytes", response.header("Accept-Ranges"))
            assertEquals("video/mp4", response.header("Content-Type"))
        }
        assertEquals(0L, upstreamBytes.get())
    }

    @Test
    fun seekingToTheMiddleReadsOnlyThatRange() {
        val url = open("/file")
        val middle = size / 2
        player.newCall(Request.Builder().url(url).header("Range", "bytes=$middle-${middle + 65_535}").build()).execute().use { response ->
            assertEquals(206, response.code)
            assertEquals("bytes $middle-${middle + 65_535}/$size", response.header("Content-Range"))
            val body = response.body.bytes()
            assertArrayEquals(ByteArray(65_536) { byteAt(middle + it) }, body)
        }
        assertEquals(65_536L, upstreamBytes.get())
        assertEquals(listOf<String?>("ndus=test"), seenCookies.toList())
    }

    @Test
    fun aPlayerThatStopsReadingStopsTheDownload() {
        val url = open("/file")
        val port = url.substringAfter("127.0.0.1:").substringBefore('/').toInt()
        // Like a player that seeks: reads a little, then drops the connection.
        Socket("127.0.0.1", port).use { socket ->
            socket.receiveBufferSize = 64 * 1024
            val path = url.substringAfter(":$port")
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nRange: bytes=0-\r\n\r\n".toByteArray())
            val input = socket.getInputStream()
            val buffer = ByteArray(64 * 1024)
            var read = 0
            while (read < 256 * 1024) read += input.read(buffer).also { check(it > 0) }
        }
        Thread.sleep(1_000)
        assertTrue("read ${upstreamBytes.get()} of $size", upstreamBytes.get() < size / 2)
    }

    @Test
    fun anExpiredLinkIsReplacedOnce() {
        val url = open("/expired")
        player.newCall(Request.Builder().url(url).header("Range", "bytes=0-1023").build()).execute().use { response ->
            assertEquals(206, response.code)
            assertEquals(1024, response.body.bytes().size)
        }
        assertEquals(1, expiredHits.get())
        assertEquals(1L, server.session(url)?.refreshes?.get())
    }

    @Test
    fun secretsFollowRedirectsOnlyToTheSameHost() {
        player.newCall(Request.Builder().url(open("/redirect-same")).header("Range", "bytes=0-9").build()).execute().use {
            assertEquals(206, it.code)
        }
        player.newCall(Request.Builder().url(open("/redirect-other")).header("Range", "bytes=0-9").build()).execute().use {
            assertEquals(206, it.code)
        }
        assertEquals(listOf("ndus=test", null), seenCookies.toList())
    }

    @Test
    fun unknownTokensAndForeignHostsAreRefused() {
        val url = open("/file")
        val port = url.substringAfter("127.0.0.1:").substringBefore('/').toInt()
        player.newCall(Request.Builder().url("http://127.0.0.1:$port/v1/${"0".repeat(64)}/x.mp4").build()).execute().use {
            assertEquals(404, it.code)
        }
        // A web page rebinding a hostname to 127.0.0.1 sends its own Host header.
        Socket("127.0.0.1", port).use { socket ->
            val path = url.substringAfter(":$port")
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: evil.example:$port\r\n\r\n".toByteArray())
            val status = socket.getInputStream().bufferedReader().readLine()
            assertTrue(status, status.contains(" 403 "))
        }
        assertEquals(0L, upstreamBytes.get())
    }

    @Test
    fun closedSessionsAreGone() {
        val url = open("/file")
        server.close(url)
        assertNull(server.session(url))
        player.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(404, it.code) }
    }

    @Test
    fun onlyGetAndHead() {
        val url = open("/file")
        player.newCall(Request.Builder().url(url).post(okhttp3.RequestBody.create(null, ByteArray(0))).build()).execute().use {
            assertEquals(405, it.code)
        }
    }

    @Test
    fun largeReadsAreFetchedInParallelBlocksAndArriveInOrder() {
        val url = open("/file")
        val start = 3 * 1024 * 1024 + 12_345
        val end = start + 7 * 1024 * 1024 + 999
        player.newCall(Request.Builder().url(url).header("Range", "bytes=$start-$end").build()).execute().use { response ->
            assertEquals(206, response.code)
            assertEquals("bytes $start-$end/$size", response.header("Content-Range"))
            val body = response.body!!.bytes()
            assertEquals(end - start + 1, body.size)
            for (i in body.indices step 4093) assertEquals(byteAt(start + i), body[i])
            assertEquals(byteAt(end), body.last())
        }
        // Several ranged reads (2 MiB blocks), each with the cookie, none past the range.
        assertTrue(seenCookies.size >= 4)
        assertTrue(seenCookies.all { it == "ndus=test" })
        assertEquals((end - start + 1).toLong(), upstreamBytes.get())
    }

    @Test
    fun theRedirectTargetIsReusedForLaterBlocks() {
        val url = open("/redirect-same")
        player.newCall(Request.Builder().url(url).header("Range", "bytes=0-${6 * 1024 * 1024 - 1}").build()).execute().use {
            assertEquals(206, it.code)
            assertEquals(6 * 1024 * 1024, it.body!!.bytes().size)
        }
        // One redirect for the first block, then straight to the target.
        assertEquals(1, redirectHits.get())
    }

    @Test
    fun outOfRangeRequestsGet416() {
        val url = open("/file")
        player.newCall(Request.Builder().url(url).header("Range", "bytes=${size + 10}-").build()).execute().use {
            assertEquals(416, it.code)
        }
    }

    @Test
    fun aStalledBlockIsRetriedQuicklyInsteadOfDrainingTheBuffer() {
        val quick = LocalCloudStreamServer(
            OkHttpClient.Builder().followRedirects(false).build(),
            allowPrivateUpstream = true,
            blockStallMs = 500,
        )
        try {
            val url = quick.open("a.mkv", size.toLong(), "video/x-matroska") { _ ->
                CloudPlaybackSource("$base/stall-once", mapOf("Cookie" to "ndus=test"), size.toLong())
            }
            val started = System.currentTimeMillis()
            player.newCall(Request.Builder().url(url).header("Range", "bytes=0-${6 * 1024 * 1024 - 1}").build()).execute().use {
                assertEquals(206, it.code)
                val body = it.body!!.bytes()
                assertEquals(6 * 1024 * 1024, body.size)
                assertEquals(byteAt(3 * 1024 * 1024), body[3 * 1024 * 1024])
            }
            val elapsed = System.currentTimeMillis() - started
            assertTrue("took $elapsed ms", elapsed < 5_000)
            assertEquals(2, stalls.get())
        } finally {
            quick.stop()
        }
    }

    @Test
    fun oneConnectionStreamsTheWholeRequestOnASingleRead() {
        val url = server.open("a.mkv", size.toLong(), "video/x-matroska", connections = 1) { _ ->
            CloudPlaybackSource("$base/file", mapOf("Cookie" to "ndus=test"), size.toLong())
        }
        player.newCall(Request.Builder().url(url).header("Range", "bytes=0-${6 * 1024 * 1024 - 1}").build()).execute().use {
            assertEquals(206, it.code)
            assertEquals(6 * 1024 * 1024, it.body!!.bytes().size)
        }
        // One upstream request, as before 0.6.2.
        assertEquals(1, seenCookies.size)
    }
}
