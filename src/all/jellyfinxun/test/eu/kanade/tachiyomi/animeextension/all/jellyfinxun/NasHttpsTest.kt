package eu.kanade.tachiyomi.animeextension.all.jellyfinxun

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class NasHttpsTest {
    @Test
    fun checkedPrivateCaAuthenticatesOnlyTheConfiguredNasAndSupportsSeeking() {
        FakeNas().use { nas ->
            val secure = nas.connection()
            NasStreamProxy(secure.client, secure::sameOrigin).use { proxy ->
                val local = proxy.streamUrl(secure.url("video"), "mp4")
                assertTrue(local.startsWith("http://127.0.0.1:"))
                assertFalse(local.contains(FakeNas.TOKEN))
                val player = OkHttpClient()
                player.newCall(Request.Builder().url(local).header("Range", "bytes=1000-2023").build()).execute().use { response ->
                    assertEquals(206, response.code)
                    assertEquals("bytes 1000-2023/${nas.video.size}", response.header("Content-Range"))
                    assertArrayEquals(nas.video.copyOfRange(1000, 2024), response.body.bytes())
                    assertNull(response.header("Authorization"))
                }
                player.newCall(Request.Builder().url(local).header("Range", "bytes=-128").build()).execute().use { response ->
                    assertEquals(206, response.code)
                    assertArrayEquals(nas.video.takeLast(128).toByteArray(), response.body.bytes())
                }
                player.newCall(Request.Builder().url(local).head().build()).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals(nas.video.size.toString(), response.header("Content-Length"))
                    assertEquals(0, response.body.bytes().size)
                }
                assertTrue(nas.requests.all { it.authorization.contains("Token=\"${FakeNas.TOKEN}\"") })
            }
        }
    }

    @Test
    fun mismatchedFingerprintStopsBeforeAnyPasswordIsSent() {
        FakeNas().use { nas ->
            expectFailure { NasConnection(nas.url, nas.ca.certificatePem(), "0".repeat(64)) { "" } }
            assertTrue(nas.requests.isEmpty())
        }
    }

    @Test
    fun loginSendsItsBodyOnlyToTheVerifiedOriginWithoutReplayingAnOldSession() {
        FakeNas().use { nas ->
            val connection = nas.connection()
            val syntheticPassword = "synthetic-password-for-test-only"
            val request = Request.Builder().url(connection.url("Users", "AuthenticateByName"))
                .post(syntheticPassword.toRequestBody()).build()
            connection.client.newCall(request).execute().use { assertEquals(200, it.code) }
            val received = nas.requests.single()
            assertFalse(received.authorization.contains(FakeNas.TOKEN))
            assertEquals(syntheticPassword, received.body)
        }
    }

    @Test
    fun wrongCaAndWrongHostnameDoNotReachHttpHandler() {
        FakeNas().use { nas ->
            val other = HeldCertificate.Builder().certificateAuthority(0).commonName("Other CA").build()
            val connection = NasConnection(nas.url, other.certificatePem(), NasConnection.fingerprint(other.certificate)) { "" }
            expectIoFailure { connection.client.newCall(Request.Builder().url(connection.url("login")).build()).execute().close() }
            assertTrue(nas.requests.isEmpty())
        }
        FakeNas(san = "wrong-host.invalid").use { nas ->
            val connection = nas.connection()
            expectIoFailure { connection.client.newCall(Request.Builder().url(connection.url("login")).build()).execute().close() }
            assertTrue(nas.requests.isEmpty())
        }
    }

    @Test
    fun expiredServerCertificateIsRejected() {
        FakeNas(expired = true).use { nas ->
            val connection = nas.connection()
            expectIoFailure { connection.client.newCall(Request.Builder().url(connection.url("login")).build()).execute().close() }
            assertTrue(nas.requests.isEmpty())
        }
    }

    @Test
    fun privateClientRefusesAnotherOriginEvenWithTheSameCa() {
        FakeNas().use { first ->
            FakeNas().use { second ->
                val connection = first.connection()
                expectIoFailure { connection.client.newCall(Request.Builder().url(second.url + "login").build()).execute().close() }
                assertTrue(first.requests.isEmpty())
                assertTrue(second.requests.isEmpty())
            }
        }
    }

    @Test
    fun redirectCannotDowngradeTlsOrReplayNasSession() {
        FakeNas().use { nas ->
            val connection = nas.connection()
            expectIoFailure { connection.client.newCall(Request.Builder().url(connection.url("redirect")).build()).execute().close() }
            assertEquals(listOf("/redirect"), nas.requests.map { it.path })
        }
    }

    @Test
    fun localProxyRefusesUnknownCapabilitiesOriginsHostRebindingAndRangeInjection() {
        FakeNas().use { nas ->
            val connection = nas.connection()
            NasStreamProxy(connection.client, connection::sameOrigin).use { proxy ->
                val local = proxy.streamUrl(connection.url("video"), "mp4")
                val player = OkHttpClient()
                player.newCall(Request.Builder().url(local).header("Origin", "https://attacker.invalid").build()).execute().use { assertEquals(403, it.code) }
                player.newCall(Request.Builder().url(local).header("Host", "attacker.invalid").build()).execute().use { assertEquals(403, it.code) }
                player.newCall(Request.Builder().url(local + "?u=https://attacker.invalid").build()).execute().use { assertEquals(404, it.code) }
                player.newCall(Request.Builder().url(local).header("Range", "bytes=0-1,50-100").build()).execute().use { assertEquals(416, it.code) }
                assertTrue(nas.requests.isEmpty())
            }
        }
    }

    @Test
    fun closingProxyRevokesTheCapability() {
        FakeNas().use { nas ->
            val connection = nas.connection()
            val proxy = NasStreamProxy(connection.client, connection::sameOrigin)
            val local = proxy.streamUrl(connection.url("video"))
            val port = local.substringAfter("127.0.0.1:").substringBefore('/').toInt()
            proxy.close()
            expectIoFailure { Socket("127.0.0.1", port).close() }
        }
    }

    @Test
    fun serverAddressCannotContainCredentialsQueriesOrPlainHttp() {
        for (address in listOf("http://127.0.0.1:48273", "https://user:password@nas.invalid", "https://nas.invalid/?token=value", "https://nas.invalid/#fragment")) {
            expectFailure { NasConnection.parseEndpoint(address) }
        }
    }

    @Test
    fun itemIdentityAndPaginationRemainIndependentOfTheServerAddress() {
        assertEquals("123abc", JellyfinJson.itemId("/Items/123abc"))
        expectFailure { JellyfinJson.itemId("/Items/../../admin") }
        val page = JellyfinJson.parse("""{"Items":[{"Id":"123abc","Name":"Clip"}],"TotalRecordCount":41}""")
        assertTrue(JellyfinJson.pageHasMore(page, 0, 40))
        assertFalse(JellyfinJson.pageHasMore(page, 40, 1))
    }

    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } catch (_: IOException) { }
    }

    private fun expectIoFailure(block: () -> Unit) {
        try { block(); fail("Expected network rejection") } catch (_: IOException) { }
    }

    private class FakeNas(san: String = "127.0.0.1", expired: Boolean = false) : Closeable {
        val ca = HeldCertificate.Builder().commonName("Synthetic test CA").certificateAuthority(0).build()
        private val leaf = HeldCertificate.Builder().commonName("Synthetic test server").addSubjectAlternativeName(san).signedBy(ca).apply {
            if (expired) validityInterval(System.currentTimeMillis() - 172_800_000, System.currentTimeMillis() - 86_400_000)
        }.build()
        private val tls = HandshakeCertificates.Builder().heldCertificate(leaf, ca.certificate).build()
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "fake-nas-test").apply { isDaemon = true } }
        private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 10).apply {
            httpsConfigurator = HttpsConfigurator(tls.sslContext())
            executor = workers
            createContext("/") { exchange -> handle(exchange) }
            start()
        }
        val requests = ConcurrentLinkedQueue<Received>()
        val video = ByteArray(64 * 1024) { (it % 251).toByte() }
        val url: String get() = "https://127.0.0.1:${server.address.port}/"

        fun connection(): NasConnection = NasConnection(url, ca.certificatePem(), NasConnection.fingerprint(ca.certificate)) { TOKEN }

        private fun handle(exchange: HttpExchange) {
            exchange.use { e ->
                requests.add(Received(e.requestURI.path, e.requestHeaders.getFirst("Authorization").orEmpty(), e.requestBody.readBytes().toString(Charsets.UTF_8)))
                if (e.requestURI.path == "/redirect") {
                    e.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/stolen")
                    e.sendResponseHeaders(302, -1); return
                }
                val range = e.requestHeaders.getFirst("Range")
                val start: Int
                val end: Int
                if (range != null) {
                    val values = range.removePrefix("bytes=").split('-')
                    start = if (values[0].isEmpty()) video.size - values[1].toInt() else values[0].toInt()
                    end = if (values[0].isEmpty() || values[1].isEmpty()) video.lastIndex else values[1].toInt().coerceAtMost(video.lastIndex)
                    e.responseHeaders.add("Content-Range", "bytes $start-$end/${video.size}")
                } else { start = 0; end = video.lastIndex }
                val length = end - start + 1
                e.responseHeaders.add("Content-Type", "video/mp4")
                e.responseHeaders.add("Accept-Ranges", "bytes")
                e.responseHeaders.add("Content-Length", length.toString())
                e.sendResponseHeaders(if (range == null) 200 else 206, if (e.requestMethod == "HEAD") -1 else length.toLong())
                if (e.requestMethod != "HEAD") e.responseBody.write(video, start, length)
            }
        }

        override fun close() { server.stop(0); workers.shutdownNow() }
        data class Received(val path: String, val authorization: String, val body: String)
        companion object { const val TOKEN = "synthetic-test-session" }
    }
}
