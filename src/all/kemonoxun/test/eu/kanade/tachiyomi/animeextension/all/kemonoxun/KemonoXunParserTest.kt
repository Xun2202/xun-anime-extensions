package eu.kanade.tachiyomi.animeextension.all.kemonoxun

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.nio.file.Files

class KemonoXunParserTest {
    @Test
    fun liveCreatorsDropDiscordAndSort() {
        val creators = KemonoXunParser.parseCreators(text("creators-sample.json"))
        assertEquals(7, creators.size)
        assertTrue(creators.any { it.service == "discord" })
        val page = KemonoXunParser.browseCreators(creators, page = 1)
        assertEquals(6, page.creators.size)
        assertFalse(page.hasNextPage)
        assertTrue(page.creators.none { it.service == "discord" })
        assertEquals("ViciNeko", page.creators.first().name)
        assertEquals(101529, page.creators.first().favorited)
        assertEquals("とらいし666", KemonoXunParser.browseCreators(creators, 1, sort = KemonoXunParser.CreatorSort.UPDATED).creators.first().name)

        val paged = KemonoXunParser.browseCreators(creators, page = 1, pageSize = 2)
        assertEquals(listOf("ViciNeko", "日南(Canan)"), paged.creators.map { it.name })
        assertTrue(paged.hasNextPage)
        val last = KemonoXunParser.browseCreators(creators, page = 4, pageSize = 2)
        assertTrue(last.creators.isEmpty())
        assertFalse(last.hasNextPage)

        assertEquals(listOf("ViciNeko"), KemonoXunParser.browseCreators(creators, 1, query = "neko").creators.map { it.name })
        assertEquals(
            listOf("日南(Canan)"),
            KemonoXunParser.browseCreators(creators, 1, includeServices = setOf("fantia")).creators.map { it.name },
        )
        val noPatreon = KemonoXunParser.browseCreators(creators, 1, excludeServices = setOf("Patreon")).creators
        assertTrue(noPatreon.none { it.service == "patreon" })
        assertTrue(noPatreon.any { it.service == "subscribestar" })
        assertTrue(KemonoXunParser.browseCreators(creators, 1, includeServices = setOf("discord")).creators.isEmpty())
    }

    @Test
    fun updatedAcceptsNumberStringAndIsoAndSkipsABrokenRow() {
        val numeric = KemonoXunParser.parseCreators(
            """[{"id":"49965584","name":"ViciNeko","service":"patreon","favorited":3,"updated":"1771540552","indexed":1}]""",
        )
        assertEquals(1771540552L, numeric.single().updated)
        val iso = KemonoXunParser.parseCreators(
            """[{"id":9,"name":"Num","service":"fanbox","favorited":1,"updated":"2026-02-19T22:35:51"}]""",
        )
        assertEquals("9", iso.single().id)
        assertTrue(iso.single().updated > 1_700_000_000L)
        val mixed = KemonoXunParser.parseCreators(
            """[
              {"id":"ok","name":"Ok","service":"patreon","favorited":2,"updated":10},
              {"id":{"no":true},"name":"bad","service":"patreon","updated":1}
            ]""",
        )
        assertEquals(listOf("ok"), mixed.map { it.id })
        assertEquals(2, mixed.single().favorited)
    }

    @Test
    fun vicinekoPageKeepsOnlyVideoPosts() {
        val posts = KemonoXunParser.parsePosts(text("posts-vicineko.json"))
        assertEquals(5, posts.size)
        assertFalse(KemonoXunParser.hasVideo(posts.first { it.id == "146624337" }))
        assertFalse(KemonoXunParser.hasVideo(posts.first { it.id == "140748650" }))
        assertFalse(KemonoXunParser.hasVideo(posts.first { it.id == "133360805" }))
        assertTrue(KemonoXunParser.hasVideo(posts.first { it.id == "135543259" }))
        val episodes = KemonoXunParser.videoEpisodes(posts, KemonoXunParser.DEFAULT_BASE)
        assertEquals(listOf("150370074", "135543259"), episodes.map { it.path.substringAfterLast('/') })
        assertEquals(2f, episodes.first().episodeNumber)
        assertEquals(1f, episodes.last().episodeNumber)
        assertTrue(episodes.first().name.contains("Wrapped In Slime"))
        assertFalse(episodes.first().name.contains("\n"))
        assertTrue(episodes.first().dateUpload > episodes.last().dateUpload)
        assertTrue(episodes.first().preview!!.contains("https://img.kemono.cr/thumbnail/data/"))
        assertTrue(episodes.first().preview!!.endsWith(".gif"))
        val entry = KemonoXunParser.entryFromCreator(KemonoXunParser.parseCreators(text("creators-sample.json")).first(), KemonoXunParser.DEFAULT_BASE)!!
        assertEquals("/patreon/user/49965584", entry.path)
        assertEquals("https://img.kemono.cr/icons/patreon/49965584", entry.thumbnail)
        assertEquals("https://img.kemono.cr/banners/patreon/49965584", entry.background)
        assertEquals("Patreon", entry.author)
    }

