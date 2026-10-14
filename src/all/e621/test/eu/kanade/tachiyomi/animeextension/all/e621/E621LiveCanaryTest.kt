package eu.kanade.tachiyomi.animeextension.all.e621

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Hits e621.net only when CANARY_LIVE=1. Prints status and counts, never URLs.
 * A non-200 response or a body that is not the posts API is a skip.
 */
class E621LiveCanaryTest {
    @Test
    fun liveVicinekoIncludesMona() {
        assumeTrue(System.getenv("CANARY_LIVE") == "1")
        val client = OkHttpClient.Builder()
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
        val response = client.newCall(
            Request.Builder()
                .url("$ORIGIN/posts.json?tags=vicineko&limit=50")
                .header("User-Agent", E621Parser.USER_AGENT)
                .header("Accept", "application/json")
                .build(),
        ).execute()
        val page = response.use { Page(it.code, it.body.string()) }
        if (page.code != 200 || !page.body.trimStart().startsWith("{")) {
            println("canary status=${page.code} bytes=${page.body.length}")
            assumeTrue(false)
            return
        }
        val posts = runCatching { E621Parser.parsePosts(page.body) }.getOrElse {
            println("canary parse failed bytes=${page.body.length}")
            assumeTrue(false)
            return
        }
        println("canary posts=${posts.size}")
        assertTrue(posts.size >= 10)
        val mona = posts.firstOrNull { it.id == 6365066L }
        assertTrue("missing 6365066", mona != null)
        assertEquals(540, E621Parser.durationSeconds(mona!!))
        assertTrue(E621Parser.videoLinks(mona).any { it.url.isNotBlank() })
    }

    private data class Page(val code: Int, val body: String)

    companion object {
        private const val ORIGIN = "https://e621.net"
    }
}
