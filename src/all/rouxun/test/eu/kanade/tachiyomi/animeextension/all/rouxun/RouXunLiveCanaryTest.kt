package eu.kanade.tachiyomi.animeextension.all.rouxun

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Hits rou.video only when CANARY_LIVE=1. A non-200 or a challenge page is a skip: the assertion
 * is about the bundle we actually downloaded, not about the network being up.
 */
class RouXunLiveCanaryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun liveBundleDiscoversEveryServerFnAndListsPageOne() {
        assumeTrue(System.getenv("CANARY_LIVE") == "1")

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val headers = Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Accept", "text/html,application/javascript,application/json,*/*")
            .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
            .add("Referer", RouXunServerFns.SITE_ROOT)
            .build()

        val home = client.newCall(get(RouXunServerFns.SITE_ROOT, headers)).execute()
        val homeBody = home.body.string()
        if (home.code != 200 || isChallenge(home.header("cf-mitigated"), homeBody)) {
            println("canary homepage status=${home.code}")
            assumeTrue(false)
            return
        }
        val paths = RouXunServerFns.indexScriptPaths(homeBody)
        assertTrue("age gate did not reference /assets/index-*.js", paths.isNotEmpty())

        var found = emptyMap<FnKey, String>()
        for (path in paths) {
            val url = RouXunServerFns.SITE_ROOT + path.removePrefix("/")
            val script = client.newCall(get(url, headers)).execute()
            val scriptBody = script.body.string()
            if (script.code != 200 || isChallenge(script.header("cf-mitigated"), scriptBody)) {
                println("canary script status=${script.code} path=$path")
                assumeTrue(false)
                return
            }
            val partial = RouXunServerFns.discover(scriptBody)
            if (partial.size > found.size) found = partial
            if (FnKey.entries.all { it in found }) break
        }

        assertEquals(FnKey.entries.toSet(), found.keys)
        found.values.forEach { id -> assertTrue(RouXunServerFns.isHex64(id)) }
        val mismatched = FnKey.entries.filter { found[it] != RouXunServerFns.FALLBACK[it] }
        println("canary idsMatchFallback=${mismatched.isEmpty()} mismatched=${mismatched.joinToString()}")

        val payload = Seroval.encodePayload(
            buildJsonObject {
                put("page", 1)
                put("order", "viewCount")
            },
        )
        val listUrl = RouXunServerFns.SITE_ROOT + "_serverFn/" + found.getValue(FnKey.VIDEO_LIST) +
            "?payload=" + URLEncoder.encode(payload, "UTF-8")
        val listHeaders = headers.newBuilder()
            .add("x-tsr-serverFn", "true")
            .set("Accept", "application/x-tss-framed, application/x-ndjson, application/json")
            .build()
        val list = client.newCall(get(listUrl, listHeaders)).execute()
        val listBody = list.body.string()
        if (list.code != 200 || isChallenge(list.header("cf-mitigated"), listBody)) {
            println("canary listStatus=${list.code}")
            assumeTrue(false)
            return
        }
        assertEquals("true", list.header("x-tss-serialized"))

        val result = Seroval.decode(json.parseToJsonElement(listBody)).jsonObject.getValue("result").jsonObject
        val videos = result.getValue("videos") as JsonArray
        val named = videos.count { item ->
            val name = (item as? JsonObject)?.get("name")?.jsonPrimitive?.content
            !name.isNullOrBlank()
        }
        println("canary videoCount=${videos.size} namedCount=$named")
        assertTrue(videos.size > 0)
        assertEquals(videos.size, named)
    }

    private fun get(url: String, headers: Headers): Request =
        Request.Builder().url(url).headers(headers).header("Cache-Control", "no-cache").build()

    private fun isChallenge(mitigated: String?, body: String): Boolean {
        if (mitigated != null) return true
        val head = body.take(500)
        return head.contains("Just a moment", ignoreCase = true) ||
            head.contains("cf-browser-verification") ||
            head.contains("Attention Required", ignoreCase = true)
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