    @Test
    fun detailListsBothVideosOnceAndPrefers4k() {
        val detail = KemonoXunParser.parseDetail(text("post-wrapped-slime.json"))
        assertEquals("150370074", detail.post!!.id)
        assertEquals(listOf("4k", "Mona", "Slime", "WIP"), detail.post!!.tags)
        val links = KemonoXunParser.videosFromDetail(detail, "https://kemono.cr/")
        assertEquals(2, links.size)
        assertEquals(2160, links[0].resolution)
        assertTrue(links[0].label.contains("4K_h265.mp4"))
        assertEquals(1080, links[1].resolution)
        assertTrue(links[0].url.startsWith("https://n4.kemono.cr/data/52/29/"))
        assertTrue(links[1].url.startsWith("https://n2.kemono.cr/data/b0/07/"))
        assertTrue(links[0].url.contains(".mp4?f=MdlwS_WIP8_WrappedInSlime_4K_h265.mp4"))
        assertTrue(links.none { it.url.contains(".gif") })
        val episode = KemonoXunParser.episodeFromDetail(detail, KemonoXunParser.DEFAULT_BASE)!!
        assertEquals(1f, episode.episodeNumber)
        assertEquals("/patreon/user/49965584/post/150370074", episode.path)
        val withFraction = KemonoXunParser.publishedMillis("2021-03-31T11:08:38.529507")
        val whole = KemonoXunParser.publishedMillis("2021-03-31T11:08:38")
        assertEquals(529L, withFraction - whole)
        assertEquals(
            KemonoXunParser.publishedMillis("2026-02-10T03:11:14"),
            KemonoXunParser.publishedMillis(detail.post!!.published),
        )
    }

    @Test
    fun gumroadAlphanumericPostAndSearchDropsZip() {
        val gumroad = KemonoXunParser.parsePosts(text("posts-gumroad.json")).single()
        assertEquals("pvdbku", gumroad.id)
        assertEquals("gumroad", gumroad.service)
        assertTrue(KemonoXunParser.hasVideo(gumroad))
        val card = KemonoXunParser.entryFromPost(gumroad, KemonoXunParser.DEFAULT_BASE, requireVideo = true)!!
        assertEquals("/gumroad/user/3267960360326/post/pvdbku", card.path)
        assertTrue(card.singlePost)
        val search = KemonoXunParser.parsePosts(text("posts-search.json"))
        assertEquals(2, search.size)
        val recent = search.mapNotNull { KemonoXunParser.entryFromPost(it, KemonoXunParser.DEFAULT_BASE, requireVideo = true) }
        assertEquals(listOf("136039847"), recent.map { it.path.substringAfterLast('/') })
        assertFalse(KemonoXunParser.recentHasNext(search.size))
        assertTrue(KemonoXunParser.recentHasNext(KemonoXunParser.PAGE_SIZE))
        assertEquals("mp4", KemonoXunParser.recentQuery("  "))
        assertEquals("webm", KemonoXunParser.recentQuery(" webm "))
    }

