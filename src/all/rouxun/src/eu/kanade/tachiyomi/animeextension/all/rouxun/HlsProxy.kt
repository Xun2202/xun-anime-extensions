package eu.kanade.tachiyomi.animeextension.all.rouxun

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.zip.InflaterInputStream
import kotlin.concurrent.thread

/**
 * Loopback HTTP server that turns rou.video's disguised HLS into something mpv / ffmpeg can play.
 *
 * The site serves both the media playlist and every segment as a PNG file: a real PNG header
 * plus a private `roUd` chunk whose payload is the actual data (playlist: deflate-compressed
 * m3u8; segment: raw MPEG-TS). The browser player unwraps them in a Shaka response filter;
 * nothing in Android's media stack can, so this proxy does it:
 *
 *  - `GET /hls/<id>/index.m3u8?u=<upstream>` fetches the playlist (following the
 *    `/api/hls/<id>` redirect), unwraps + inflates it and rewrites every segment URI to
 *    `/seg/<n>.ts?u=<absolute upstream URL>`.
 *  - `GET /seg/<n>.ts?u=<upstream>` streams the segment with the PNG prefix stripped.
 *
 * Responses are `Connection: close` without a Content-Length, so clients read until EOF.
 * Range headers are ignored (HLS clients do not need them for whole segments).
 */
