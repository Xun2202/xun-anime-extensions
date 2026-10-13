package eu.kanade.tachiyomi.animeextension.all.rouxun

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class RouXunServerFnsTest {

    @Test
    fun discoverPairsAllEightKeys() {
        val js = buildString {
            append(route("home", 'a'))
            append(route("v._id.edit", '9'))
            append(route("v.index", 'b'))
            append(route("search", 'c'))
            append(route("series", 'd'))
            append(route("s._id", 'e'))
            append(route("t._id", 'f'))
            append(route("cat", '1'))
            append(route("admin.index", '8'))
            append(detail('7'))
        }
        val found = RouXunServerFns.discover(js)
        assertEquals(FnKey.entries.toSet(), found.keys)
        assertEquals(hex('a'), found[FnKey.HOME])
        assertEquals(hex('b'), found[FnKey.VIDEO_LIST])
        assertEquals(hex('c'), found[FnKey.SEARCH])
        assertEquals(hex('d'), found[FnKey.SERIES_LIST])
        assertEquals(hex('e'), found[FnKey.SERIES_DETAIL])
        assertEquals(hex('f'), found[FnKey.TAG])
        assertEquals(hex('1'), found[FnKey.CAT])
        assertEquals(hex('7'), found[FnKey.VIDEO_DETAIL])
    }

    @Test
    fun discoverReturnsOnlyRoutesThatArePresent() {
        val js = route("home", 'a') + route("cat", 'b')
        val found = RouXunServerFns.discover(js)
        assertEquals(setOf(FnKey.HOME, FnKey.CAT), found.keys)
        assertEquals(hex('a'), found[FnKey.HOME])
        assertNull(found[FnKey.VIDEO_LIST])
        assertNull(found[FnKey.VIDEO_DETAIL])
    }

    @Test
    fun discoverIgnoresUtOutsideTheWindow() {
        val id = hex('a')
        // 400 sits past the 200-char pairing window and short of the next route's ut (~450 in the live bundle).
        val js = """import("./home-abc.js")""" + "x".repeat(400) + """ut("$id")"""
        assertNull(RouXunServerFns.discover(js)[FnKey.HOME])
    }

    @Test
    fun discoverRequiresTheShiftedCharCodeMarkerForVideoDetail() {
        val plain = """function p(t){const a=atob(t);return a.charCodeAt(0)}const g=rt().handler(ut("${hex('a')}"))"""
        assertNull(RouXunServerFns.discover(plain)[FnKey.VIDEO_DETAIL])
        assertEquals(hex('b'), RouXunServerFns.discover(detail('b'))[FnKey.VIDEO_DETAIL])
    }

    @Test
    fun discoverLowercasesHex() {
        val upper = "A".repeat(64)
        val found = RouXunServerFns.discover(route("home", upper))
        assertEquals(upper.lowercase(), found[FnKey.HOME])
    }

    @Test
    fun indexScriptPathsPreferTheEntryScript() {
        val html = """
            <link rel="modulepreload" href="/assets/index-tiny.js"/>
            <script type="module" src="/assets/index-main.js"></script>
            <link rel="modulepreload" href="/assets/index-other.js"/>
        """.trimIndent()
        assertEquals(
            listOf("/assets/index-main.js", "/assets/index-tiny.js", "/assets/index-other.js"),
            RouXunServerFns.indexScriptPaths(html),
        )
    }

    @Test
    fun fallbackIdsCoverEveryKey() {
        assertEquals(FnKey.entries.toSet(), RouXunServerFns.FALLBACK.keys)
        RouXunServerFns.FALLBACK.values.forEach { id ->
            assertTrue(RouXunServerFns.isHex64(id))
        }
    }

    @Test
    fun emptyFirstPageOfAListingIsAnError() {
        val error = runCatching { ensureListing(page = 1, count = 0, rejectEmptyFirstPage = true) }.exceptionOrNull()
        assertEquals(RouXunServerFns.MSG_FORMAT_CHANGED, error?.message)
    }

    @Test
    fun laterPagesAndOptionalQueriesMayBeEmpty() {
        ensureListing(page = 2, count = 0, rejectEmptyFirstPage = true)
        ensureListing(page = 99999, count = 0, rejectEmptyFirstPage = true)
        ensureListing(page = 1, count = 0, rejectEmptyFirstPage = false)
        ensureListing(page = 1, count = 3, rejectEmptyFirstPage = true)
    }

    @Test
    fun staleServerFnIsHttp500WithoutTheSerializationHeader() {
        assertTrue(response(500, header = null).isStaleServerFn())
        assertFalse(response(500, header = "true").isStaleServerFn())
        assertFalse(response(200, header = null).isStaleServerFn())
        assertFalse(response(200, header = "true").isStaleServerFn())
    }

    @Test
    fun persistedIdsSkipTheNetwork() {
        val stored = FnKey.entries.associateWith { hex('c') }
        var fetches = 0
        val catalog = ServerFnCatalog(
            discoverFromNetwork = {
                fetches += 1
                emptyMap()
            },
            readCache = { stored },
            writeCache = { _ -> error("persisted ids should not be rewritten") },
        )
        assertEquals(hex('c'), catalog.resolve(FnKey.VIDEO_LIST).id)
        assertEquals(0, fetches)
    }

    @Test
    fun failedDiscoveryUsesFallbackAndDoesNotPersist() {
        var writes = 0
        val catalog = ServerFnCatalog(
            discoverFromNetwork = { emptyMap() },
            readCache = { null },
            writeCache = { _ -> writes += 1 },
        )
        assertEquals(RouXunServerFns.FALLBACK.getValue(FnKey.CAT), catalog.resolve(FnKey.CAT).id)
        assertEquals(0, writes)
    }

    @Test
    fun partialDiscoveryFillsGapsFromFallback() {
        var saved: Map<FnKey, String>? = null
        val catalog = ServerFnCatalog(
            discoverFromNetwork = { mapOf(FnKey.HOME to hex('a')) },
            readCache = { null },
            writeCache = { saved = it },
        )
        assertEquals(hex('a'), catalog.resolve(FnKey.HOME).id)
        assertEquals(RouXunServerFns.FALLBACK.getValue(FnKey.SEARCH), catalog.resolve(FnKey.SEARCH).id)
        assertEquals(hex('a'), saved?.get(FnKey.HOME))
        assertEquals(RouXunServerFns.FALLBACK.getValue(FnKey.SEARCH), saved?.get(FnKey.SEARCH))
        assertEquals(FnKey.entries.size, saved?.size)
    }

    @Test
    fun concurrentRefreshDownloadsOncePerEpoch() {
        val calls = AtomicInteger()
        val catalog = ServerFnCatalog(
            discoverFromNetwork = {
                val n = calls.incrementAndGet()
                Thread.sleep(80)
                mapOf(FnKey.HOME to hex(if (n == 1) 'a' else 'b'))
            },
            readCache = { null },
            writeCache = { _ -> },
        )
        assertEquals(hex('a'), catalog.resolve(FnKey.HOME).id)
        val seen = catalog.epoch
        val start = CountDownLatch(1)
        val threads = List(6) {
            Thread {
                start.await()
                catalog.refreshIfStale(seen)
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        catalog.refreshIfStale(seen)
        assertEquals(hex('b'), catalog.resolve(FnKey.HOME).id)
        assertEquals(2, calls.get())
    }

    private fun route(name: String, id: Char) = route(name, hex(id))

    private fun route(name: String, id: String) =
        """import("./$name-abc.js"),__vite__mapDeps([0,1])),x=rt().handler(ut("$id"))"""

    private fun detail(id: Char) = detail(hex(id))

    private fun detail(id: String) =
        "function pL(t,a){const i=atob(t).split(\"\").map(s=>String.fromCharCode(s.charCodeAt(0)-a)).join(\"\");" +
            "return JSON.parse(i)}const gL=rt().handler(ut(\"$id\"))"

    private fun hex(ch: Char) = ch.toString().repeat(64)

    private fun response(code: Int, header: String?): Response {
        val builder = Response.Builder()
            .request(Request.Builder().url("https://rou.video/_serverFn/abc").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body("{}".toResponseBody(null))
        if (header != null) builder.header("x-tss-serialized", header)
        return builder.build()
    }
}