    @Test
    fun offsetErrorsHtmlAndHttpMessages() {
        assertTrue(KemonoXunParser.isOffsetError(400, text("offset-error.json")))
        assertTrue(KemonoXunParser.isOffsetError(400, text("offset-not-multiple.json")))
        assertFalse(KemonoXunParser.isOffsetError(200, text("offset-error.json")))
        assertFalse(KemonoXunParser.isOffsetError(400, """{"error":"no such user"}"""))
        assertTrue(KemonoXunParser.parsePosts(text("posts-empty.json")).isEmpty())
        val html = """<!DOCTYPE html><html><body>DDoS-Guard</body></html>"""
        val htmlError = runCatching { KemonoXunParser.parseCreators(html) }.exceptionOrNull()
        assertTrue(htmlError!!.message!!.contains("网页"))
        assertTrue(htmlError.message!!.contains("HTML"))
        val forbidden = runCatching {
            KemonoXunParser.ensureOk(403, """If you want to scrape, use "Accept: text/css" header""")
        }.exceptionOrNull()
        assertTrue(forbidden!!.message!!.contains("403"))
        assertTrue(forbidden.message!!.contains("WebView"))
        val rate = runCatching { KemonoXunParser.ensureOk(429, "") }.exceptionOrNull()
        assertTrue(rate!!.message!!.contains("429"))
        assertEquals(2_000L, KemonoXunParser.retryDelayMillis(0))
        assertEquals(4_000L, KemonoXunParser.retryDelayMillis(1))
        assertEquals(8_000L, KemonoXunParser.retryDelayMillis(2))
        assertTrue(KemonoXunParser.shouldRetryStatus(429, 2))
        assertFalse(KemonoXunParser.shouldRetryStatus(429, 3))
        assertFalse(KemonoXunParser.shouldRetryStatus(500, 0))
        assertEquals(KemonoXunParser.TIMEOUT_MESSAGE, KemonoXunParser.transportMessage(SocketTimeoutException("timeout")))
        assertEquals(
            KemonoXunParser.TIMEOUT_MESSAGE,
            KemonoXunParser.transportMessage(IllegalStateException("wrap", SocketTimeoutException())),
        )
        assertTrue(KemonoXunParser.HOST_MESSAGE.contains("服务器"))
    }

