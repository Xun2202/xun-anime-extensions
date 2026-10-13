package eu.kanade.tachiyomi.animeextension.all.rouxun

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.Deflater
import kotlin.concurrent.thread

class HlsProxyTest {
    private val client = OkHttpClient.Builder().build()
    private val headers = Headers.Builder().add("User-Agent", "rouxun-test").build()

    @Test
    fun rawJpegLabeledSegmentIsPassedThroughAsTs() {
        val ts = ByteArray(188).also { it[0] = 0x47 }
        Upstream { path ->
            when (path) {
                "/index.jpg" -> Triple(200, "text/plain", playlist("/C-000.jpg").toByteArray())
                "/C-000.jpg" -> Triple(200, "image/jpeg", ts)
                else -> Triple(404, "text/plain", "missing".toByteArray())
            }
        }.use { upstream ->
            val proxy = HlsProxy(client, headers)
            val local = proxy.playlistUrl("vid", upstream.url("/index.jpg"))
            val body = client.newCall(Request.Builder().url(local).build()).execute().body.string()
            assertTrue(body.contains("#EXTM3U"))
            val segment = body.lineSequence().map { it.trim() }.first { it.isNotEmpty() && !it.startsWith("#") }
            val bytes = client.newCall(Request.Builder().url(segment).build()).execute().body.bytes()
            assertArrayEquals(ts, bytes)
        }
    }

    @Test
    fun pngWrappedPlaylistAndSegmentAreUnwrapped() {
        val ts = ByteArray(188).also { it[0] = 0x47 }
        val playlist = playlist("/C-000.png").toByteArray()
        Upstream { path ->
            when (path) {
                "/index.png" -> Triple(200, "image/png", wrapPng(playlist, deflate = true))
                "/C-000.png" -> Triple(200, "image/png", wrapPng(ts, deflate = false))
                else -> Triple(404, "text/plain", "missing".toByteArray())
            }
        }.use { upstream ->
            val proxy = HlsProxy(client, headers)
            val local = proxy.playlistUrl("vid", upstream.url("/index.png"))
            val body = client.newCall(Request.Builder().url(local).build()).execute().body.string()
            val segment = body.lineSequence().map { it.trim() }.first { it.isNotEmpty() && !it.startsWith("#") }
            val bytes = client.newCall(Request.Builder().url(segment).build()).execute().body.bytes()
            assertArrayEquals(ts, bytes)
        }
    }

    @Test
    fun keepAliveRangeRequestReturnsTheWholeSegmentAndCloses() {
        val ts = ByteArray(188 * 4).also { bytes ->
            for (offset in bytes.indices step 188) bytes[offset] = 0x47
        }
        Upstream { path ->
            when (path) {
                "/index.jpg" -> Triple(200, "text/plain", playlist("/C-000.jpg").toByteArray())
                "/C-000.jpg" -> Triple(200, "image/jpeg", ts)
                else -> Triple(404, "text/plain", "missing".toByteArray())
            }
        }.use { upstream ->
            val proxy = HlsProxy(client, headers)
            val local = proxy.playlistUrl("vid", upstream.url("/index.jpg"))
            val playlist = client.newCall(Request.Builder().url(local).build()).execute().body.string()
            val segment = playlist.lineSequence().map { it.trim() }.first { it.isNotEmpty() && !it.startsWith("#") }
            val port = segment.substringAfter("http://127.0.0.1:").substringBefore("/").toInt()
            val path = segment.substringAfter(":$port")
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
                socket.soTimeout = 2000
                val request = "GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nRange: bytes=0-\r\nConnection: keep-alive\r\nUser-Agent: Lavf/60.16.100\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
                val input = socket.getInputStream()
                val raw = readUntilEof(input)
                val headerEnd = indexOf(raw, "\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                assertTrue(headerEnd > 0)
                val head = String(raw, 0, headerEnd, Charsets.ISO_8859_1)
                assertTrue(head.startsWith("HTTP/1.1 200"))
                assertTrue(head.contains("Content-Length: ${ts.size}"))
                assertTrue(head.contains("Connection: close"))
                val body = raw.copyOfRange(headerEnd + 4, raw.size)
                assertArrayEquals(ts, body)
            }
        }
    }

