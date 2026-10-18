package eu.kanade.tachiyomi.animeextension.all.kemonoxun

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Hits kemono.cr only when CANARY_LIVE=1. Prints counts, never file URLs.
 * A non-200 response or a body that is not JSON is a skip.
 * KEMONO_PROBE_VIDEOS=1 also follows one video URL per creator and records whether n* answered.
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
        println("canary slimeVideos=${links.size}")
        assertTrue("missing videos on $SLIME_POST", links.size >= 2)
        assertTrue(links.any { it.resolution == 2160 })
        assertTrue(links.all { it.url.startsWith("$ORIGIN/data/") })
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
        var comparedHttp1 = false
        println("PROBE header service id pageVideos apiMs redirect status locationHost fileStatus contentType fileMs note")
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
            assertTrue(links.first().url.startsWith("$ORIGIN/data/"))
            val outcome = probeFile(links.first().url, tryHttp1 = !comparedHttp1)
            if (!comparedHttp1) comparedHttp1 = true
            println(
                "PROBE ${sample.service} ${sample.id} pageVideos=${episodes.size} apiMs=$apiMs " +
                    "redirect=${outcome.redirect} locationHost=${outcome.locationHost} " +
                    "fileStatus=${outcome.fileStatus} contentType=${outcome.contentType} " +
                    "fileMs=${outcome.fileMs} note=${outcome.note} post=$postId",
            )
            Thread.sleep(1_100)
        }
    }

    private fun probeFile(url: String, tryHttp1: Boolean): Probe {
        val redirectStarted = System.nanoTime()
        val redirect = runCatching {
            noFollow.newCall(ranged(url)).execute().use { response ->
                Redirect(response.code, response.header("Location").orEmpty())
            }
        }.getOrElse { error ->
            return Probe(
                redirect = "error",
                locationHost = "-",
                fileStatus = "error",
                contentType = "-",
                fileMs = elapsed(redirectStarted),
                note = error.javaClass.simpleName,
            )
        }
        val locationHost = hostOf(redirect.location)
        if (redirect.code !in listOf(301, 302, 303, 307, 308) || redirect.location.isBlank()) {
            return Probe(redirect.code.toString(), locationHost, "-", "-", elapsed(redirectStarted), "no-redirect")
        }
        val fileStarted = System.nanoTime()
        val primary = fetchFile(ipv4Client, redirect.location)
        if (primary.playable || !tryHttp1) {
            return Probe(redirect.code.toString(), locationHost, primary.status, primary.type, elapsed(fileStarted), primary.note)
        }
        val fallback = fetchFile(http1Client, redirect.location)
        val note = "h2:${primary.note}; h1:${fallback.note}"
        val winner = if (fallback.playable) fallback else primary
        return Probe(redirect.code.toString(), locationHost, winner.status, winner.type, elapsed(fileStarted), note)
    }

    private fun fetchFile(client: OkHttpClient, url: String): FileResult {
        return try {
            client.newCall(ranged(url)).execute().use { response ->
                val type = response.header("Content-Type").orEmpty().substringBefore(';')
                val bytes = response.body.bytes()
                val playable = response.code == 200 || response.code == 206
                val video = type.startsWith("video/") || looksLikeVideo(bytes)
                FileResult(
                    status = response.code.toString(),
                    type = type.ifBlank { "-" },
                    playable = playable && video,
                    note = if (playable && video) "playable" else "http-${response.code}",
                )
            }
        } catch (error: Exception) {
            FileResult(status = "timeout", type = "-", playable = false, note = error.javaClass.simpleName)
        }
    }

    private fun ranged(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", KemonoXunParser.USER_AGENT)
        .header("Referer", "$ORIGIN/")
        .header("Accept", "*/*")
        .header("Range", "bytes=0-1023")
        .build()

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

    private fun looksLikeVideo(bytes: ByteArray): Boolean {
        if (bytes.size >= 8) {
            val box = bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII)
            if (box == "ftyp") return true
        }
        return bytes.size >= 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte()
    }

    private fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host ?: "-"
    }.getOrDefault("-")

    private fun elapsed(started: Long): Long = (System.nanoTime() - started) / 1_000_000L

    private data class Sample(val service: String, val id: String, val name: String)
    private data class Page(val code: Int, val body: String)
    private data class Redirect(val code: Int, val location: String)
    private data class FileResult(val status: String, val type: String, val playable: Boolean, val note: String)
    private data class Probe(
        val redirect: String,
        val locationHost: String,
        val fileStatus: String,
        val contentType: String,
        val fileMs: Long,
        val note: String,
    )

    companion object {
        private const val ORIGIN = "https://kemono.cr"
        private const val VICINEKO = "49965584"
        private const val SLIME_POST = "150370074"

        private val api = OkHttpClient.Builder()
            .callTimeout(40, TimeUnit.SECONDS)
            .build()

        private val noFollow = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

        private val ipv4Client = OkHttpClient.Builder()
            .dns(Ipv4Dns)
            .followRedirects(true)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

        private val http1Client = ipv4Client.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }
}

private object Ipv4Dns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        val v4 = all.filterIsInstance<Inet4Address>()
        return v4.ifEmpty { all }
    }
}