internal class HlsProxy(
    private val client: OkHttpClient,
    private val upstreamHeaders: Headers,
) {
    @Volatile
    private var server: ServerSocket? = null

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "rouxun-hls-worker").apply { isDaemon = true }
    }

    /** `http://127.0.0.1:<port>`; starts the server on first use or after it died. */
    val baseUrl: String
        get() = "http://127.0.0.1:${ensureStarted().localPort}"

    fun playlistUrl(videoId: String, upstreamUrl: String): String = "$baseUrl/hls/$videoId/index.m3u8?u=${encode(upstreamUrl)}"

    @Synchronized
    private fun ensureStarted(): ServerSocket {
        server?.takeIf { !it.isClosed }?.let { return it }
        val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        server = socket
        thread(isDaemon = true, name = "rouxun-hls-accept") { acceptLoop(socket) }
        return socket
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val connection = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            executor.execute {
                try {
                    connection.use(::handle)
                } catch (e: Exception) {
                    Log.w(TAG, "connection failed: ${e.message}")
                }
            }
        }
        synchronized(this) { if (server === socket) server = null }
    }

    private fun handle(connection: Socket) {
        connection.soTimeout = 30_000
        val input = BufferedInputStream(connection.getInputStream())
        val requestLine = readLine(input) ?: return
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val headOnly = parts[0] == "HEAD"
        val url = "http://127.0.0.1${parts[1]}".toHttpUrlOrNull()

        val output = BufferedOutputStream(connection.getOutputStream(), 64 * 1024)
        try {
            when {
                url == null -> writeStatus(output, 400, "Bad Request")
                url.encodedPath.endsWith(".m3u8") -> servePlaylist(url, output, headOnly)
                url.encodedPath.startsWith("/seg/") -> serveSegment(url, output, headOnly)
                else -> writeStatus(output, 404, "Not Found")
            }
        } catch (e: ResponseHeadersSent) {
            // The player closed the connection mid-segment (seek, stop); nothing left to send.
            return
        } catch (e: Exception) {
            Log.w(TAG, "request failed: ${e.message}")
            writeStatus(output, 502, "Bad Gateway", e.message.orEmpty())
        }
        output.flush()
    }

    // =============================== Playlist ===============================

    private fun servePlaylist(url: HttpUrl, output: OutputStream, headOnly: Boolean) {
        val upstream = url.queryParameter("u") ?: throw IOException("missing upstream url")
        client.newCall(GET(upstream, upstreamHeaders)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("upstream playlist ${response.code}")
            val finalUrl = response.request.url
            val playlist = String(unwrap(response.body.bytes()), Charsets.UTF_8)
            val body = rewritePlaylist(playlist, finalUrl).toByteArray(Charsets.UTF_8)
            writeHead(output, 200, "OK", "application/vnd.apple.mpegurl", body.size.toLong())
            if (!headOnly) output.write(body)
        }
    }

    private fun rewritePlaylist(playlist: String, playlistUrl: HttpUrl): String {
        val isMaster = playlist.contains("#EXT-X-STREAM-INF")
        var segmentIndex = 0
        return playlist.lineSequence().joinToString("\n") { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> line
                line.startsWith("#") -> URI_ATTRIBUTE.replace(line) { match ->
                    val absolute = playlistUrl.resolve(match.groupValues[1])?.toString() ?: match.groupValues[1]
                    "URI=\"$baseUrl/seg/init.bin?u=${encode(absolute)}\""
                }
                else -> {
                    val absolute = playlistUrl.resolve(line)?.toString() ?: line
                    if (isMaster) {
                        "$baseUrl/hls/variant/index.m3u8?u=${encode(absolute)}"
                    } else {
                        "$baseUrl/seg/${segmentIndex++}.ts?u=${encode(absolute)}"
                    }
                }
            }
        }
    }

    // =============================== Segments ===============================

    private fun serveSegment(url: HttpUrl, output: OutputStream, headOnly: Boolean) {
        val upstream = url.queryParameter("u") ?: throw IOException("missing upstream url")
        client.newCall(GET(upstream, upstreamHeaders)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("upstream segment ${response.code}")
            val input = BufferedInputStream(response.body.byteStream(), 64 * 1024)
            val payload = locatePayload(input, response.body.contentLength())

            // Raw TS payloads have a known size; inflated ones are sent chunked so the client
            // sees a clean end of body instead of "stream ends prematurely" on connection close.
            val contentLength = payload.length.takeIf { it >= 0 && !payload.deflated }
            writeHead(output, 200, "OK", "video/mp2t", contentLength, chunked = contentLength == null)
            if (headOnly) return
            try {
                val source = if (payload.deflated) InflaterInputStream(payload.stream) else payload.stream
                if (contentLength == null) {
                    ChunkedOutputStream(output).also(source::copyTo).finish()
                } else {
                    source.copyTo(output)
                }
            } catch (e: IOException) {
                throw ResponseHeadersSent(e)
            }
        }
    }

    private class Payload(val stream: InputStream, val length: Long, val deflated: Boolean)

    /**
     * Positions [input] at the data to serve. For a PNG-wrapped file that is the payload of the
     * first `roUd` chunk (bit 0 of its flag byte marks deflate compression); anything else is
     * passed through unchanged with [upstreamLength] as its size (-1 when unknown).
     */
    private fun locatePayload(input: BufferedInputStream, upstreamLength: Long): Payload {
        input.mark(PNG_SIGNATURE.size)
        val signature = ByteArray(PNG_SIGNATURE.size)
        val isPng = readFully(input, signature) && signature.contentEquals(PNG_SIGNATURE)
        input.reset()
        if (!isPng) return Payload(input, upstreamLength, false)

        input.skipFully(PNG_SIGNATURE.size.toLong())
        val data = DataInputStream(input)
        while (true) {
            val length = try {
                data.readInt().toLong() and 0xFFFFFFFFL
            } catch (e: EOFException) {
                return Payload(ByteArray(0).inputStream(), 0, false)
            }
            val type = data.readInt()
            if (type == CHUNK_ROUD && length >= 1) {
                val flags = data.readUnsignedByte()
                return Payload(LimitedInputStream(input, length - 1), length - 1, flags and FLAG_DEFLATE != 0)
            }
            if (type == CHUNK_IEND) return Payload(ByteArray(0).inputStream(), 0, false)
            input.skipFully(length + 4) // data + crc
        }
    }

    /** Returns the payload of a PNG-wrapped file, or [bytes] unchanged when it is not a PNG. */
    private fun unwrap(bytes: ByteArray): ByteArray {
        val payload = locatePayload(BufferedInputStream(bytes.inputStream()), bytes.size.toLong())
        return (if (payload.deflated) InflaterInputStream(payload.stream) else payload.stream).readBytes()
    }

    // ================================ HTTP ================================

    private fun writeHead(
        output: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        contentLength: Long?,
        chunked: Boolean = false,
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            if (contentLength != null) append("Content-Length: ").append(contentLength).append("\r\n")
            if (chunked) append("Transfer-Encoding: chunked\r\n")
            append("Accept-Ranges: none\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    private fun writeStatus(output: OutputStream, code: Int, reason: String, message: String = reason) {
        val body = message.toByteArray(Charsets.UTF_8)
        writeHead(output, code, reason, "text/plain; charset=utf-8", body.size.toLong())
        output.write(body)
    }

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.append(b.toChar())
            if (buffer.length > 16 * 1024) throw IOException("header line too long")
        }
        return buffer.toString()
    }

    private fun readFully(input: InputStream, target: ByteArray): Boolean {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun InputStream.skipFully(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                if (read() < 0) throw EOFException()
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Reads at most [limit] bytes from [input]; never closes it. */
    private class LimitedInputStream(private val input: InputStream, private var limit: Long) : InputStream() {
        override fun read(): Int {
            if (limit <= 0) return -1
            val b = input.read()
            if (b >= 0) limit--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (limit <= 0) return -1
            val read = input.read(b, off, minOf(len.toLong(), limit).toInt())
            if (read > 0) limit -= read
            return read
        }

        override fun close() = Unit
    }

    /** HTTP/1.1 chunked transfer encoding; call [finish] after the last byte. Never closes [output]. */
    private class ChunkedOutputStream(private val output: OutputStream) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            output.write(Integer.toHexString(len).toByteArray(Charsets.ISO_8859_1))
            output.write(CRLF)
            output.write(b, off, len)
            output.write(CRLF)
        }

        fun finish() {
            output.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }

        override fun flush() = output.flush()

        override fun close() = Unit
    }

    private class ResponseHeadersSent(cause: IOException) : IOException(cause)

    companion object {
        private const val TAG = "RouXunHlsProxy"

        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )

        /** "roUd" as a big-endian chunk type. */
        private const val CHUNK_ROUD = 0x726F5564
        private const val CHUNK_IEND = 0x49454E44
        private const val FLAG_DEFLATE = 1

        private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)

        private val URI_ATTRIBUTE = Regex("""URI="([^"]+)"""")
    }
}
