package eu.kanade.tachiyomi.animeextension.all.kemonoxun

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Hits kemono.cr only when CANARY_LIVE=1. Prints counts, never file URLs.
 * A non-200 response or a body that is not JSON is a skip.
 * KEMONO_PROBE_VIDEOS=1 probes each episode's file hosts (4s connect, 6s call, parallel)
 * and expects the bilingual "file server unreachable" error. It does not download video bytes.
 */
class KemonoXunLiveCanaryTest {
    @Test
    fun liveViciNekoPostsAndWrappedSlime() {
        assumeTrue(System.getenv("CANARY_LIVE") == "1")
        val posts = get("$ORIGIN/api/v1/patreon/user/$VICINEKO/posts?o=0")
        if (posts.code != 200 || !posts.body.trimStart().startsWith("[")) {
            println("canary posts status=${posts.code} bytes=${posts.body.length}")
            assumeTrue(false)
            return
        }
        val parsed = runCatching { KemonoXunParser.parsePosts(posts.body) }.getOrElse {
            println("canary parse failed bytes=${posts.body.length}")
            assumeTrue(false)
            return
        }
        val episodes = KemonoXunParser.videoEpisodes(parsed, ORIGIN)
        println("canary posts=${parsed.size} videoEpisodes=${episodes.size}")
        assertTrue(parsed.size >= 40)
        assertTrue(episodes.size >= 5)
        Thread.sleep(1_100)
        val detail = get("$ORIGIN/api/v1/patreon/user/$VICINEKO/post/$SLIME_POST")
        if (detail.code != 200 || !detail.body.trimStart().startsWith("{")) {
            println("canary detail status=${detail.code} bytes=${detail.body.length}")
            assumeTrue(false)
            return
        }
        val links = runCatching { KemonoXunParser.videosFromBody(detail.body, ORIGIN) }.getOrElse {
            println("canary detail parse failed")
            assumeTrue(false)
            return
        }
        println("canary slimeVideos=${links.size} hosts=${links.map { KemonoXunParser.fileHost(it.url) }.distinct()}")
        assertTrue("missing videos on $SLIME_POST", links.size >= 2)
        assertTrue(links.any { it.resolution == 2160 })
        assertTrue(links.all { KemonoXunParser.fileHost(it.url).startsWith("n") && it.url.contains("/data/") })
    }

    @Test
    fun livePlayableChain() {
        assumeTrue(System.getenv("KEMONO_PROBE_VIDEOS") == "1")
        val samples = listOf(
            Sample("patreon", "49965584", "ViciNeko"),
            Sample("subscribestar", "mantis-x", "Mantis-X"),
            Sample("fantia", "83679", "Canan"),
            Sample("gumroad", "3267960360326", "Rinhee"),
        )
        val remembered = HashMap<String, Boolean>()
        println("PROBE header service id pageVideos hosts apiMs probeMs error")
        for (sample in samples) {
            val started = System.nanoTime()
            val posts = get("$ORIGIN/api/v1/${sample.service}/user/${sample.id}/posts?o=0")
            val apiMs = elapsed(started)
            assertEquals(200, posts.code)
            val parsed = KemonoXunParser.parsePosts(posts.body)
            val episodes = KemonoXunParser.videoEpisodes(parsed, ORIGIN)
            assertTrue("${sample.service}/${sample.id} has no video on page 1", episodes.isNotEmpty())
            Thread.sleep(1_100)
            val postId = episodes.first().path.substringAfterLast('/')
            val detail = get("$ORIGIN/api/v1${episodes.first().path}")
            assertEquals(200, detail.code)
            val links = KemonoXunParser.videosFromBody(detail.body, ORIGIN)
            assertTrue(links.isNotEmpty())
            assertTrue(links.all { KemonoXunParser.fileHost(it.url).startsWith("n") })
            val hosts = links.map { KemonoXunParser.fileHost(it.url) }.distinct()
            val probeStarted = System.nanoTime()
            val reach = probeHosts(links, remembered)
            val choice = KemonoXunParser.classifyPlayback(links, reach)
            val probeMs = elapsed(probeStarted)
            val blocked = choice as? KemonoXunParser.PlaybackChoice.Blocked
            assertTrue(
                "expected file-server error, hosts=$hosts reach=$reach",
                blocked != null &&
                    blocked.message.contains("文件服务器") &&
                    blocked.message.contains("站点故障，不是插件问题") &&
                    blocked.message.contains("file server unreachable"),
            )
            println(
                "PROBE ${sample.service} ${sample.id} pageVideos=${episodes.size} hosts=${hosts.joinToString(",")} " +
                    "apiMs=$apiMs probeMs=$probeMs error=${blocked!!.message} post=$postId",
            )
            Thread.sleep(1_100)
        }
    }

    private fun probeHosts(links: List<KemonoXunParser.VideoLink>, remembered: MutableMap<String, Boolean>): Map<String, Boolean> {
        val samples = LinkedHashMap<String, String>()
        for (link in links) {
            val host = KemonoXunParser.fileHost(link.url)
            if (host.isNotEmpty()) samples.putIfAbsent(host, link.url)
        }
        val now = System.currentTimeMillis()
        val pending = LinkedHashMap<String, String>()
        val known = HashMap<String, Boolean>()
        for ((host, sample) in samples) {
            val cached = remembered[host]
            val at = rememberedAt[host]
            if (cached != null && at != null && now - at <= KemonoXunParser.HOST_PROBE_TTL_MILLIS) {
                known[host] = cached
            } else {
                pending[host] = sample
            }
        }
        if (pending.isEmpty()) return known
        val fresh = runBlocking {
            coroutineScope {
                pending.map { (host, sample) ->
                    async { host to probeOne(sample) }
                }.awaitAll()
            }
        }
        val stamped = System.currentTimeMillis()
        for ((host, ok) in fresh) {
            remembered[host] = ok
            rememberedAt[host] = stamped
            known[host] = ok
        }
        return known
    }

    private fun probeOne(url: String): Boolean {
        return try {
            probe.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", KemonoXunParser.USER_AGENT)
                    .header("Referer", "$ORIGIN/")
                    .header("Accept", "*/*")
                    .header("Range", "bytes=0-0")
                    .build(),
            ).execute().use { response -> response.code in 100..599 }
        } catch (_: Exception) {
            false
        }
    }

    private fun get(url: String): Page {
        val response = api.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", KemonoXunParser.USER_AGENT)
                .header("Accept", "text/css")
                .build(),
        ).execute()
        return response.use { Page(it.code, it.body.string()) }
    }

    private fun elapsed(started: Long): Long = (System.nanoTime() - started) / 1_000_000L

    private data class Sample(val service: String, val id: String, val name: String)
    private data class Page(val code: Int, val body: String)

    companion object {
        private const val ORIGIN = "https://kemono.cr"
        private const val VICINEKO = "49965584"
        private const val SLIME_POST = "150370074"

        private val api = OkHttpClient.Builder()
            .callTimeout(40, TimeUnit.SECONDS)
            .build()

        private val probe = OkHttpClient.Builder()
            .connectTimeout(KemonoXunParser.PROBE_CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(KemonoXunParser.PROBE_CONNECT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(KemonoXunParser.PROBE_CALL_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        private val rememberedAt = HashMap<String, Long>()
    }
}
