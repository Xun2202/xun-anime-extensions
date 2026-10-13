package eu.kanade.tachiyomi.animeextension.all.xvideosxun

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XvideosXunParserTest {
    @Test
    fun oldMarkupReadsAnchorTitleAndMetadataAuthor() {
        val page = listing("""<div class="mozaique">${oldTile("/video.aaa/sample_clip", "Sample &amp; Clip", "Example Author")}</div>""")
        assertEquals(1, page.items.size)
        assertEquals(1, page.primaryTitleHits)
        val item = page.items.single()
        assertEquals("/video.aaa/sample_clip", item.href)
        assertEquals("Sample & Clip", item.title)
        assertEquals(TitleSource.PRIMARY, item.titleSource)
        assertEquals("Example Author", item.author)
        assertEquals("https://example.com/thumb-lazy.jpg", item.thumb)
    }

    @Test
    fun newMarkupReadsBlockTitleAndVideoMetadataAuthor() {
        val html = """
            <div class="mozaique">
              <div class="thumb-block" data-video='{"id":1,"encodedId":"abc"}'>
                <div class="thumb">
                  <a href="/video.bbb/other_clip">
                    <img src="https://example.com/other.jpg" alt="Video">
                  </a>
                </div>
                <div class="thumb-under">
                  <div class="title" title="Block Title Example">
                    <a href="/video.bbb/other_clip">ignored anchor text</a>
                  </div>
                  <div class="video-metadata">
                    <a class="name" href="/channels/example">Example Channel</a>
                  </div>
                </div>
              </div>
            </div>
        """.trimIndent()
        val item = listing(html).items.single()
        assertEquals("/video.bbb/other_clip", item.href)
        assertEquals("Block Title Example", item.title)
        assertEquals(TitleSource.BLOCK_TITLE, item.titleSource)
        assertEquals("Example Channel", item.author)
        assertEquals("https://example.com/other.jpg", item.thumb)
    }

    @Test
    fun primaryHitRateBelowHalfThrows() {
        val html = """
            <div class="mozaique">
              ${slugTile("/video.one/only_slug")}
              ${slugTile("/video.two/another_slug")}
              ${oldTile("/video.three/kept_clip", "Kept Clip", "Example Author")}
            </div>
        """.trimIndent()
        val error = assertThrows(Exception::class.java) {
            listing(html)
        }
        assertEquals(XvideosXunParser.REDESIGN_TITLE_MESSAGE, error.message)
    }

    @Test
    fun primaryHitRateAtHalfKeepsSlugFallback() {
        val html = """
            <div class="mozaique">
              ${oldTile("/video.keep/kept_clip", "Kept Clip", "Example Author")}
              ${slugTile("/video.slug/my_slug_title")}
            </div>
        """.trimIndent()
        val page = listing(html)
        assertEquals(2, page.items.size)
        assertEquals(1, page.primaryTitleHits)
        val slug = page.items[1]
        assertEquals(TitleSource.SLUG, slug.titleSource)
        assertEquals("my slug title", slug.title)
        assertEquals("/video.slug/my_slug_title", slug.href)
    }

    @Test
    fun curatedFirstPageWithNoTilesThrows() {
        val error = assertThrows(Exception::class.java) {
            listing("""<div class="mozaique"></div>""")
        }
        assertEquals(XvideosXunParser.REDESIGN_EMPTY_MESSAGE, error.message)
    }

    @Test
    fun channelTileDoesNotCountAsAVideo() {
        val html = """
            <div class="mozaique">
              <div class="thumb-block">
                <a href="/channels/example">Example Channel</a>
              </div>
            </div>
        """.trimIndent()
        val error = assertThrows(Exception::class.java) {
            listing(html)
        }
        assertEquals(XvideosXunParser.REDESIGN_EMPTY_MESSAGE, error.message)
    }

    @Test
    fun curatedLaterPageAndOptionalSearchMayBeEmpty() {
        val empty = """<div class="mozaique"></div>"""
        assertTrue(listing(empty, firstPage = false).items.isEmpty())
        assertTrue(listing(empty, kind = ListingKind.OPTIONAL).items.isEmpty())
    }

    @Test
    fun loginWallAndChallengeAreNotTreatedAsARedesign() {
        val login = listing("""<html><head><title>Please login</title></head><body><div class="mozaique"></div></body></html>""")
        val challenge = listing("""<html><head><title>Just a moment...</title></head><body><div class="mozaique"></div></body></html>""")
        assertTrue(login.items.isEmpty())
        assertTrue(challenge.items.isEmpty())
    }

    @Test
    fun titleFromSlugAndCanonicalVideoPath() {
        assertEquals("my slug title", XvideosXunParser.titleFromSlug("/video.abc/my_slug_title"))
        assertEquals("abc", XvideosXunParser.titleFromSlug("/video.abc/_/123"))
        assertEquals("example", XvideosXunParser.titleFromSlug("/channels/example"))
        assertEquals("Video", XvideosXunParser.titleFromSlug("/"))
        assertEquals(
            "/video.eid/some_slug",
            XvideosXunParser.canonicalVideoPath("https://example.com/video.eid/56819569/0/some_slug?pl=123"),
        )
        assertEquals(
            "/video.eid/some_slug",
            XvideosXunParser.canonicalVideoPath("/prof-video-click/model/someone/eid/some_slug"),
        )
        assertNull(XvideosXunParser.canonicalVideoPath("/channels/example"))
    }

    @Test
    fun jsonLdContentUrlAndPlayerRegexes() {
        val html = detailHtml()
        val ld = XvideosXunParser.extractJsonLd(html)
        assertEquals("Detail Title", ld?.name)
        assertEquals("https://cdn.example.com/media/clip.mp4", XvideosXunParser.mediaContentUrl(ld))
        val sources = XvideosXunParser.parsePlayerSources(html)
        assertEquals("https://cdn.example.com/hls/clip.m3u8", sources.hls)
        assertEquals("https://cdn.example.com/media/high.mp4", sources.high)
        assertEquals("https://cdn.example.com/media/low.mp4", sources.low)

        val pageUrl = """{"@type":"VideoObject","contentUrl":"https://example.com/watch/1"}"""
        val wrapped = """<script type="application/ld+json">$pageUrl</script>"""
        assertNull(XvideosXunParser.mediaContentUrl(XvideosXunParser.extractJsonLd(wrapped)))
    }

    @Test
    fun detailHeadingAndUploaderComeFromThePage() {
        val details = XvideosXunParser.parseVideoDetails(Jsoup.parse(detailHtml(), "https://example.com/"))
        assertEquals("Detail Title", details.title)
        assertEquals("Example Uploader", details.uploaderName)
        assertEquals("/channels/example", details.uploaderHref)
        assertEquals(listOf("Example Model"), details.models.map { it.name })
        assertEquals("01:02", details.duration?.trim())
        assertEquals("https://example.com/cover.jpg", details.thumbnailUrl)
    }

    @Test
    fun relatedBadJsonThrowsAndMissingMarkerIsEmpty() {
        val broken = assertThrows(Exception::class.java) {
            XvideosXunParser.parseRelatedVideos("<script>video_related=[{</script>")
        }
        assertEquals(XvideosXunParser.REDESIGN_RELATED_MESSAGE, broken.message)
        val objectInstead = assertThrows(Exception::class.java) {
            XvideosXunParser.parseRelatedVideos("<script>video_related={\"eid\":\"x\"}</script>")
        }
        assertEquals(XvideosXunParser.REDESIGN_RELATED_MESSAGE, objectInstead.message)
        assertFalse(XvideosXunParser.relatedMarkerPresent("<html>no recommendations</html>"))
        assertTrue(XvideosXunParser.parseRelatedVideos("<html>no recommendations</html>").isEmpty())
        assertTrue(XvideosXunParser.parseRelatedVideos("<script>video_related=[];</script>").isEmpty())
        val related = XvideosXunParser.parseRelatedVideos(detailHtml())
        assertEquals(1, related.size)
        assertEquals("abc", related.single().eid)
        assertEquals("/video.abc/related_title", related.single().u)
        assertEquals("a]b", related.single().tf)
    }

    @Test
    fun accountSectionStillPagesWhenThirtyTilesComeBack() {
        val full = listing(tiles(30), account = true, kind = ListingKind.OPTIONAL)
        val short = listing(tiles(29), account = true, kind = ListingKind.OPTIONAL)
        assertTrue(full.hasNext)
        assertFalse(short.hasNext)
        assertEquals(30, full.items.size)
    }

    private fun listing(
        html: String,
        kind: ListingKind = ListingKind.CURATED,
        firstPage: Boolean = true,
        account: Boolean = false,
    ) = XvideosXunParser.parseListing(
        Jsoup.parse(html, "https://example.com/"),
        "https://example.com",
        account,
        kind,
        firstPage,
    )

    private fun oldTile(href: String, title: String, author: String) = """
        <div class="thumb-block">
          <div class="thumb">
            <a href="$href"><img data-src="https://example.com/thumb-lazy.jpg" src="https://example.com/thumb.jpg" alt="Video"></a>
          </div>
          <p class="title"><a href="$href" title="$title">$title</a></p>
          <p class="metadata"><span class="name">$author</span></p>
        </div>
    """.trimIndent()

    private fun slugTile(href: String) = """
        <div class="thumb-block">
          <div class="thumb"><a href="$href"><img alt="Video" src="https://example.com/slug.jpg"></a></div>
        </div>
    """.trimIndent()

    private fun tiles(count: Int) = buildString {
        append("""<div class="mozaique">""")
        repeat(count) { index ->
            append(oldTile("/video.e$index/clip_$index", "Clip $index", "Example Author"))
        }
        append("</div>")
    }

    private fun detailHtml() = """
        <html>
          <head>
            <script type="application/ld+json">
              {
                "@type": "VideoObject",
                "name": "Detail Title",
                "description": "A synthetic description",
                "thumbnailUrl": ["https://example.com/cover.jpg"],
                "uploadDate": "2024-01-02T03:04:05+00:00",
                "contentUrl": "https://cdn.example.com/media/clip.mp4"
              }
            </script>
          </head>
          <body>
            <h2 class="page-title">Fallback Title <span class="duration">01:02</span></h2>
            <li class="main-uploader"><a href="/channels/example"><span class="name">Example Uploader</span></a></li>
            <li class="model"><a href="/models/example"><span class="name">Example Model</span></a></li>
            <script>
              html5player.setVideoHLS('https://cdn.example.com/hls/clip.m3u8');
              html5player.setVideoUrlHigh('https://cdn.example.com/media/high.mp4');
              html5player.setVideoUrlLow('https://cdn.example.com/media/low.mp4');
              var video_related=[{"eid":"abc","tf":"a]b","u":"/video.abc/related_title","deleted":false}];
            </script>
          </body>
        </html>
    """.trimIndent()
}
