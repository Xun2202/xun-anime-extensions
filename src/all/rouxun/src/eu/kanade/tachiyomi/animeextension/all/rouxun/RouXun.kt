package eu.kanade.tachiyomi.animeextension.all.rouxun

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.rouxun.BrowseFilter.Companion.BROWSE_HOME
import eu.kanade.tachiyomi.animeextension.all.rouxun.BrowseFilter.Companion.BROWSE_SERIES
import eu.kanade.tachiyomi.animeextension.all.rouxun.BrowseFilter.Companion.BROWSE_WATCHING
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * rou.video (肉視頻) source that works with the site's TanStack Start rewrite.
 *
 * The stock RouVideo extension scrapes `script#__NEXT_DATA__`, which no longer exists, so it
 * shows nothing. This one calls the same server functions the site's own JS calls
 * (`/_serverFn/<id>?payload=…`, seroval-encoded) and plays video through [HlsProxy], because
 * the HLS playlist and segments are disguised as PNG files that mpv/ffmpeg cannot read directly.
 */
class RouXun :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "RouXun"

    override val baseUrl = "https://rou.video"

    override val lang = "all"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "zh-CN,zh;q=0.9,zh-TW;q=0.8,en;q=0.7")

    private val fnHeaders: Headers by lazy {
        headersBuilder()
            .add("x-tsr-serverFn", "true")
            .add("Accept", "application/x-tss-framed, application/x-ndjson, application/json")
            .build()
    }

    private val apiHeaders: Headers by lazy {
        headersBuilder()
            .add("Accept", "application/json, text/plain, */*")
            .build()
    }

    private val proxy by lazy { HlsProxy(client, headers) }

    // ============================ Server functions =========================

    private fun fnRequest(fnId: String, data: JsonObject?): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("_serverFn")
            .addPathSegment(fnId)
            .addEncodedQueryParameter("payload", URLEncoder.encode(Seroval.encodePayload(data), "UTF-8"))
            .build()
        return GET(url, fnHeaders)
    }

    private fun Response.isFn(fnId: String) = request.url.pathSegments.getOrNull(1) == fnId

    private inline fun <reified T> Response.fnResult(): T = use {
        val root = Seroval.decode(json.parseToJsonElement(body.string())).jsonObject
        root["error"]?.let { error ->
            if (error is JsonObject || (error is JsonPrimitive && error.isString)) {
                throw Exception("rou.video 返回错误：$error")
            }
        }
        val result = root["result"]?.takeUnless { it is JsonNull } ?: throw Exception("rou.video 没有返回数据")
        json.decodeFromJsonElement<T>(result)
    }

    private fun idData(id: String) = buildJsonObject { put("id", id) }

    private fun listRequest(page: Int, order: String) = fnRequest(
        FN_VIDEO_LIST,
        buildJsonObject {
            put("page", page)
            put("order", order)
        },
    )

    private fun tagRequest(tag: String, page: Int, order: String) = fnRequest(
        FN_TAG,
        buildJsonObject {
            put("id", tag)
            put("page", page)
            put("order", order)
        },
    )

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        fetchTaxonomyOnce()
        return listRequest(page, SORT_VIEWS)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = response.fnResult<VideoListResult>().toPage()

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        fetchTaxonomyOnce()
        return listRequest(page, SORT_LATEST)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val trimmed = query.trim()

        if (trimmed.startsWith("http")) {
            val url = trimmed.toHttpUrlOrNull() ?: throw Exception("无法识别的链接")
            if (url.host != baseUrl.toHttpUrl().host) throw Exception("不是 rou.video 的链接")
            val kind = url.pathSegments.getOrNull(0)
            val item = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() }
            if (kind !in setOf(KIND_VIDEO, KIND_SERIES, KIND_TAG) || item == null) throw Exception("不支持的链接：$trimmed")
            return getSearchAnime(page, "$kind:$item", filters)
        }
        if (trimmed.startsWith(PREFIX_VIDEO)) {
            val detail = client.newCall(fnRequest(FN_VIDEO_DETAIL, idData(trimmed.removePrefix(PREFIX_VIDEO))))
                .awaitSuccess().fnResult<VideoDetailResult>()
            rememberTags(detail.video)
            return AnimesPage(listOf(detail.video.toSAnime()), false)
        }
        if (trimmed.startsWith(PREFIX_SERIES)) {
            val detail = client.newCall(fnRequest(FN_SERIES_DETAIL, idData(trimmed.removePrefix(PREFIX_SERIES))))
                .awaitSuccess().fnResult<SeriesDetailResult>()
            return AnimesPage(listOf(detail.series.toSAnime()), false)
        }
        if (trimmed.startsWith(PREFIX_TAG)) {
            return client.newCall(tagRequest(trimmed.removePrefix(PREFIX_TAG), page, SORT_LATEST))
                .awaitSuccess().fnResult<VideoListResult>().toPage()
        }

        fetchTaxonomyOnce()

        val sort = filters.filterOf<SortFilter>()?.selectedValue.orEmpty()
        val browse = filters.filterOf<BrowseFilter>()?.selectedValue ?: BrowseFilter.BROWSE_VIDEOS
        val tag = filters.filterOf<TagFilter>()?.selectedValue.orEmpty()
            .ifEmpty { filters.filterOf<CategoryFilter>()?.selectedValue.orEmpty() }
        val keyword = trimmed.ifEmpty { filters.filterOf<HotSearchFilter>()?.selectedValue.orEmpty() }

        if (keyword.isNotEmpty()) {
            val tab = if (browse == BROWSE_SERIES) "series" else "video"
            val result = client.newCall(
                fnRequest(
                    FN_SEARCH,
                    buildJsonObject {
                        put("q", keyword)
                        put("t", tag)
                        put("sort", if (sort.isEmpty()) "" else "$sort:desc")
                        put("page", page)
                        put("tab", tab)
                    },
                ),
            ).awaitSuccess().fnResult<VideoListResult>()
            result.hotSearches?.let(::rememberHotSearches)
            return if (tab == "series") {
                AnimesPage(result.seriesList.map { it.toSAnime() }, result.hasNextPage)
            } else {
                result.toPage()
            }
        }

        val order = sort.ifEmpty { SORT_LATEST }
        return when (browse) {
            BROWSE_HOME -> {
                val home = client.newCall(fnRequest(FN_HOME, null)).awaitSuccess().fnResult<HomeResult>()
                val videos = home.allVideos().let { videos ->
                    when (order) {
                        SORT_VIEWS -> videos.sortedByDescending { it.viewCount }
                        SORT_LIKES -> videos.sortedByDescending { it.likeCount ?: 0 }
                        else -> videos.sortedByDescending { it.createdAt.orEmpty() }
                    }
                }
                AnimesPage(videos.map { it.toSAnime() }, false)
            }
            BROWSE_WATCHING -> {
                val videos = client.newCall(GET("$baseUrl/api/v/watching", apiHeaders)).awaitSuccess()
                    .use { json.decodeFromString<List<VideoDto>>(it.body.string()) }
                AnimesPage(videos.map { it.toSAnime() }, false)
            }
            BROWSE_SERIES -> {
                val result = client.newCall(
                    fnRequest(
                        FN_SERIES_LIST,
                        buildJsonObject {
                            put("f", filters.filterOf<SeriesStatusFilter>()?.selectedValue ?: "all")
                            put("s", filters.filterOf<SeriesSortFilter>()?.selectedValue ?: "updated")
                            put("page", page)
                            put("tag", tag)
                        },
                    ),
                ).awaitSuccess().fnResult<SeriesListResult>()
                AnimesPage(result.list.map { it.toSAnime() }, result.hasNextPage)
            }
            else -> {
                val request = if (tag.isNotEmpty()) tagRequest(tag, page, order) else listRequest(page, order)
                client.newCall(request).awaitSuccess().fnResult<VideoListResult>().toPage()
            }
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    private inline fun <reified T> AnimeFilterList.filterOf(): T? = filterIsInstance<T>().firstOrNull()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList {
        val taxonomy = taxonomy
        val categories = taxonomy?.cats?.map { it.id }?.takeIf { it.isNotEmpty() } ?: DEFAULT_CATEGORIES
        val tags = buildList {
            taxonomy?.genre?.forEach { add(it.id) }
            taxonomy?.byParent?.values?.forEach { children -> children.forEach { add(it.id) } }
            addAll(savedTags)
        }.distinct().filterNot { it in categories }

        return AnimeFilterList(
            AnimeFilter.Header("有搜索词：站内搜索，「排序」「标签 / 分类」生效；无搜索词：按「浏览方式」列出"),
            SortFilter(),
            BrowseFilter(),
            CategoryFilter(categories),
            TagFilter(tags),
            HotSearchFilter(hotSearches),
            AnimeFilter.Separator(),
            SeriesStatusFilter(),
            SeriesSortFilter(),
            AnimeFilter.Header(
                if (taxonomy == null) "分类 / 标签列表正在后台加载，稍后点「重置」刷新" else "分类 ${categories.size} 个 · 标签 ${tags.size} 个",
            ),
        )
    }

    @Volatile
    private var taxonomy: Taxonomy? = null
    private val taxonomyFetchStarted = AtomicBoolean(false)

    private fun fetchTaxonomyOnce() {
        if (taxonomy != null || !taxonomyFetchStarted.compareAndSet(false, true)) return
        thread(isDaemon = true, name = "rouxun-taxonomy") {
            runCatching { client.newCall(fnRequest(FN_CAT, null)).execute().fnResult<CatResult>().taxonomy }
                .onSuccess { taxonomy = it }
                .onFailure { taxonomyFetchStarted.set(false) }
        }
    }

    private var savedTags: Set<String>
        get() = preferences.getStringSet(PREF_SAVED_TAGS, emptySet()).orEmpty()
        set(value) = preferences.edit().putStringSet(PREF_SAVED_TAGS, value).apply()

    private fun rememberTags(video: VideoDto) {
        val tags = video.tags.filterNot { it in DEFAULT_CATEGORIES }
        if (tags.isNotEmpty()) savedTags = savedTags + tags
    }

    private var hotSearches: List<String>
        get() = preferences.getString(PREF_HOT_SEARCHES, "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(value) = preferences.edit().putString(PREF_HOT_SEARCHES, value.joinToString("\n")).apply()

    private fun rememberHotSearches(keywords: List<String>) {
        if (keywords.isNotEmpty()) hotSearches = (keywords + hotSearches).distinct().take(40)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val (kind, id) = anime.url.parseEntryUrl()
        return if (kind == KIND_SERIES) fnRequest(FN_SERIES_DETAIL, idData(id)) else fnRequest(FN_VIDEO_DETAIL, idData(id))
    }

    override fun animeDetailsParse(response: Response): SAnime {
        if (response.isFn(FN_SERIES_DETAIL)) {
            return response.fnResult<SeriesDetailResult>().series.toSAnime()
        }
        val detail = response.fnResult<VideoDetailResult>()
        rememberTags(detail.video)
        return detail.video.toSAnime()
    }

    override fun relatedAnimeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        if (response.isFn(FN_SERIES_DETAIL)) {
            return response.fnResult<SeriesDetailResult>().more.map { it.toSAnime() }
        }
        val detail = response.fnResult<VideoDetailResult>()
        return listOfNotNull(detail.series?.toSAnime()) + detail.relatedVideos.map { it.toSAnime() }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        if (response.isFn(FN_SERIES_DETAIL)) {
            val detail = response.fnResult<SeriesDetailResult>()
            return detail.episodes
                .sortedByDescending { it.episode ?: 0f }
                .map { it.toEpisode(inSeries = true) }
        }
        val detail = response.fnResult<VideoDetailResult>()
        return listOf(detail.video.toEpisode(inSeries = false))
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val (_, id) = episode.url.parseEntryUrl()
        val detail = client.newCall(fnRequest(FN_VIDEO_DETAIL, idData(id))).awaitSuccess().fnResult<VideoDetailResult>()
        val hlsPath = detail.ev?.let { ev ->
            runCatching { json.decodeFromString<VideoUrls>(decodeEv(ev)).videoUrl }.getOrNull()
        } ?: "/api/hls/$id"
        val upstream = if (hlsPath.startsWith("http")) hlsPath else baseUrl + hlsPath
        val resolution = detail.video.maxResolution
        val quality = resolution?.let { "${it}p" } ?: "HLS"
        val url = proxy.playlistUrl(id, upstream)
        return listOf(Video(videoUrl = url, videoTitle = "$quality · rou.video（本地解包）", resolution = resolution))
    }

    /** `d` is base64 of a string whose every char code was shifted up by `k`. */
    private fun decodeEv(ev: EncodedVideoUrl): String {
        val shifted = String(Base64.decode(ev.d, Base64.DEFAULT), Charsets.ISO_8859_1)
        return buildString(shifted.length) {
            shifted.forEach { append((it.code - ev.k).toChar()) }
        }
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANG
            title = "标题 / 标签文字"
            entries = arrayOf("简体中文（站点提供的简体版）", "繁体中文（站点原文）")
            entryValues = arrayOf(TITLE_SIMPLIFIED, TITLE_TRADITIONAL)
            setDefaultValue(TITLE_SIMPLIFIED)
            summary = "%s"
        }.also(screen::addPreference)
    }

    private val useSimplified: Boolean
        get() = preferences.getString(PREF_TITLE_LANG, TITLE_SIMPLIFIED) == TITLE_SIMPLIFIED

    // ============================= Mappers ================================

    private fun VideoListResult.toPage() = AnimesPage(videos.map { it.toSAnime() }, hasNextPage)

    private val VideoDto.displayName: String
        get() = (if (useSimplified) nameZh else null)?.takeIf { it.isNotBlank() } ?: name

    private val VideoDto.displayTags: List<String>
        get() = (if (useSimplified) tagsZh else null)?.takeIf { it.isNotEmpty() } ?: tags

    private fun VideoDto.toSAnime(): SAnime = SAnime.create().apply {
        url = "/$KIND_VIDEO/$id"
        title = displayName
        thumbnail_url = coverImageUrl
        genre = displayTags.joinToString()
        artist = displayTags.firstOrNull()
        author = publisher ?: (if (useSimplified) seriesNameZh else null) ?: seriesName
        status = SAnime.COMPLETED
        description = buildString {
            vid?.takeIf { it.isNotBlank() }?.let { appendLine("番号：$it") }
            val parts = listOfNotNull(
                duration?.let { "时长：${formatDuration(it)}" },
                maxResolution?.let { "画质：${it}p" },
            )
            if (parts.isNotEmpty()) appendLine(parts.joinToString(" · "))
            appendLine(listOfNotNull("观看：$viewCount", likeCount?.let { "喜欢：$it" }).joinToString(" · "))
            createdAt?.let { appendLine("发布：${it.take(10)}") }
            val series = (if (useSimplified) seriesNameZh else null) ?: seriesName
            if (series != null) appendLine("剧集：$series${episode?.let { " 第${it.toInt()}集" }.orEmpty()}")
            ref?.takeIf { it.isNotBlank() }?.let { appendLine("来源：$it") }
            this@toSAnime.description?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
        }.trimEnd()
        initialized = true
    }

    private fun VideoDto.toEpisode(inSeries: Boolean): SEpisode = SEpisode.create().apply {
        url = "/$KIND_VIDEO/$id"
        name = if (inSeries) episode?.let { "第${it.toInt()}集" } ?: displayName else vid?.takeIf { it.isNotBlank() } ?: "正片"
        episode_number = episode ?: 1f
        date_upload = createdAt.toDate()
        summary = listOfNotNull(duration?.let { formatDuration(it) }, "观看 $viewCount").joinToString(" · ")
    }

    private fun SeriesDto.toSAnime(): SAnime = SAnime.create().apply {
        url = "/$KIND_SERIES/$id"
        title = (if (useSimplified) nameZh else null)?.takeIf { it.isNotBlank() } ?: name
        thumbnail_url = coverImageUrl
        genre = tags?.joinToString()
        artist = tags?.firstOrNull()
        author = "剧集"
        status = if (this@toSAnime.status.equals("COMPLETED", ignoreCase = true)) SAnime.COMPLETED else SAnime.ONGOING
        description = buildString {
            val count = epLabel ?: episodeCount?.let { "已更新 $it 集" }
            appendLine(listOfNotNull("剧集", count, totalEpisodes?.let { "共 $it 集" }.takeIf { epLabel == null }).joinToString(" · "))
            appendLine(listOfNotNull(viewCount?.let { "观看：$it" }, likeCount?.let { "喜欢：$it" }).joinToString(" · "))
            lastEpisodeAt?.let { appendLine("最近更新：${it.take(10)}") }
            this@toSAnime.description?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
        }.trimEnd()
        initialized = true
    }

    // ============================= Utilities ==============================

    /** `/v/<id>` or `/s/<id>` (also tolerates a bare id, treated as a video). */
    private fun String.parseEntryUrl(): Pair<String, String> {
        val segments = trim('/').split('/')
        return if (segments.size >= 2) segments[0] to segments[1] else KIND_VIDEO to segments[0]
    }

    private fun formatDuration(seconds: Double): String {
        val total = seconds.toLong()
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun String?.toDate(): Long = this?.let { runCatching { DATE_FORMAT.parse(it.trim())?.time }.getOrNull() } ?: 0L

    companion object {
        // Server-function ids are hashes of the route file + export; they change only when the
        // site's source layout changes. Captured from https://rou.video/assets/index-*.js.
        private const val FN_VIDEO_LIST = "e61b4025d09a39a538e2a98200c722a8aac61a91fe3c24069b22b6995cca358c"
        private const val FN_VIDEO_DETAIL = "74554e0f1bc953cd9ba961827420d087df3ba658600b3e3851bb2534a7f8151f"
        private const val FN_TAG = "c84377bc729812c8b0f1a090029dab1cc3eaa54d8244077638856530c838af3f"
        private const val FN_SEARCH = "b3cc132b712a986cd50504d429446dda22b20e0a6d3353d9bf129015218dad3c"
        private const val FN_HOME = "d35808569a69e11e7268ab35797d3a3912cd11db0a3c079cd1bdcff8891a80ed"
        private const val FN_CAT = "173c4c582796c7514195728e9ec7460dfeddbb50e5c886c7ac6e690302df61aa"
        private const val FN_SERIES_LIST = "efb21a4b20fbf5718f45417571e1cc66d2a22946338aeeee62ff162e19b95c4c"
        private const val FN_SERIES_DETAIL = "2b8ad32189617e753416c128487a55971d2bf9367af80897e9e72aed49829fb5"

        private const val SORT_LATEST = "createdAt"
        private const val SORT_VIEWS = "viewCount"
        private const val SORT_LIKES = "likeCount"

        private const val KIND_VIDEO = "v"
        private const val KIND_SERIES = "s"
        private const val KIND_TAG = "t"
        const val PREFIX_VIDEO = "$KIND_VIDEO:"
        const val PREFIX_SERIES = "$KIND_SERIES:"
        const val PREFIX_TAG = "$KIND_TAG:"

        private val DEFAULT_CATEGORIES = listOf("自拍流出", "國產AV", "探花", "日本", "麻豆傳媒", "OnlyFans")

        private const val PREF_TITLE_LANG = "title_lang"
        private const val TITLE_SIMPLIFIED = "zh-Hans"
        private const val TITLE_TRADITIONAL = "zh-Hant"
        private const val PREF_SAVED_TAGS = "saved_tags"
        private const val PREF_HOT_SEARCHES = "hot_searches"

        private val DATE_FORMAT by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }
    }
}