    @Test
    fun linksBaseUrlCacheAndPaging() {
        assertEquals(
            KemonoXunParser.DirectLink.User("patreon", "49965584"),
            KemonoXunParser.parseDirectLink("https://kemono.cr/patreon/user/49965584?o=1"),
        )
        assertEquals(
            KemonoXunParser.DirectLink.Post("patreon", "49965584", "150370074"),
            KemonoXunParser.parseDirectLink("https://kemono.cr/patreon/user/49965584/post/150370074/"),
        )
        assertEquals(
            KemonoXunParser.DirectLink.User("onlyfans", "ynna.sertion"),
            KemonoXunParser.parseDirectLink("https://coomer.st/onlyfans/user/ynna.sertion"),
        )
        assertEquals(
            KemonoXunParser.DirectLink.Post("gumroad", "3267960360326", "pvdbku"),
            KemonoXunParser.parseDirectLink("/gumroad/user/3267960360326/post/pvdbku"),
        )
        assertNull(KemonoXunParser.parseDirectLink("vicineko"))
        assertEquals("https://kemono.cr", KemonoXunParser.normalizeBaseUrl(" https://kemono.cr/ "))
        assertNull(KemonoXunParser.normalizeBaseUrl("http://kemono.cr"))
        assertNull(KemonoXunParser.normalizeBaseUrl("https://kemono.cr/api"))
        assertEquals("https://coomer.st", KemonoXunParser.effectiveBase("https://kemono.cr", "https://coomer.st"))
        assertEquals("https://kemono.cr", KemonoXunParser.effectiveBase("https://kemono.cr", "   "))
        assertEquals("https://kemono.cr", KemonoXunParser.effectiveBase("nope", null))
        assertEquals(10, KemonoXunParser.maxPages(null))
        assertEquals(100, KemonoXunParser.maxPages("500"))
        assertEquals(1, KemonoXunParser.maxPages("0"))
        assertEquals(
            "https://kemono.cr/data/aa/bb/file.mp4?f=my%20file.mp4",
            KemonoXunParser.fileUrl("https://kemono.cr", "aa/bb/file.mp4", "my file.mp4"),
        )
        assertEquals("https://img.coomer.st", KemonoXunParser.imageOrigin("https://coomer.st"))
        assertTrue(KemonoXunParser.isVideo(KemonoXunParser.MediaFile(name = "clip.m4v", path = "/aa/bb/clip.m4v")))
        assertFalse(KemonoXunParser.isVideo(KemonoXunParser.MediaFile(name = "pack.zip", path = "/aa/bb/pack.bin")))
        assertFalse(KemonoXunParser.isVideo(KemonoXunParser.MediaFile(name = "a.psd", path = "/aa/bb/a.psd")))
        assertEquals(
            "mp4",
            KemonoXunParser.mediaExtension(KemonoXunParser.MediaFile(name = "a.bin", path = "/x.bin", extension = ".mp4")),
        )
        val plain = KemonoXunParser.plainText("<p>Hello<br>there &amp; me</p>")
        assertTrue(plain.contains("Hello"))
        assertTrue(plain.contains("there"))
        assertTrue(plain.contains("&"))
        assertFalse(plain.contains("<"))
        val note = KemonoXunParser.creatorDescription(
            base = "https://kemono.cr",
            service = "patreon",
            id = "49965584",
            name = "ViciNeko",
            postCount = 345,
            videoPosts = 0,
            pagesLoaded = 1,
            maxPages = 10,
        )
        assertTrue(note.contains("https://kemono.cr/patreon/user/49965584"))
        assertTrue(note.contains(KemonoXunParser.NO_VIDEO_NOTE))
        assertTrue(note.contains(KemonoXunParser.FILE_HOST_NOTE))
        val profile = KemonoXunParser.parseProfile(
            """{"id":"49965584","name":"ViciNeko","service":"patreon","indexed":"2021-05-12T04:41:47.970437","updated":"2026-02-19T22:35:51.538980","public_id":"ViciNeko","post_count":345}""",
        )
        assertEquals(345, profile.postCount)
        assertEquals("ViciNeko", profile.name)

        val dir = Files.createTempDirectory("kemonoxun").toFile()
        val cache = KemonoXunParser.CreatorCache(dir, ttlMillis = 1_000)
        val creators = KemonoXunParser.parseCreators(text("creators-sample.json"))
        cache.write("https://kemono.cr", text("creators-sample.json"), creators, now = 5_000)
        assertEquals(7, cache.read("https://kemono.cr", now = 5_500)!!.size)
        val restarted = KemonoXunParser.CreatorCache(dir, ttlMillis = 1_000)
        assertEquals("ViciNeko", restarted.read("https://kemono.cr", now = 5_900)!!.first { it.id == "49965584" }.name)
        assertNull(restarted.read("https://kemono.cr", now = 6_001))
        cache.write("https://coomer.st", """[{"id":"1","name":"Other","service":"onlyfans","updated":1}]""", KemonoXunParser.parseCreators("""[{"id":"1","name":"Other","service":"onlyfans","updated":1}]"""), now = 9_000)
        assertEquals("Other", cache.read("https://coomer.st", now = 9_100)!!.single().name)
        java.io.File(dir, "https___kemono.cr.json").writeText("not-json")
        java.io.File(dir, "https___kemono.cr.stamp").writeText("9000")
        assertNull(KemonoXunParser.CreatorCache(dir, ttlMillis = 10_000).read("https://kemono.cr", now = 9_100))

        val template = KemonoXunParser.parsePosts(text("posts-vicineko.json")).first()
        val calls = mutableListOf<Int>()
        val capped = runBlocking {
            KemonoXunParser.walkPosts(3) { offset ->
                calls += offset
                KemonoXunParser.PostBatch.Page(List(KemonoXunParser.PAGE_SIZE) { index -> template.copy(id = "${offset}_$index") })
            }
        }
        assertEquals(listOf(0, 50, 100), calls)
        assertEquals(150, capped.posts.size)
        val shortCalls = mutableListOf<Int>()
        val short = runBlocking {
            KemonoXunParser.walkPosts(10) { offset ->
                shortCalls += offset
                KemonoXunParser.PostBatch.Page(KemonoXunParser.parsePosts(text("posts-vicineko.json")))
            }
        }
        assertEquals(listOf(0), shortCalls)
        assertEquals(5, short.posts.size)
        val endCalls = mutableListOf<Int>()
        runBlocking {
            KemonoXunParser.walkPosts(10) { offset ->
                endCalls += offset
                if (offset == 0) {
                    KemonoXunParser.PostBatch.Page(List(KemonoXunParser.PAGE_SIZE) { template.copy(id = "p$it") })
                } else {
                    KemonoXunParser.PostBatch.End
                }
            }
        }
        assertEquals(listOf(0, 50), endCalls)
        val brokenSearch = KemonoXunParser.parsePosts(
            """{"count":"nope","true_count":1,"posts":[{"id":"1","user":"2","service":"patreon","title":"t","published":"2024-01-01T00:00:00","file":{"name":"a.mp4","path":"/aa/bb/a.mp4"},"attachments":[]}]}""",
        )
        assertEquals("1", brokenSearch.single().id)
        assertTrue(KemonoXunParser.hasVideo(brokenSearch.single()))
    }

