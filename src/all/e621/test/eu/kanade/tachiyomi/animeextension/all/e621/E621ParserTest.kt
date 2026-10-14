package eu.kanade.tachiyomi.animeextension.all.e621

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class E621ParserTest {
    @Test
    fun vicinekoFixtureHasTwelvePostsAndMona() {
        val posts = E621Parser.parsePosts(text("posts-vicineko.json"))
        assertEquals(12, posts.size)
        val mona = posts.first { it.id == 6365066L }
        assertEquals(540, E621Parser.durationSeconds(mona))
        assertTrue(E621Parser.titleOf(mona).contains("Mona X Slime"))
        val links = E621Parser.videoLinks(mona)
        assertTrue(links.isNotEmpty())
        assertTrue(links.all { it.url.isNotBlank() })
        assertTrue(links.any { it.label.startsWith("原始") && it.url.contains(".webm") })
        assertTrue(links.any { it.label == "480p" })
        val best = E621Parser.sortQualities(links, E621Parser.QUALITY_BEST)
        assertEquals(links.maxOf { it.resolution }, best.first().resolution)
        assertTrue(best.first().label.startsWith("原始"))
        val prefer480 = E621Parser.sortQualities(links, "480p")
        assertEquals(480, prefer480.first().resolution)
        assertEquals("480p", prefer480.first().label)
    }

    @Test
    fun higherPostPrefersNamed720SampleOverOriginal() {
        val post = E621Parser.parsePosts(text("posts-vicineko.json")).first { it.id == 6363666L }
        val links = E621Parser.videoLinks(post)
        assertTrue(links.any { it.resolution == 1080 && it.label.startsWith("原始") })
        assertTrue(links.any { it.label == "720p" })
        val best = E621Parser.sortQualities(links, E621Parser.QUALITY_BEST)
        assertEquals(1080, best.first().resolution)
        val prefer720 = E621Parser.sortQualities(links, "720")
        assertEquals("720p", prefer720.first().label)
        assertEquals(listOf(1080, 1080, 480), prefer720.drop(1).map { it.resolution })
    }

    @Test
    fun titleAuthorGenreAndHiddenFile() {
        val posts = E621Parser.parsePosts(text("posts-vicineko.json"))
        val mona = posts.first { it.id == 6365066L }
        assertEquals("vicineko", E621Parser.entryFromPost(mona)!!.author)
        assertEquals("2_horns", E621Parser.genresOf(mona).substringBefore(","))
        assertTrue(E621Parser.genresOf(mona).split(", ").size <= E621Parser.GENRE_LIMIT)
        assertTrue(E621Parser.thumbnailOf(mona)!!.startsWith("https://"))
        val untitled = posts.first { it.id == 6627533L }
        assertEquals("vicineko #6627533", E621Parser.titleOf(untitled))
        val listing = E621Parser.listing(text("posts-vicineko.json"), E621Parser.LISTING_PAGE_SIZE)
        assertEquals(12, listing.posts.size)
        assertFalse(listing.hasNextPage)

        val hidden = """
            {"posts":[
              {"id":1,"file":{"width":1280,"height":720,"ext":"webm","url":null},"tags":{"artist":["vicineko"],"general":["a"]},"duration":10.2},
              {"id":2,"file":{"width":640,"height":360,"ext":"mp4","url":"https://example.test/a.mp4"},"tags":{"artist":["vicineko"],"general":[]},"duration":3}
            ]}
        """.trimIndent()
        val parsed = E621Parser.listing(hidden, limit = 2)
        assertEquals(listOf(2L), parsed.posts.map { it.id })
        assertTrue(parsed.hasNextPage)
        assertTrue(E621Parser.videoLinks(E621Parser.parsePosts(hidden).first()).isEmpty())
        assertEquals("0:03", E621Parser.formatDuration(3.2))
    }

    @Test
    fun searchTagsAlwaysIncludeTheVideoOr() {
        assertEquals("~type:webm ~type:mp4 order:score", E621Parser.popularTags())
        assertEquals("~type:webm ~type:mp4", E621Parser.latestTags())
        assertEquals("vicineko ~type:webm ~type:mp4", E621Parser.searchTags("vicineko", "", ""))
        assertEquals(
            "vicineko female ~type:webm ~type:mp4 order:favcount",
            E621Parser.searchTags("vicineko", "favcount", "female"),
        )
        assertEquals(
            "artist:vicineko ~type:webm ~type:mp4 order:id",
            E621Parser.searchTags("artist:vicineko", "id", ""),
        )
        assertEquals("", E621Parser.orderTag("default"))
    }

    @Test
    fun artistLookupAndCollectionPages() {
        assertEquals("vicineko", E621Parser.artistCandidate("ViciNeko"))
        assertEquals("vicineko", E621Parser.artistCandidate("artist:vicineko"))
        assertNull(E621Parser.artistCandidate("vicineko slime"))
        assertNull(E621Parser.artistCandidate("order:score"))
        assertEquals("vicineko", E621Parser.exactArtist(text("tags-vicineko.json"), "ViciNeko"))
        assertNull(E621Parser.exactArtist(text("tags-vicineko.json"), "someone_else"))
        assertEquals("/collection/artist/vicineko", E621Parser.collectionPath("vicineko"))
        assertEquals("vicineko", E621Parser.artistFromCollection("/collection/artist/vicineko"))
        assertTrue(E621Parser.isCollection("https://e621.net/collection/artist/vicineko"))
        assertFalse(E621Parser.isCollection("/posts/6365066.json"))

        val page1 = E621Parser.parsePosts(text("posts-vicineko.json"))
        val short = runBlocking {
            E621Parser.collectPages(pageSize = 320) { page ->
                if (page == 1) page1 else error("short page must not fetch page $page")
            }
        }
        assertEquals(page1.map { it.id }, short.map { it.id })

        val calls = mutableListOf<Int>()
        val collected = runBlocking {
            E621Parser.collectPages(pageSize = page1.size, maxPages = 10) { page ->
                calls += page
                if (page == 1) page1 else E621Parser.parsePosts(text("posts-empty.json"))
            }
        }
        assertEquals(listOf(1, 2), calls)
        assertEquals(12, collected.size)

        val episodes = E621Parser.collectionEpisodes(collected)
        assertEquals(12, episodes.size)
        assertEquals("/posts/6627533.json", episodes.first().path)
        assertEquals(12f, episodes.first().episodeNumber)
        assertEquals(1f, episodes.last().episodeNumber)
        assertEquals(3758373L, episodes.last().path.substringAfter("/posts/").substringBefore(".json").toLong())
        assertTrue(episodes.first().dateUpload >= episodes.last().dateUpload)
        val mona = episodes.first { it.path == "/posts/6365066.json" }
        assertTrue(mona.name.contains("Mona X Slime"))
        assertTrue(mona.name.contains("9:00"))
        val cappedCalls = mutableListOf<Int>()
        val capped = runBlocking {
            E621Parser.collectPages(pageSize = 1, maxPages = 10) { page ->
                cappedCalls += page
                listOf(page1.first().copy(id = 10_000L + page))
            }
        }
        assertEquals((1..10).toList(), cappedCalls)
        assertEquals(10, capped.size)
        val duplicateCalls = mutableListOf<Int>()
        val duplicate = runBlocking {
            E621Parser.collectPages(pageSize = 1, maxPages = 10) { page ->
                duplicateCalls += page
                page1.take(1)
            }
        }
        assertEquals(listOf(1, 2), duplicateCalls)
        assertEquals(1, duplicate.size)
    }

    private fun text(name: String): String = javaClass.classLoader?.getResource(name)?.readText()
        ?: error("missing fixture $name")
}
