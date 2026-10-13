package eu.kanade.tachiyomi.animeextension.en.rule34videoxun

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Rule34VideoParserTest {
    private val base = "https://rule34video.com"

    @Test
    fun sourceIdMatchesUpstreamRule34Video() {
        assertEquals("Rule34Video", Rule34VideoParser.SOURCE_NAME)
        assertEquals("en", Rule34VideoParser.SOURCE_LANG)
        assertEquals(1, Rule34VideoParser.SOURCE_VERSION_ID)
        assertEquals(
            Rule34VideoParser.SOURCE_ID,
            Rule34VideoParser.generateSourceId(
                Rule34VideoParser.SOURCE_NAME,
                Rule34VideoParser.SOURCE_LANG,
                Rule34VideoParser.SOURCE_VERSION_ID,
            ),
        )
        assertEquals(9212790518101043860L, Rule34VideoParser.SOURCE_ID)
    }

    @Test
    fun searchPageReadsVideosAndSkipsPromo() {
        val page = doc("search.html")
        val cards = Rule34VideoParser.parseVideoCards(page)
        assertEquals(listOf(111L, 222L), cards.map { it.videoId })
        assertEquals("Sample One", cards[0].title)
        assertEquals("/video/111/sample-one/", cards[0].path)
        assertEquals("https://example.com/thumbs/111.jpg", cards[0].thumbnail)
        assertEquals("https://example.com/thumbs/222.jpg", cards[1].thumbnail)
        assertEquals("Submitted: 2 days ago", cards[0].submitted)
        assertTrue(Rule34VideoParser.hasNextPage(page))
    }

    @Test
    fun modelListReadsAvatarAndSlug() {
        val cards = Rule34VideoParser.parseModelCards(doc("models-list.html"))
        assertEquals(2, cards.size)
        assertEquals("vicineko", cards[0].dir)
        assertEquals("ViciNeko", cards[0].name)
        assertEquals("/models/vicineko/", cards[0].path)
        assertEquals("https://example.com/avatars/vicineko.jpg", cards[0].thumbnail)
        assertEquals("theobrobine", cards[1].dir)
        assertTrue(Rule34VideoParser.hasNextPage(doc("models-list.html")))
    }

    @Test
    fun modelsJsonReadsAuthorSearch() {
        val cards = Rule34VideoParser.parseModelsJson(text("models.json"))
        assertEquals(listOf("vicineko", "viciousfox"), cards.map { it.dir })
        assertEquals("ViciNeko", cards[0].name)
        assertNull(cards[0].thumbnail)
        assertEquals("/models/viciousfox/", cards[1].path)
    }

    @Test
    fun authorPagesKeepWipAndNumberNewestFirst() {
        val first = doc("model-page.html")
        val details = Rule34VideoParser.parseModelDetails(first)
        assertEquals("ViciNeko", details.name)
        assertEquals(3, details.videoCount)
        assertEquals(2, details.pageCount)
        assertEquals("作者合集：3 个视频", details.description)
        assertEquals("https://example.com/avatars/vicineko.jpg", details.avatar)
        assertTrue(Rule34VideoParser.isModelDocument(first))

        val now = 1_700_000_000_000L
        val episodes = Rule34VideoParser.episodesFrom(
            Rule34VideoParser.parseVideoCards(first) + Rule34VideoParser.parseVideoCards(doc("model-page-2.html")),
            now,
        )
        assertEquals(listOf("Newest Clip WIP", "Slime Compilation", "Oldest Clip"), episodes.map { it.name })
        assertEquals(listOf(3f, 2f, 1f), episodes.map { it.episodeNumber })
        assertEquals("/video/300/newest-clip/", episodes[0].url)
        assertTrue(episodes[0].dateUpload > episodes[1].dateUpload)
        assertTrue(episodes[1].dateUpload > episodes[2].dateUpload)
        assertEquals(now - 7 * 86_400_000L, episodes[0].dateUpload)
        assertFalse(Rule34VideoParser.hasNextPage(doc("model-page-2.html")))
    }

    @Test
    fun videoPageListsHighestQualityFirstAndFillsAuthor() {
        val page = doc("video.html")
        val details = Rule34VideoParser.parseVideoDetails(page)
        assertEquals("Sample Clip", details.title)
        assertEquals("ViciNeko", details.author)
        assertTrue(details.description.contains("Other Artist"))
        assertTrue(details.description.contains("Uploader: UploaderName"))
        assertTrue(details.description.contains("https://www.patreon.com/Example"))
        assertEquals("animation, blender", details.genres)
        assertEquals(Rule34VideoParser.parseIsoDate("2026-07-29"), details.uploadDate)
        assertFalse(Rule34VideoParser.isModelDocument(page))

        val links = Rule34VideoParser.parseDownloadLinks(page)
        assertEquals(listOf(720, 2160, 360, 1080, 480, 1440), links.map { it.resolution })
        links.forEach { link ->
            assertEquals("${link.resolution}p", link.label)
            assertFalse(link.fileUrl.contains("download="))
            assertFalse(link.fileUrl.contains("download_filename"))
            assertTrue(link.fileUrl.contains("v-acctoken=fake"))
        }
        val best = Rule34VideoParser.sortQualities(links, Rule34VideoParser.QUALITY_BEST)
        assertEquals(listOf(2160, 1440, 1080, 720, 480, 360), best.map { it.resolution })
        val prefer720 = Rule34VideoParser.sortQualities(links, "720p")
        assertEquals(720, prefer720.first().resolution)
        assertEquals(listOf(2160, 1440, 1080, 480, 360), prefer720.drop(1).map { it.resolution })
    }

    @Test
    fun exactSlugAndCollectionUrl() {
        assertEquals("vicineko", Rule34VideoParser.exactModelSlug("ViciNeko"))
        assertEquals("general-butch", Rule34VideoParser.exactModelSlug("general butch"))
        assertNull(Rule34VideoParser.exactModelSlug("a/b"))
        assertEquals(
            "$base/models/vicineko/",
            Rule34VideoParser.modelCollectionUrl(base, "vicineko", "post_date", null, null),
        )
        assertEquals(
            "$base/models/vicineko/?sort_by=duration&duration_from=1200",
            Rule34VideoParser.modelCollectionUrl(base, "vicineko", "duration", "1200", null),
        )
        val page2 = Rule34VideoParser.modelPageUrl("$base/models/vicineko/?sort_by=video_viewed", 2)
        assertTrue(page2.startsWith("$base/models/vicineko/?"))
        assertTrue(page2.contains("from=2"))
        assertTrue(page2.contains("sort_by=video_viewed"))
        val search = Rule34VideoParser.videoSearchUrl(base, "vici neko", 2, "post_date", "", "", "0", "60")
        assertTrue(search.startsWith("$base/search/vici-neko/"))
        assertTrue(search.contains("from_videos=2"))
        assertTrue(search.contains("sort_by=post_date"))
        assertTrue(search.contains("duration_from=0"))
        assertTrue(search.contains("duration_to=60"))
        assertEquals("$base/models/?from=2", Rule34VideoParser.modelBrowseUrl(base, 2))
        assertEquals("$base/models/", Rule34VideoParser.modelBrowseUrl(base, 1))
        val json = Rule34VideoParser.modelsJsonUrl(base, "vici", 1)
        assertTrue(json.contains("q=vici"))
        assertTrue(json.contains("page=1"))
    }

    @Test
    fun pastedLinks() {
        val model = Rule34VideoParser.pasted("https://rule34video.com/models/vicineko/?from=2")
        assertTrue(model is Rule34VideoParser.Pasted.Model)
        assertEquals("vicineko", (model as Rule34VideoParser.Pasted.Model).dir)
        val video = Rule34VideoParser.pasted("https://www.rule34video.com/video/111/sample-one")
        assertTrue(video is Rule34VideoParser.Pasted.Video)
        assertEquals("/video/111/sample-one/", (video as Rule34VideoParser.Pasted.Video).path)
        assertNull(Rule34VideoParser.pasted("https://example.com/models/vicineko/"))
        assertTrue(Rule34VideoParser.isModelEntry("/models/vicineko/?sort_by=duration"))
        assertFalse(Rule34VideoParser.isModelEntry("/models/"))
        assertFalse(Rule34VideoParser.isModelEntry("/video/111/sample-one/"))
    }

    private fun text(name: String): String {
        return javaClass.classLoader.getResource(name)?.readText()
            ?: error("missing fixture $name")
    }

    private fun doc(name: String) = Jsoup.parse(text(name), base)
}
