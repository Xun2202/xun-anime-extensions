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
import java.io.IOException
import java.net.InetAddress
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
        private val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
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
}
