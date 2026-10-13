package eu.kanade.tachiyomi.animeextension.en.rule34videoxun

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Hits rule34video.com only when CANARY_LIVE=1. Prints status and byte counts, never URLs.
 * A non-200 page or a challenge body is a skip.
 */
class Rule34VideoLiveCanaryTest {
    @Test
    fun liveAuthorPageResolvesHighestQualityRange() {
        assumeTrue(System.getenv("CANARY_LIVE") == "1")
        val jar = MemoryCookies()
        val client = OkHttpClient.Builder()
            .cookieJar(jar)
            .followRedirects(true)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
        val noRedirect = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

        val author = fetch(client, "$ORIGIN/models/vicineko/")
        if (isChallenge(author.code, author.body)) {
            println("canary author status=${author.code} bytes=${author.body.length}")
            assumeTrue(false)
            return
        }
        val authorDoc = Jsoup.parse(author.body, ORIGIN)
        val latest = Rule34VideoParser.parseVideoCards(authorDoc).firstOrNull()
        assertTrue("author page had no videos", latest != null)
        val details = Rule34VideoParser.parseModelDetails(authorDoc)
        assertTrue(details.videoCount == null || details.videoCount > 0)

        val videoUrl = ORIGIN + latest!!.path
        val video = fetch(client, videoUrl)
        if (isChallenge(video.code, video.body)) {
            println("canary video status=${video.code} bytes=${video.body.length}")
            assumeTrue(false)
            return
        }
        val videoDoc = Jsoup.parse(video.body, videoUrl)
        val best = Rule34VideoParser.sortQualities(
            Rule34VideoParser.parseDownloadLinks(videoDoc),
            Rule34VideoParser.QUALITY_BEST,
        ).firstOrNull()
        assertTrue("video page had no download links", best != null)
        assertTrue(best!!.label.matches(Regex("\\d{3,4}p")))
        assertEquals(best.resolution, Rule34VideoParser.parseDownloadLinks(videoDoc).maxOf { it.resolution })

        val redirect = noRedirect.newCall(
            Request.Builder()
                .url(best.fileUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", videoUrl)
                .header("Accept", "video/*,*/*")
                .build(),
        ).execute()
        val location = redirect.use { response ->
            println("canary redirect status=${response.code} quality=${best.label}")
            if (response.code !in setOf(301, 302, 303, 307, 308)) null else response.header("Location")
        }
        assertTrue("get_file did not redirect", !location.isNullOrBlank())
        val cdn = redirect.request.url.resolve(location!!) ?: error("bad location")

        val ranged = noRedirect.newCall(
            Request.Builder()
                .url(cdn)
                .header("User-Agent", USER_AGENT)
                .header("Referer", videoUrl)
                .header("Range", "bytes=0-${RANGE_BYTES - 1}")
                .build(),
        ).execute()
        val read = ranged.use { response ->
            val source = response.body.source()
            val window = Buffer()
            var count = 0L
            while (count < RANGE_BYTES && !source.exhausted()) {
                val n = source.read(window, minOf(8192, RANGE_BYTES - count))
                if (n == -1L) break
                count += n
            }
            println("canary range status=${response.code} bytes=$count")
            response.code to count
        }
        assertEquals(206, read.first)
        assertTrue(read.second >= RANGE_BYTES)
    }

    private fun fetch(client: OkHttpClient, url: String): Page {
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .build(),
        ).execute()
        return response.use { Page(it.code, it.body.string()) }
    }

    private fun isChallenge(code: Int, body: String): Boolean {
        if (code != 200 || body.length < 1000) return true
        val lower = body.lowercase()
        if ("just a moment" in lower) return true
        return "thumb_title" !in body && "get_file" !in body && "brand_image" !in body
    }

    private data class Page(val code: Int, val body: String)

    private class MemoryCookies : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain }
                this.cookies += cookie
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
    }

    companion object {
        private const val ORIGIN = "https://rule34video.com"
        private const val RANGE_BYTES = 1_048_576L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
