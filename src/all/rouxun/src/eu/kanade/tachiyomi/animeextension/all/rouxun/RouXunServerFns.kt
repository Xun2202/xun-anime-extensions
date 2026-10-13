package eu.kanade.tachiyomi.animeextension.all.rouxun

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Server-function ids are hashes of the route file plus the export name, so they change when
 * rou.video rebuilds. The ids sit next to a stable pattern in `/assets/index-*.js`:
 *
 *  - each route chunk is `import("./<route>-<hash>.js")` and the matching `ut("<64 hex>")`
 *    follows within a short window (measured at 21–49 chars; the next route's `ut` is ~450
 *    away, so the window stays well clear of it)
 *  - the video-detail id is not next to an import; it is the `ut(...)` immediately after the
 *    `atob` + `charCodeAt(0)-` playlist decoder
 *
 * [FALLBACK] is the last set captured from the site. Discovery fills what it can; missing keys
 * keep the fallback so one unmatched route does not blank the whole extension.
 */
internal enum class FnKey {
    VIDEO_LIST,
    SEARCH,
    TAG,
    SERIES_LIST,
    HOME,
    CAT,
    VIDEO_DETAIL,
    SERIES_DETAIL,
}

internal data class ResolvedFn(val id: String, val epoch: Int)

internal object RouXunServerFns {
    const val SITE_ROOT = "https://rou.video/"

    const val MSG_SCRIPT_CHANGED = "站点脚本已变化，请更新 RouXun 插件"
    const val MSG_FORMAT_CHANGED = "站点数据格式可能已变化，请更新 RouXun 插件"

    val FALLBACK: Map<FnKey, String> = mapOf(
        FnKey.VIDEO_LIST to "e61b4025d09a39a538e2a98200c722a8aac61a91fe3c24069b22b6995cca358c",
        FnKey.VIDEO_DETAIL to "74554e0f1bc953cd9ba961827420d087df3ba658600b3e3851bb2534a7f8151f",
        FnKey.TAG to "c84377bc729812c8b0f1a090029dab1cc3eaa54d8244077638856530c838af3f",
        FnKey.SEARCH to "b3cc132b712a986cd50504d429446dda22b20e0a6d3353d9bf129015218dad3c",
        FnKey.HOME to "d35808569a69e11e7268ab35797d3a3912cd11db0a3c079cd1bdcff8891a80ed",
        FnKey.CAT to "173c4c582796c7514195728e9ec7460dfeddbb50e5c886c7ac6e690302df61aa",
        FnKey.SERIES_LIST to "efb21a4b20fbf5718f45417571e1cc66d2a22946338aeeee62ff162e19b95c4c",
        FnKey.SERIES_DETAIL to "2b8ad32189617e753416c128487a55971d2bf9367af80897e9e72aed49829fb5",
    )

    private val ROUTE_KEYS = mapOf(
        "home" to FnKey.HOME,
        "v.index" to FnKey.VIDEO_LIST,
        "search" to FnKey.SEARCH,
        "series" to FnKey.SERIES_LIST,
        "s._id" to FnKey.SERIES_DETAIL,
        "t._id" to FnKey.TAG,
        "cat" to FnKey.CAT,
    )

