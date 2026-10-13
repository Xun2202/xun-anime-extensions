package eu.kanade.tachiyomi.animeextension.zh.hanime1xun

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

class Hanime1ParserTest {
    @Test
    fun vicinekoSearchParsesSeventeenVideosAndDropsSponsors() {
        val page = fixture("search_vicineko.html").parseListing()
        assertFalse(page.hasNextPage)
        assertEquals(VICINEKO_IDS, page.items.map { it.url.substringAfter("v=") })
        assertTrue(page.items.all { it.kind == ListingKind.VIDEO })
        assertTrue(page.items.all { it.author == "ViciNeko" })
        assertTrue(page.items.all { it.url.startsWith("/watch?v=") })
        assertTrue(page.items.all { it.title.endsWith(INVISIBLE_TITLE_SUFFIX) })
        assertEquals(
            "Mona Daily Life with Slime – Turning Up The Heat 🔥٩(｡•`◡´•｡)۶🔥",
            page.items.first().title.removeSuffix(INVISIBLE_TITLE_SUFFIX),
        )
        assertEquals("莫娜 x 空 ♥‿♥ [動畫]", page.items.last().title.removeSuffix(INVISIBLE_TITLE_SUFFIX))
        val blob = page.items.joinToString { it.title }
        assertFalse(blob.contains("慾望王座"))
        assertFalse(blob.contains("櫻境物語"))
        assertTrue(page.items.all { it.thumbnail?.contains("example.invalid") == true })
        assertTrue(page.items.none { it.thumbnail?.contains("secure=") == true })
    }

    @Test
    fun ribanPageParsesVerticalCards() {
        val page = fixture("search_riban.html").parseListing()
        assertTrue(page.hasNextPage)
        assertEquals(listOf("408297", "408310", "408316", "408315"), page.items.map { it.url.substringAfter("v=") })
        assertEquals("[中字後補] 少女彈珠汽水 8", page.items.first().title.removeSuffix(INVISIBLE_TITLE_SUFFIX))
        assertTrue(page.items.all { it.kind == ListingKind.VIDEO })
        assertTrue(page.items.all { it.url.startsWith("/watch?v=") })
        assertTrue(page.items.none { it.title.contains("erolabs") })
    }

    @Test
    fun mixedCardsKeepDocumentOrderAndDropSponsors() {
        val page = fixture("search_mixed.html").parseListing()
        assertEquals(listOf("/watch?v=11", "/watch?v=22"), page.items.map { it.url })
        assertEquals("Horizontal One", page.items[0].title.removeSuffix(INVISIBLE_TITLE_SUFFIX))
        assertEquals("HorizAuthor", page.items[0].author)
        assertEquals("Vertical Two", page.items[1].title.removeSuffix(INVISIBLE_TITLE_SUFFIX))
        assertNull(page.items[1].author)
        assertFalse(page.hasNextPage)
    }

    @Test
    fun artistSearchParsesAuthorCollectionCard() {
        val page = fixture("search_artist.html").parseListing()
        assertFalse(page.hasNextPage)
        val card = page.items.single()
        assertEquals(ListingKind.AUTHOR, card.kind)
        assertEquals("/search?query=ViciNeko", card.url)
        assertEquals("ViciNeko", card.title.removeSuffix(INVISIBLE_TITLE_SUFFIX))
        assertTrue(card.title.endsWith(INVISIBLE_TITLE_SUFFIX))
        assertEquals("ViciNeko", card.author)
        assertEquals("作者合集：17 部影片", card.description)
        assertTrue(card.thumbnail?.contains("/avatar/") == true)
        assertTrue(isAuthorCollection(card.url))
    }

    @Test
    fun authorDetailsCountVideosOnTheSearchPage() {
        val details = fixture("search_vicineko.html")
            .parseAuthorCollectionDetails("https://hanime1.me/search?query=ViciNeko")
        assertEquals("ViciNeko", details.name)
        assertEquals(17, details.videoCount)
        assertEquals("作者合集：17 部影片", details.description)
    }

    @Test
    fun authorEpisodesUseSiteOrderAndReverseNumbers() {
        val episodes = episodesFromListingPages(listOf(fixture("search_vicineko.html").parseListing()))
        assertEquals(17, episodes.size)
        assertEquals("/watch?v=408279", episodes.first().url)
        assertEquals(17f, episodes.first().episodeNumber)
        assertEquals(1f, episodes.last().episodeNumber)
        assertEquals("/watch?v=149175", episodes.last().url)
        assertFalse(episodes.first().name.endsWith(INVISIBLE_TITLE_SUFFIX))
        assertEquals(
            "Mona Daily Life with Slime – Turning Up The Heat 🔥٩(｡•`◡´•｡)۶🔥",
            episodes.first().name,
        )
    }