    /**
     * Android hard-codes [InetAddress.getLoopbackAddress] to ::1. A socket bound there
     * refuses 127.0.0.1, which is what the playlist tells ffmpeg to open. The child JVM
     * prefers IPv6 addresses so this fails if the proxy goes back to that bind.
     */
    @Test
    fun ipv4ClientReachesProxyWhenLoopbackPrefersIpv6() {
        val classpath = System.getProperty("java.class.path")
        val argFile = File.createTempFile("rouxun-ipv6-probe", ".txt")
        argFile.writeText("-Djava.net.preferIPv6Addresses=true\n-cp\n$classpath\n${Ipv4ListenProbe::class.java.name}\n")
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(java, "@${argFile.absolutePath}")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        assertEquals(output, 0, code)
        argFile.delete()
    }

    @Test
    fun deadPlaylistFailsBeforeThePlayer() {
        Upstream { _ -> Triple(404, "text/plain", "nope".toByteArray()) }.use { upstream ->
            val proxy = HlsProxy(client, headers)
            try {
                proxy.assertPlayable(upstream.url("/index.jpg"))
                fail("expected a playlist error")
            } catch (e: Exception) {
                assertEquals("rou.video 没有返回可播放的视频（HTTP 404）", e.message)
            }
        }
    }

    private fun playlist(segmentPath: String) = "#EXTM3U\n#EXTINF:1,\n$segmentPath\n"

    /** PNG signature, one `roUd` chunk, no CRC check (the proxy does not verify it). */
    private fun wrapPng(payload: ByteArray, deflate: Boolean): ByteArray {
        val body = if (deflate) deflate(payload) else payload
        val data = ByteArray(1 + body.size)
        data[0] = if (deflate) 1 else 0
        body.copyInto(data, 1)
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(intBytes(data.size))
        out.write("roUd".toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.write(byteArrayOf(0, 0, 0, 0))
        return out.toByteArray()
    }

    private fun deflate(payload: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(payload)
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(256)
        while (!deflater.finished()) {
            val written = deflater.deflate(buffer)
            if (written == 0) break
            out.write(buffer, 0, written)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun intBytes(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private class Upstream(
        private val handler: (String) -> Triple<Int, String, ByteArray>,
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val thread = thread(isDaemon = true, name = "rouxun-test-upstream") { acceptLoop() }

        fun url(path: String) = "http://127.0.0.1:${socket.localPort}$path"

        private fun acceptLoop() {
            while (!socket.isClosed) {
                val connection = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                thread(isDaemon = true) { handle(connection) }
            }
        }

        private fun handle(connection: Socket) {
            connection.use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val request = readLine(input) ?: return
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                }
                val path = request.split(" ").getOrNull(1)?.substringBefore("?").orEmpty()
                val (code, type, body) = handler(path)
                val reason = if (code == 200) "OK" else "Error"
                val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                val output = socket.getOutputStream()
                output.write(head.toByteArray(Charsets.ISO_8859_1))
                output.write(body)
                output.flush()
            }
        }

        private fun readLine(input: BufferedInputStream): String? {
            val buffer = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (buffer.isEmpty()) null else buffer.toString()
                if (b == '\n'.code) break
                if (b != '\r'.code) buffer.append(b.toChar())
            }
            return buffer.toString()
        }

        override fun close() {
            socket.close()
            thread.join(1000)
        }
    }

    private fun readUntilEof(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || data.size < needle.size) return -1
        for (start in 0..data.size - needle.size) {
            var matches = true
            for (i in needle.indices) {
                if (data[start + i] != needle[i]) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }
        return -1
    }
}

/** Child process entry. Exit 0 when an IPv4 client can open the proxy. */
object Ipv4ListenProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val proxy = HlsProxy(OkHttpClient(), Headers.Builder().add("User-Agent", "probe").build())
        val port = proxy.playlistUrl("vid", "http://127.0.0.1/unused")
            .substringAfter("http://127.0.0.1:")
            .substringBefore("/")
            .toInt()
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 2000) }
    }
}