    // The hyphen after the route name keeps `series` from matching `s._id` and `v.index` from matching `v._id`.
    private val ROUTE_IMPORT = Regex(
        """import\("\./(home|v\.index|search|series|s\._id|t\._id|cat)-[A-Za-z0-9_-]+\.js"\)""",
    )
    private val UT_CALL = Regex("""ut\("([0-9a-fA-F]{64})"\)""")
    private val SCRIPT_SRC = Regex("""src="(/assets/index-[A-Za-z0-9_-]+\.js)"""")
    private val ANY_INDEX = Regex("""(/assets/index-[A-Za-z0-9_-]+\.js)""")
    private val HEX_64 = Regex("[0-9a-f]{64}")

    private const val DETAIL_MARKER = "charCodeAt(0)-"

    /** Chars after an import (or the detail marker) in which its `ut(...)` must appear. */
    private const val UT_WINDOW = 200

    fun discover(indexJs: String): Map<FnKey, String> {
        val found = LinkedHashMap<FnKey, String>()
        for (match in ROUTE_IMPORT.findAll(indexJs)) {
            val key = ROUTE_KEYS[match.groupValues[1]] ?: continue
            if (key in found) continue
            val id = utWithin(indexJs, match.range.last + 1) ?: continue
            found[key] = id
        }
        val markerAt = indexJs.indexOf(DETAIL_MARKER)
        if (markerAt >= 0) {
            utWithin(indexJs, markerAt + DETAIL_MARKER.length)?.let { found[FnKey.VIDEO_DETAIL] = it }
        }
        return found
    }

    /**
     * Entry script (`src=`) first, then any other `/assets/index-*.js`. The age gate also
     * preloads a tiny sibling bundle; trying the entry first avoids parsing that file.
     */
    fun indexScriptPaths(html: String): List<String> {
        val fromSrc = SCRIPT_SRC.findAll(html).map { it.groupValues[1] }
        val any = ANY_INDEX.findAll(html).map { it.groupValues[1] }
        return (fromSrc + any).distinct().toList()
    }

    fun fetchDiscovered(client: OkHttpClient, headers: Headers): Map<FnKey, String> {
        val html = httpGet(client, headers, SITE_ROOT) ?: return emptyMap()
        var best = emptyMap<FnKey, String>()
        for (path in indexScriptPaths(html)) {
            val url = when {
                path.startsWith("https://") || path.startsWith("http://") -> path
                else -> SITE_ROOT + path.removePrefix("/")
            }
            val js = httpGet(client, headers, url) ?: continue
            val found = discover(js)
            if (found.size > best.size) best = found
            if (FnKey.entries.all { it in found }) return found
        }
        return best
    }

    fun isHex64(value: String): Boolean = HEX_64.matches(value)

    private fun utWithin(text: String, from: Int): String? {
        val match = UT_CALL.find(text, from) ?: return null
        if (match.range.first >= from + UT_WINDOW) return null
        return match.groupValues[1].lowercase()
    }

    private fun httpGet(client: OkHttpClient, headers: Headers, url: String): String? {
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .header("Cache-Control", "no-cache")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body.string()
        }
    }
}

/** A 500 without `x-tss-serialized` is how an unknown serverFn id fails; a real handler sets the header. */
internal fun Response.isStaleServerFn(): Boolean = code == 500 && header("x-tss-serialized") == null

/**
 * Page 1 of a listing that should never be empty (popular, latest, home picks). Later pages and
 * search / tag queries may legitimately return nothing. [count] for home is the flattened list.
 */
internal fun ensureListing(page: Int, count: Int, rejectEmptyFirstPage: Boolean) {
    if (rejectEmptyFirstPage && page <= 1 && count == 0) {
        throw Exception(RouXunServerFns.MSG_FORMAT_CHANGED)
    }
}

/**
 * Process-wide serverFn id table. [discoverFromNetwork] runs at most once per epoch: callers
 * that raced on the same stale epoch share the refresh instead of each downloading the bundle.
 * A persisted map is used only when every key is still 64 hex; a 500 refresh replaces it.
 */
internal class ServerFnCatalog(
    private val discoverFromNetwork: () -> Map<FnKey, String>,
    private val readCache: () -> Map<FnKey, String>?,
    private val writeCache: (Map<FnKey, String>) -> Unit,
) {
    private val gate = Any()

    @Volatile
    private var resolved: Map<FnKey, String>? = null

    @Volatile
    var epoch: Int = 0
        private set

    fun resolve(key: FnKey): ResolvedFn = synchronized(gate) {
        val current = resolved ?: loadLocked()
        ResolvedFn(current[key] ?: RouXunServerFns.FALLBACK.getValue(key), epoch)
    }

    fun refreshIfStale(seenEpoch: Int) {
        synchronized(gate) {
            if (resolved != null && epoch != seenEpoch) return
            val discovered = runCatching { discoverFromNetwork() }.getOrDefault(emptyMap())
            if (discovered.isEmpty()) {
                // Keep the ids we already have, but move the epoch so waiters do not download again.
                if (resolved == null) resolved = RouXunServerFns.FALLBACK
                epoch += 1
                return
            }
            publishLocked(discovered)
        }
    }

    private fun loadLocked(): Map<FnKey, String> {
        readCache()?.takeIf { it.hasAllKeys() }?.let {
            resolved = it
            return it
        }
        val discovered = runCatching { discoverFromNetwork() }.getOrDefault(emptyMap())
        return publishLocked(discovered)
    }

    private fun publishLocked(discovered: Map<FnKey, String>): Map<FnKey, String> {
        val merged = FnKey.entries.associateWith { key ->
            discovered[key] ?: RouXunServerFns.FALLBACK.getValue(key)
        }
        resolved = merged
        epoch += 1
        if (discovered.isNotEmpty()) writeCache(merged)
        return merged
    }

    private fun Map<FnKey, String>.hasAllKeys(): Boolean = FnKey.entries.all { key -> this[key]?.let(RouXunServerFns::isHex64) == true }
}