    @Test
    fun authorEpisodePagingDedupesAndStops() = runBlocking {
        var calls = 0
        val episodes = authorEpisodesFrom { page ->
            calls++
            assertEquals(calls, page)
            val html = if (page == 1) {
                videoCard("1", "One") + videoCard("2", "Two") + nextLink()
            } else {
                videoCard("2", "Two again") + videoCard("3", "Three")
            }
            Jsoup.parse(html, BASE)
        }
        assertEquals(2, calls)
        assertEquals(listOf("/watch?v=1", "/watch?v=2", "/watch?v=3"), episodes.map { it.url })
        assertEquals(listOf(3f, 2f, 1f), episodes.map { it.episodeNumber })
        assertEquals(listOf("One", "Two", "Three"), episodes.map { it.name })
    }

    @Test
    fun authorEpisodePagingCapsAtThirtyPages() = runBlocking {
        var calls = 0
        val episodes = authorEpisodesFrom {
            calls++
            Jsoup.parse(videoCard(calls.toString(), "Title $calls") + nextLink(), BASE)
        }
        assertEquals(MAX_AUTHOR_PAGES, calls)
        assertEquals(MAX_AUTHOR_PAGES, episodes.size)
        assertEquals(MAX_AUTHOR_PAGES.toFloat(), episodes.first().episodeNumber)
    }

    @Test
    fun cgWatchPageKeepsOnlyTheCurrentVideo() {
        val episodes = fixture("watch_3dcg.html").parseWatchEpisodes("https://hanime1.me/watch?v=408279")
        val episode = episodes.single()
        assertEquals("/watch?v=408279", episode.url)
        assertEquals("Mona Daily Life with Slime – Turning Up The Heat", episode.name)
        assertEquals(1f, episode.episodeNumber)
        assertEquals(uploadTime("2025-12-26"), episode.dateUpload)
        assertFalse(episode.name.contains("Cissia"))
    }

    @Test
    fun ribanWatchPageKeepsThePlaylist() {
        val episodes = fixture("watch_riban.html").parseWatchEpisodes("https://hanime1.me/watch?v=302")
        assertEquals(listOf("示例番劇 1", "示例番劇 2", "示例番劇 3"), episodes.map { it.name })
        assertEquals(listOf(3f, 2f, 1f), episodes.map { it.episodeNumber })
        assertEquals(listOf(0L, uploadTime("2024-01-15"), 0L), episodes.map { it.dateUpload })
        assertEquals("/watch?v=302", episodes[1].url)
    }

    @Test
    fun cupNoodleWatchPageKeepsThePlaylist() {
        val html = fixture("watch_riban.html").outerHtml().replace("裏番", "泡麵番")
        val episodes = Jsoup.parse(html, BASE).parseWatchEpisodes("https://hanime1.me/watch?v=301")
        assertEquals(3, episodes.size)
        assertEquals(uploadTime("2024-01-15"), episodes[0].dateUpload)
    }

    @Test
    fun missingTypeKeepsThePlaylist() {
        val episodes = fixture("watch_untyped.html").parseWatchEpisodes("https://hanime1.me/watch?v=401")
        assertEquals(2, episodes.size)
        assertEquals(listOf("未標 1", "未標 2"), episodes.map { it.name })
        assertEquals(uploadTime("2020-02-02"), episodes[0].dateUpload)
        assertEquals(0L, episodes[1].dateUpload)
    }

    @Test
    fun skipPageButtonCountsAsNextPage() {
        assertTrue(fixture("next_skip_button.html").hasNextListingPage())
        assertFalse(fixture("search_vicineko.html").hasNextListingPage())
        assertTrue(fixture("search_riban.html").hasNextListingPage())
    }

