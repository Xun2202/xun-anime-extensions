package eu.kanade.tachiyomi.animeextension.all.jellyfinxun

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Streams via the checked TLS client; mpv/FFmpeg never receive NAS credentials. */
internal class NasStreamProxy(
    private val client: OkHttpClient,
    private val allowUpstream: (HttpUrl) -> Boolean,
) : Closeable {
    private val resources = ConcurrentHashMap<String, HttpUrl>()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val random = SecureRandom()
    private val workers = ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS, ArrayBlockingQueue(12), { task ->
        Thread(task, "jellyfin-xun-stream").apply { isDaemon = true }
    }).apply { allowCoreThreadTimeOut(true) }

    @Volatile private var server: ServerSocket? = null

    @Volatile private var closed = false

    @Synchronized
    fun streamUrl(upstream: HttpUrl, extension: String = "bin"): String {
        check(!closed) { "本机连接已清除，请重新解析视频" }
        require(allowUpstream(upstream)) { "拒绝转发其他服务器的媒体" }
        check(resources.size < 512) { "当前播放链接过多，请在插件设置中重新登录" }
        val key = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        resources[key] = upstream
        val socket = server ?: ServerSocket(0, 12, InetAddress.getByName("127.0.0.1")).also { listener ->
            server = listener
            thread(isDaemon = true, name = "jellyfin-xun-listen") {
                while (!listener.isClosed) {
                    val connection = try {
                        listener.accept()
                    } catch (_: IOException) {
                        break
                    }
                    activeSockets.add(connection)
                    try {
                        workers.execute {
                            connection.use { accepted ->
                                try {
                                    handle(accepted)
                                } catch (_: Exception) { /* Client may seek or stop. No credentials in logs. */ }
                            }
                            activeSockets.remove(connection)
                        }
                    } catch (_: RejectedExecutionException) {
                        connection.close()
                        activeSockets.remove(connection)
                    }
                }
            }
        }
        val suffix = extension.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
        return "http://127.0.0.1:${socket.localPort}/media/$key/file.$suffix"
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        val parts = (readLine(input) ?: return).split(' ')
        if (parts.size != 3 || parts[0] !in listOf("GET", "HEAD") || parts[2] !in listOf("HTTP/1.0", "HTTP/1.1")) {
            status(output, 405)
            return
        }
        val headers = mutableMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > 16_384 || headers.size >= 40) {
                status(output, 431)
                return
            }
            val separator = line.indexOf(':')
            if (separator <= 0) {
                status(output, 400)
                return
            }
            val key = line.substring(0, separator).lowercase()
            if (headers.put(key, line.substring(separator + 1).trim()) != null) {
                status(output, 400)
                return
            }
        }
        val authority = "127.0.0.1:${socket.localPort}"
        if (headers["host"] != authority || "origin" in headers || "referer" in headers || "content-length" in headers || "transfer-encoding" in headers) {
            status(output, 403)
            return
        }
        val match = RESOURCE.matchEntire(parts[1]) ?: run {
            status(output, 404)
            return
        }
        val upstream = resources[match.groupValues[1]] ?: run {
            status(output, 404)
            return
        }
        if (!allowUpstream(upstream)) {
            status(output, 403)
            return
        }
        val range = headers["range"]
        if (range != null && !RANGE.matches(range)) {
            status(output, 416)
            return
        }
        val request = Request.Builder().url(upstream)
            .method(parts[0], null)
            .header("Accept-Encoding", "identity")
            .apply {
                if (range != null) header("Range", range)
                headers["if-range"]?.takeIf { it.length <= 256 }?.let { header("If-Range", it) }
            }.build()
        var sent = false
        try {
            client.newCall(request).execute().use { response ->
                if (response.code !in listOf(200, 206, 304, 416)) {
                    status(output, if (response.code in listOf(401, 403)) 403 else 502)
                    return
                }
                val responseHeaders = linkedMapOf("Connection" to "close", "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")
                for (name in listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified")) {
                    response.header(name)?.takeIf { '\r' !in it && '\n' !in it }?.let { responseHeaders[name] = it }
                }
                writeHead(output, response.code, responseHeaders)
                sent = true
                if (parts[0] != "HEAD" && response.code != 304) response.body.byteStream().copyTo(output, 64 * 1024)
                output.flush()
            }
        } catch (_: Exception) {
            if (!sent) status(output, 502)
        }
    }

    private fun status(out: OutputStream, code: Int) {
        writeHead(out, code, mapOf("Content-Length" to "0", "Connection" to "close", "Cache-Control" to "no-store"))
        out.flush()
    }

    private fun writeHead(out: OutputStream, code: Int, headers: Map<String, String>) {
        out.write(
            buildString {
                append(
                    "HTTP/1.1 $code ${if (code == 206) {
                        "Partial Content"
                    } else if (code == 200) {
                        "OK"
                    } else {
                        "Response"
                    }}\r\n",
                )
                headers.forEach { (key, value) -> append("$key: $value\r\n") }
                append("\r\n")
            }.toByteArray(Charsets.ISO_8859_1),
        )
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= 4096) {
            val b = input.read()
            if (b < 0) return null
            if (b == 10) return bytes.toByteArray().toString(Charsets.ISO_8859_1).trimEnd('\r')
            bytes.add(b.toByte())
        }
        throw IOException("Local request line too long")
    }

    @Synchronized
    override fun close() {
        closed = true
        resources.clear()
        server?.close()
        server = null
        activeSockets.forEach { runCatching { it.close() } }
        activeSockets.clear()
        client.dispatcher.cancelAll()
        workers.shutdownNow()
    }

    companion object {
        private val RESOURCE = Regex("/media/([0-9a-f]{64})/file\\.[a-z0-9]{1,8}")
        private val RANGE = Regex("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")
    }
}