    @Test
    fun fileServerUrlAndProbeClassification() {
        assertEquals(
            "https://n4.kemono.cr/data/aa/bb/file.mp4?f=clip.mp4",
            KemonoXunParser.fileUrl("https://kemono.cr", "/aa/bb/file.mp4", "clip.mp4", "https://n4.kemono.cr/"),
        )
        assertEquals(
            "https://kemono.cr/data/aa/bb/file.mp4?f=clip.mp4",
            KemonoXunParser.fileUrl("https://kemono.cr", "/aa/bb/file.mp4", "clip.mp4", "http://n4.kemono.cr"),
        )
        assertEquals(
            "https://kemono.cr/data/aa/bb/file.mp4?f=clip.mp4",
            KemonoXunParser.fileUrl("https://kemono.cr", "/aa/bb/file.mp4", "clip.mp4", null),
        )
        val bare = KemonoXunParser.videosFromDetail(
            KemonoXunParser.PostDetail(
                post = KemonoXunParser.Post(
                    id = "1",
                    user = "2",
                    service = "patreon",
                    attachments = listOf(KemonoXunParser.MediaFile(name = "a.mp4", path = "/aa/bb/a.mp4")),
                ),
            ),
            "https://kemono.cr",
        )
        assertEquals("https://kemono.cr/data/aa/bb/a.mp4?f=a.mp4", bare.single().url)
        val hosted = KemonoXunParser.videosFromDetail(
            KemonoXunParser.PostDetail(
                post = KemonoXunParser.Post(
                    id = "1",
                    user = "2",
                    service = "patreon",
                    attachments = listOf(KemonoXunParser.MediaFile(name = "a.mp4", path = "/aa/bb/a.mp4")),
                ),
                attachments = listOf(
                    KemonoXunParser.MediaFile(
                        name = "a.mp4",
                        path = "/aa/bb/a.mp4",
                        server = "https://n3.kemono.cr",
                    ),
                ),
            ),
            "https://coomer.st",
        )
        assertEquals("https://n3.kemono.cr/data/aa/bb/a.mp4?f=a.mp4", hosted.single().url)
        assertEquals("n3.kemono.cr", KemonoXunParser.fileHost(hosted.single().url))

        val fourK = KemonoXunParser.VideoLink("https://n4.kemono.cr/data/aa/a.mp4?f=4k.mp4", "4k.mp4", 2160)
        val hd = KemonoXunParser.VideoLink("https://n2.kemono.cr/data/bb/b.mp4?f=hd.mp4", "hd.mp4", 1080)
        val bothDown = KemonoXunParser.classifyPlayback(
            listOf(fourK, hd),
            mapOf("n4.kemono.cr" to false, "n2.kemono.cr" to false),
        ) as KemonoXunParser.PlaybackChoice.Blocked
        assertTrue(bothDown.message.contains("n4.kemono.cr"))
        assertTrue(bothDown.message.contains("n2.kemono.cr"))
        assertTrue(bothDown.message.contains("站点故障，不是插件问题"))
        assertTrue(bothDown.message.contains("file server unreachable"))

        val mixed = KemonoXunParser.classifyPlayback(
            listOf(fourK, hd),
            mapOf("n4.kemono.cr" to false, "n2.kemono.cr" to true),
        ) as KemonoXunParser.PlaybackChoice.Ready
        assertEquals(listOf("hd.mp4", "4k.mp4" + KemonoXunParser.UNREACHABLE_LABEL), mixed.links.map { it.label })
        assertEquals(hd.url, mixed.links[0].url)

        val unknown = KemonoXunParser.classifyPlayback(listOf(fourK), emptyMap()) as KemonoXunParser.PlaybackChoice.Ready
        assertEquals(fourK, unknown.links.single())
        val empty = KemonoXunParser.classifyPlayback(emptyList(), emptyMap()) as KemonoXunParser.PlaybackChoice.Blocked
        assertEquals(KemonoXunParser.NO_PLAYABLE_MESSAGE, empty.message)
    }

    private fun text(name: String): String = javaClass.classLoader?.getResource(name)?.readText()
        ?: error("missing fixture $name")
}