    @Test
    fun sourceIdMatchesOriginalHanime1Algorithm() {
        val expected = originalSourceId("Hanime1.me", "zh", 1)
        assertEquals(2853734405579058222L, expected)
        assertEquals(expected, Hanime1SourceId.generate())
        assertEquals("Hanime1.me", Hanime1SourceId.NAME)
        assertEquals("zh", Hanime1SourceId.LANG)
        assertEquals(1, Hanime1SourceId.VERSION_ID)
        val declared = Hanime1::class.java.declaredMethods.map { it.name }.toSet()
        assertFalse("id must stay on AnimeHttpSource.generateId", declared.contains("getId"))
        assertFalse("versionId must stay at the library default", declared.contains("getVersionId"))
        assertFalse(Hanime1::class.java.declaredFields.any { it.name == "id" || it.name == "versionId" })
    }

    @Test
    fun searchUrlCarriesArtistGenreTagsDateAndPage() {
        val genre = GenreFilter(arrayOf("全部", "3DCG")).apply { state = 1 }
        val year = YearFilter(arrayOf("全部年份", "2024 年")).apply { state = 1 }
        val month = MonthFilter(arrayOf("全部月份", "3 月")).apply { state = 1 }
        val tag = TagFilter("tags[]", "無碼").apply { state = true }
        val filters = AnimeFilterList(
            ArtistSearchFilter().apply { state = true },
            genre,
            DateFilter(year, month),
            TagsFilter(listOf(BroadMatchFilter().apply { state = true }, CategoryFilter("分類", listOf(tag)))),
        )
        val url = hanime1SearchUrl(BASE, 2, "vicineko", filters).toHttpUrl()
        assertEquals("vicineko", url.queryParameter("query"))
        assertEquals("artist", url.queryParameter("type"))
        assertEquals("3DCG", url.queryParameter("genre"))
        assertEquals(listOf("無碼"), url.queryParameterValues("tags[]"))
        assertEquals("on", url.queryParameter("broad"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("2024+%E5%B9%B4+3+%E6%9C%88", url.toString().substringAfter("date=").substringBefore("&"))
    }

    @Test
    fun unsetGenreIsOmittedAndAuthorUrlIsStable() {
        val url = hanime1SearchUrl(BASE, 1, "q", AnimeFilterList(GenreFilter(emptyArray()))).toHttpUrl()
        assertEquals("q", url.queryParameter("query"))
        assertNull(url.queryParameter("genre"))
        assertNull(url.queryParameter("type"))
        assertNull(url.queryParameter("page"))
        assertEquals(
            listOf("全部", "裏番", "泡麵番", "Motion Anime", "3DCG", "2.5D", "2D動畫", "AI生成", "MMD", "Cosplay", "新番預告"),
            GenreFilter(emptyArray()).values.toList(),
        )
        val cover = GenreFilter(arrayOf("", "泡麵番")).apply { state = 1 }
        assertEquals("泡麵番", cover.selected)
        assertEquals("/search?query=ViciNeko", authorCollectionUrl("ViciNeko"))
        val spaced = (BASE + authorCollectionUrl("Foo Bar")).toHttpUrl()
        assertEquals("Foo Bar", spaced.queryParameter("query"))
        assertNull(spaced.queryParameter("genre"))
        assertFalse(isAuthorCollection("/watch?v=1"))
        assertFalse(isAuthorCollection("/search"))
        assertTrue(isAuthorCollection("/search?query=ViciNeko"))
    }

    private fun fixture(name: String): Document {
        val stream = javaClass.classLoader?.getResourceAsStream(name)
            ?: error("missing fixture $name")
        return Jsoup.parse(stream.bufferedReader(Charsets.UTF_8).readText(), BASE)
    }

    private fun videoCard(id: String, title: String) = """
        <div class="horizontal-row">
          <div class="video-item-container">
            <a class="video-link" href="https://hanime1.me/watch?v=$id">
              <div class="title">$title</div>
            </a>
            <div class="subtitle">Author</div>
          </div>
        </div>
    """.trimIndent()

    private fun nextLink() = """<li class="page-item"><a class="page-link" rel="next" href="?page=2">›</a></li>"""

    private fun uploadTime(day: String): Long {
        return checkNotNull(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(day)).time
    }

    /** Copy of Anikku AnimeHttpSource.generateId, including operator precedence. */
    private fun originalSourceId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }
            .reduce(Long::or) and Long.MAX_VALUE
    }

    private companion object {
        const val BASE = "https://hanime1.me"
        val VICINEKO_IDS = listOf(
            "408279",
            "406025",
            "404552",
            "402844",
            "143578",
            "114819",
            "114489",
            "96013",
            "109812",
            "103821",
            "103567",
            "100028",
            "94956",
            "105248",
            "13312",
            "149180",
            "149175",
        )
    }
}
