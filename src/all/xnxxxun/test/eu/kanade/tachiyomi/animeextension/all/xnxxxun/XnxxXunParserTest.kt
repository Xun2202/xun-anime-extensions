package eu.kanade.tachiyomi.animeextension.all.xnxxxun

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XnxxXunParserTest {
    @Test fun currentAndOlderCards() {
        val document = Jsoup.parse("""
            <div class="thumb-block thumb-cat"><div class="thumb-inside"></div></div>
            <div class="thumb-block video" id="video-thumb-abc"><a class="thumb-link" href="/video-abc/one"></a><img data-src="https://image.example/1"><a class="title" href="/video-abc/one" title="One"></a></div>
            <div class="thumb-block with-uploader"><div class="thumb-under"><p><a href="/video-def/two?tracking=1" title="Two"></a></p></div><img src="https://image.example/2"></div>
        """.trimIndent())
        val items = XnxxXunParser.listing(document)
        assertEquals(listOf("/video-abc/one", "/video-def/two"), items.map { it.path })
        assertEquals(listOf("One", "Two"), items.map { it.title })
    }

    @Test fun relatedAndSources() {
        val html = """
            <script>var video_related=[{"u":"/video-abc/one?x=1","tf":"One","i":"https://image.example/1"},
            {"u":"https://www.xnxx.com/video-def/two","t":"Two"}];
            html5player.setVideoHLS('https://hls.example/master.m3u8');
            html5player.setVideoUrlHigh('https://mp4.example/high.mp4');
            html5player.setVideoUrlLow('https://mp4.example/low.mp4');</script>
        """.trimIndent()
        assertEquals(listOf("/video-abc/one", "/video-def/two"), XnxxXunParser.related(html).map { it.path })
        val sources = XnxxXunParser.sources(html)
        assertEquals("https://hls.example/master.m3u8", sources.hls)
        assertEquals("https://mp4.example/high.mp4", sources.high)
        assertEquals("https://mp4.example/low.mp4", sources.low)
        assertNull(XnxxXunParser.sources("<html></html>").hls)
    }

    @Test fun currentDetailsAndOgTitleFallback() {
        val document = Jsoup.parse("""
            <meta property="og:title" content="Open Graph title"><meta property="og:image" content="https://image.example/cover">
            <meta name="description" content="Description">
            <div id="video-content-metadata"><div class="clear-infobar"><strong>Page title</strong><span><a href="/porn-maker/example">Uploader</a></span></div>
            <div class="video-tags"><a href="/search/one">One</a><a href="/search/two">Two</a><a class="suggestion" href="#">Suggest</a></div></div>
        """.trimIndent())
        val details = XnxxXunParser.details(document)
        assertEquals("Page title", details.title)
        assertEquals("Uploader", details.author)
        assertEquals("One, Two", details.tags)
        assertEquals("Description", details.description)

        document.selectFirst("#video-content-metadata strong")!!.text("")
        assertEquals("Open Graph title", XnxxXunParser.details(document).title)
    }

    @Test fun malformedRelatedMarkerFailsClearly() {
        assertTrue(XnxxXunParser.related("<html></html>").isEmpty())
        try {
            XnxxXunParser.related("<script>video_related={};</script>")
            throw AssertionError("Expected a format error")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("相关视频"))
        }
    }
}
