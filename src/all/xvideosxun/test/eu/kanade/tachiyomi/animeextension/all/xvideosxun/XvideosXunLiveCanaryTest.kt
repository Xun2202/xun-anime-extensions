package eu.kanade.tachiyomi.animeextension.all.xvideosxun

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Hits the public site only when CANARY_LIVE=1. Prints counts, never titles or URLs.
 * A non-200 response or a bot-challenge page is a skip.
 */
class XvideosXunLiveCanaryTest {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    @Test
    fun liveListingsAndDetailStillMatch() {
        assumeTrue(System.getenv("CANARY_LIVE") == "1")
        val home = fetch("$ORIGIN/")
        val homePage = assertListing("home", home.document, firstPage = true)
        val newest = fetch("$ORIGIN/new/1")
        assertListing("new", newest.document, firstPage = false)
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(
            Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.time,
        )
        val best = fetch("$ORIGIN/best/$month/0")
        assertListing("best", best.document, firstPage = true)

        val detailPath = homePage.items.first().href
        val detail = fetch(ORIGIN + detailPath)
        val sources = XvideosXunParser.parsePlayerSources(detail.html)
        val contentUrl = XvideosXunParser.mediaContentUrl(XvideosXunParser.extractJsonLd(detail.document)) != null
        val hasHls = !sources.hls.isNullOrBlank()
        val hasHigh = !sources.high.isNullOrBlank()
        val hasLow = !sources.low.isNullOrBlank()
        println("canary detail hls=$hasHls high=$hasHigh low=$hasLow contentUrl=$contentUrl")
        assertTrue(hasHls || hasHigh || hasLow || contentUrl)
        assertTrue(XvideosXunParser.relatedMarkerPresent(detail.html))
        val related = XvideosXunParser.parseRelatedVideos(detail.html)
        println("canary related count=${related.size}")
    }

    private fun assertListing(label: String, document: Document, firstPage: Boolean): ListingPage {
        val page = XvideosXunParser.parseListing(
            document,
            ORIGIN,
            accountSection = false,
            kind = ListingKind.CURATED,
            firstPage = firstPage,
        )
        println("canary $label count=${page.items.size} primary=${page.primaryTitleHits}")
        assertTrue(page.items.isNotEmpty())
        assertTrue(page.primaryTitleHits * 2 >= page.items.size)
        return page
    }

    private fun fetch(url: String): Page {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != 200) {
                println("canary skip http=${response.code}")
                assumeTrue(false)
            }
            val html = response.body.string()
            val document = Jsoup.parse(html, url)
            val title = document.title()
            if (title.contains("challenge", ignoreCase = true) || title.contains("Just a moment", ignoreCase = true)) {
                println("canary skip challenge")
                assumeTrue(false)
            }
            return Page(html, document)
        }
    }

    private class Page(val html: String, val document: Document)

    private companion object {
        const val ORIGIN = "https://www.xvideos.com"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
