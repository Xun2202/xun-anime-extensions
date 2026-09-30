package eu.kanade.tachiyomi.animeextension.all.xvideosxun

import android.webkit.CookieManager
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Xvideos source with the extras the stock extension lacks:
 *  - "Latest" tab, monthly "Best of" as popular
 *  - Full search filters (sort / date / duration / quality)
 *  - Browse by category, tag, channel/model, favorites list
 *  - Account lists (liked / watch later / history) via WebView login cookies
 *  - Per-resolution HLS streams with preferred-quality sorting
 */
class XvideosXun :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "XvXun"

    // Keep the id generated for the original name ("Xvideos (Xun)") so library entries survive the rename.
    override val id = 5320004989093205933L

    override val baseUrl = "https://www.xvideos.com"

    override val lang = "all"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "en-US,en;q=0.9")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val cal = Calendar.getInstance()
        if (!preferences.getBoolean(PREF_BEST_CURRENT_MONTH, false)) {
            cal.add(Calendar.MONTH, -1)
        }
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(cal.time)
        return GET("$baseUrl/best/$month/${page - 1}", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseListingPage(response)

    override fun popularAnimeSelector(): String = LISTING_SELECTOR

    override fun popularAnimeFromElement(element: Element): SAnime = listingItemFromElement(element)

    override fun popularAnimeNextPageSelector(): String = NEXT_PAGE_SELECTOR

    // =============================== Latest ===============================

    // Page 1 of "Newest" is the home page; /new/0 and /new do not exist.
    override fun latestUpdatesRequest(page: Int): Request =
        if (page <= 1) GET("$baseUrl/", headers) else GET("$baseUrl/new/${page - 1}", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListingPage(response)

    override fun latestUpdatesSelector(): String = LISTING_SELECTOR

    override fun latestUpdatesFromElement(element: Element): SAnime = listingItemFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = NEXT_PAGE_SELECTOR

    // =============================== Search ===============================

    override fun getFilterList(): AnimeFilterList = buildFilterList()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val p = page - 1

        if (query.isNotBlank()) {
            val url = "$baseUrl/".toHttpUrl().newBuilder()
                .addQueryParameter("k", query.trim())
                .addQueryParameter("p", p.toString())
            filters.firstOfType<SortFilter>()?.selectedValue?.takeIf { it != "relevance" }?.let { url.addQueryParameter("sort", it) }
            filters.firstOfType<DateFilter>()?.selectedValue?.takeIf { it != "all" }?.let { url.addQueryParameter("datef", it) }
            filters.firstOfType<DurationFilter>()?.selectedValue?.takeIf { it != "allduration" }?.let { url.addQueryParameter("durf", it) }
            filters.firstOfType<QualityFilter>()?.selectedValue?.takeIf { it != "all" }?.let { url.addQueryParameter("quality", it) }
            return GET(url.build(), headers)
        }

        filters.firstOfType<AccountFilter>()?.selectedValue?.takeIf { it.isNotBlank() }?.let { section ->
            requireLogin()
            if (section == ACCOUNT_PLAYLISTS) {
                return POST("$baseUrl$PLAYLISTS_API/alpha/$p", ajaxHeaders)
            }
            return POST("$baseUrl/$section/$p", ajaxHeaders)
        }

        filters.firstOfType<FavoriteListFilter>()?.state?.trim()?.takeIf { it.isNotBlank() }?.let { input ->
            val listPath = resolveFavoritePath(input)
            return GET("$baseUrl$listPath/$p", headers)
        }

        filters.firstOfType<UploaderFilter>()?.state?.trim()?.takeIf { it.isNotBlank() }?.let { input ->
            val profilePath = resolveProfilePath(input)
            return GET("$baseUrl$profilePath/videos/best/$p", ajaxHeaders)
        }

        filters.firstOfType<BestMonthFilter>()?.selectedValue?.takeIf { it.isNotBlank() }?.let { month ->
            return GET("$baseUrl/best/$month/$p", headers)
        }

        filters.firstOfType<CategoryFilter>()?.selectedValue?.takeIf { it.isNotBlank() }?.let { path ->
            return GET("$baseUrl$path/$p", headers)
        }

        filters.firstOfType<TagFilter>()?.state?.trim()?.takeIf { it.isNotBlank() }?.let { tag ->
            val slug = tag.lowercase().replace(Regex("\\s+"), "-")
            return GET("$baseUrl/tags/$slug/$p", headers)
        }

        return latestUpdatesRequest(page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url
        if (url.encodedPath.startsWith("$PLAYLISTS_API/alpha")) {
            return parsePlaylistsJson(response)
        }
        val isProfileJson = url.pathSegments.contains("videos") && url.pathSegments.contains("best")
        if (isProfileJson) {
            return parseProfileJson(response)
        }
        return parseListingPage(response)
    }

    private fun parseListingPage(response: Response): AnimesPage {
        val url = response.request.url
        val isAccountSection = url.pathSegments.first() in ACCOUNT_SECTIONS
        val document = response.asJsoup()
        val items = document.select(LISTING_SELECTOR)
            .filter { it.isVideoBlock() }
            .map(::listingItemFromElement)

        if (items.isEmpty() && isAccountSection) {
            val sessionGone = document.title().contains("login", ignoreCase = true)
            if (sessionGone || (document.selectFirst(".empty-state-box") != null && !isLoggedIn())) {
                throw Exception(LOGIN_HINT)
            }
        }

        val hasNext = document.selectFirst(NEXT_PAGE_SELECTOR) != null ||
            // "Best of" pages: <a class="current"> followed by further page links
            document.selectFirst(".pagination a.current + a[href]") != null ||
            // Account fragments carry no pagination markup; assume more while pages are full
            (isAccountSection && items.size >= 30)
        return AnimesPage(items, hasNext)
    }

    override fun searchAnimeSelector(): String = LISTING_SELECTOR

    override fun searchAnimeFromElement(element: Element): SAnime = listingItemFromElement(element)

    override fun searchAnimeNextPageSelector(): String = NEXT_PAGE_SELECTOR

    private fun parseProfileJson(response: Response): AnimesPage {
        val data = json.decodeFromString<ProfileVideosDto>(response.body.string())
        val animes = data.videos.map { v ->
            SAnime.create().apply {
                val slug = v.u.trimEnd('.').substringAfterLast('/').ifBlank { "video" }
                url = "/video.${v.eid}/$slug"
                title = v.tf ?: v.t ?: slug
                thumbnail_url = v.i
                author = v.pn
            }
        }
        val perPage = data.nbPerPage.takeIf { it > 0 } ?: 36
        val totalPages = (data.nbVideos + perPage - 1) / perPage
        return AnimesPage(animes, data.currentPage + 1 < totalPages)
    }

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun animeDetailsRequest(anime: SAnime): Request {
        favoriteListId(anime.url)?.let { id ->
            return POST("$baseUrl$PLAYLISTS_API/list/$id", ajaxHeaders)
        }
        return super.animeDetailsRequest(anime)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        if (response.request.url.encodedPath.startsWith("$PLAYLISTS_API/list/")) {
            val list = parsePlaylistDetail(response)
            return playlistToAnime(list).apply {
                description = buildString {
                    appendLine("XVideos 收藏夹 / 播放列表")
                    appendLine("视频数: ${list.nbVideos}")
                    list.status?.let { appendLine("可见性: ${if (it == "NOBODY") "私密" else "公开"}") }
                    appendLine()
                    append("每个“剧集”对应列表里的一个视频，加入书架后列表有新增会像追更一样出现")
                }
            }
        }
        return super.animeDetailsParse(response)
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val ld = extractJsonLd(document)

        anime.title = ld?.name?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h2.page-title")?.ownText()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: ""

        anime.thumbnail_url = ld?.thumbnailUrl?.firstOrNull()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val uploader = document.selectFirst("li.main-uploader span.name")?.text()?.trim()
        val models = document.select("li.model span.name").map { it.text().trim() }.filter { it.isNotBlank() }
        anime.author = uploader
        anime.artist = models.joinToString().ifBlank { null }

        anime.genre = document.select("a.is-keyword").map { it.text().trim() }.filter { it.isNotBlank() }.joinToString()
        anime.status = SAnime.COMPLETED

        val duration = document.selectFirst("h2.page-title span.duration")?.text()
        val hdMark = document.selectFirst("h2.page-title span.video-hd-mark")?.text()
        val views = document.selectFirst("#v-views strong")?.text()
        val likes = document.selectFirst("span.rating-good-nbr")?.text()
        val dislikes = document.selectFirst("span.rating-bad-nbr")?.text()

        anime.description = buildString {
            uploader?.let { appendLine("上传者: $it") }
            if (models.isNotEmpty()) appendLine("出演: ${models.joinToString()}")
            duration?.let { appendLine("时长: $it") }
            hdMark?.let { appendLine("画质: $it") }
            views?.let { appendLine("观看: $it") }
            if (likes != null || dislikes != null) appendLine("赞/踩: ${likes ?: "-"} / ${dislikes ?: "-"}")
            ld?.uploadDate?.let { appendLine("上传时间: ${it.substringBefore('T')}") }
            ld?.description?.takeIf { it.isNotBlank() && it != anime.title }?.let {
                appendLine()
                appendLine(it)
            }
        }.trim()

        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        favoriteListId(anime.url)?.let { id ->
            return POST("$baseUrl$PLAYLISTS_API/list/$id/0", ajaxHeaders)
        }
        return super.episodeListRequest(anime)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        if (response.request.url.encodedPath.startsWith("$PLAYLISTS_API/list/")) {
            return playlistEpisodes(response)
        }

        val document = response.asJsoup()
        val ld = extractJsonLd(document)
        val episode = SEpisode.create().apply {
            name = "Video"
            episode_number = 1F
            setUrlWithoutDomain(response.request.url.toString())
            date_upload = DATE_FORMAT.tryParse(ld?.uploadDate).takeIf { it > 0 } ?: System.currentTimeMillis()
        }
        return listOf(episode)
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val html = response.body.string()
        val hls = PLAYER_HLS_REGEX.find(html)?.groupValues?.get(1)
        val high = PLAYER_HIGH_REGEX.find(html)?.groupValues?.get(1)
        val low = PLAYER_LOW_REGEX.find(html)?.groupValues?.get(1)

        val videos = mutableListOf<Video>()

        if (!hls.isNullOrBlank()) {
            runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = hls,
                    referer = "$baseUrl/",
                    videoNameGen = { quality -> "HLS $quality" },
                )
            }.getOrNull()?.let(videos::addAll)
            if (videos.isEmpty()) {
                videos.add(Video(hls, "HLS (auto)", hls, headers = headers))
            }
        }

        if (!high.isNullOrBlank()) {
            videos.add(Video(high, "MP4 High", high, headers = headers))
        }
        if (!low.isNullOrBlank() && low != high) {
            videos.add(Video(low, "MP4 Low", low, headers = headers))
        }

        if (videos.isEmpty()) throw Exception("未找到视频地址")
        return videos
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT)!!
        val preferHls = preferences.getBoolean(PREF_PREFER_HLS, true)
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality) }
                .thenByDescending { (it.videoTitle.startsWith("HLS")) == preferHls }
                .thenByDescending { QUALITY_NUMBER_REGEX.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
        )
    }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = "首选画质"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_PREFER_HLS
            title = "优先 HLS 流"
            summary = "开启：同画质下优先 HLS（可选清晰度更多）；关闭：优先 MP4 直链（下载更稳）"
            setDefaultValue(true)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_BEST_CURRENT_MONTH
            title = "「热门」使用当月精选"
            summary = "默认使用上个月的 Best of（与网站默认一致）；开启后改为当月"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    // ============================== Helpers ===============================

    private val ajaxHeaders: Headers by lazy {
        headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Accept", "application/json, text/plain, */*")
            .build()
    }

    private fun listingItemFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("div.thumb a[href]") ?: element.selectFirst("a[href]")!!
        setUrlWithoutDomain(link.absUrl("href").ifBlank { baseUrl + link.attr("href") })
        val titleLink = element.selectFirst("p.title a")
        title = titleLink?.attr("title")?.takeIf { it.isNotBlank() }
            ?: titleLink?.ownText()?.trim()
            ?: link.attr("title")
        val img = element.selectFirst("img")
        thumbnail_url = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
        author = element.selectFirst("p.metadata span.name")?.text()?.trim()
    }

    /** Only keep tiles that point at a video (home page also carries channel/ad tiles). */
    private fun Element.isVideoBlock(): Boolean {
        val href = selectFirst("a[href]")?.attr("href") ?: return false
        return href.contains("/video") || href.contains("/prof-video-click")
    }

    // ========================= Favorites lists ============================

    /** Numeric list id from a /favorite/{id}/{slug} (or /playlist/…) url, or null for anything else. */
    private fun favoriteListId(url: String): Long? =
        FAVORITE_PATH_REGEX.find(url)?.groupValues?.get(2)?.toLongOrNull()

    private inline fun <reified T : PlaylistEnvelope> parsePlaylistEnvelope(response: Response): T {
        val dto = json.decodeFromString<T>(response.body.string())
        if (dto.metadata?.isLogged == false) throw Exception(LOGIN_HINT)
        if (!dto.result) throw Exception(dto.message ?: "XVideos 返回错误")
        return dto
    }

    /** POST /api/playlists/alpha/{page}: one entry per list, alphabetically sorted. */
    private fun parsePlaylistsJson(response: Response): AnimesPage {
        val dto = parsePlaylistEnvelope<PlaylistsAlphaDto>(response)
        val lists = dto.data?.lists.orEmpty()
        if (lists.isEmpty() && dto.metadata?.page == 0) {
            throw Exception("这个账户还没有任何收藏夹/播放列表")
        }
        val meta = dto.metadata
        val hasNext = meta != null && (meta.page + 1) * meta.nbPerPage < meta.nbLists
        return AnimesPage(lists.map(::playlistToAnime), hasNext)
    }

    private fun parsePlaylistDetail(response: Response): PlaylistDto =
        parsePlaylistEnvelope<PlaylistDetailDto>(response).data?.list
            ?: throw Exception("收藏夹不存在或已被删除")

    private fun playlistToAnime(list: PlaylistDto): SAnime = SAnime.create().apply {
        url = list.url
        title = list.name
        thumbnail_url = list.cover.firstOrNull()
        status = SAnime.ONGOING
        genre = buildList {
            add("收藏夹")
            if (list.watchLater) add("稍后观看")
            if (list.status == "NOBODY") add("私密")
        }.joinToString()
        description = "视频数: ${list.nbVideos}"
    }

    /**
     * POST /api/playlists/list/{id}/{page} returns 27 videos per page and `nb_more` = how many
     * remain after this page; keep going until it hits zero (bounded by [MAX_LIST_PAGES]).
     */
    private fun playlistEpisodes(response: Response): List<SEpisode> {
        val id = response.request.url.pathSegments.getOrNull(3)
            ?: throw Exception("无法识别收藏夹 id")
        val videos = mutableListOf<PlaylistVideoDto>()

        var list = parsePlaylistDetail(response)
        var page = 0
        while (true) {
            videos += list.videos
            page++
            if (list.nbMore <= 0 || list.videos.isEmpty() || page >= MAX_LIST_PAGES) break
            val next = client.newCall(POST("$baseUrl$PLAYLISTS_API/list/$id/$page", ajaxHeaders)).execute()
            list = parsePlaylistDetail(next)
        }

        val live = videos.filter { !it.deleted && !it.u.isNullOrBlank() }
        val total = live.size
        return live.mapIndexed { index, v ->
            SEpisode.create().apply {
                url = v.u!!.substringBefore('?')
                val rawTitle = Parser.unescapeEntities(v.tf ?: v.t ?: v.eid, false)
                name = if (v.d.isNullOrBlank()) rawTitle else "$rawTitle · ${v.d}"
                episode_number = (total - index).toFloat()
                scanlator = v.pn
                date_upload = v.ut?.let { it * 1000 } ?: 0L
            }
        }
    }

    private fun isLoggedIn(): Boolean {
        val cookies = runCatching { CookieManager.getInstance().getCookie(baseUrl) }.getOrNull() ?: return false
        return cookies.contains("session_token")
    }

    private fun requireLogin() {
        if (!isLoggedIn()) throw Exception(LOGIN_HINT)
    }

    /**
     * Accepts a full favorites/playlist URL (https://www.xvideos.com/favorite/12345/name),
     * a path (/favorite/12345/name) or a bare numeric id.
     */
    private fun resolveFavoritePath(input: String): String {
        val path = input.toHttpUrlOrNull()?.encodedPath ?: input
        return when {
            path.startsWith("/favorite/") -> path.trimEnd('/').removeSuffixPage()
            path.startsWith("favorite/") -> "/${path.trimEnd('/')}".removeSuffixPage()
            path.all { it.isDigit() } -> "/favorite/$path/list"
            else -> "/favorite/${path.trim('/')}"
        }
    }

    /**
     * Accepts a profile URL, a path like /channels/xxx or /models/xxx, or a bare username.
     */
    private fun resolveProfilePath(input: String): String {
        val path = (input.toHttpUrlOrNull()?.encodedPath ?: input).trim('/')
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return "/channels/$path"
        return if (segments.size >= 2 && segments[0] in PROFILE_PREFIXES) {
            "/${segments[0]}/${segments[1]}"
        } else {
            "/channels/${segments[0]}"
        }
    }

    private fun String.removeSuffixPage(): String {
        val segments = split('/')
        return if (segments.size > 3 && segments.last().all { it.isDigit() }) {
            segments.dropLast(1).joinToString("/")
        } else {
            this
        }
    }

    private fun extractJsonLd(document: Document): VideoLdDto? {
        for (script in document.select("script[type=application/ld+json]")) {
            val text = script.data().trim()
            if (!text.contains("VideoObject")) continue
            runCatching { json.decodeFromString<VideoLdDto>(text) }.getOrNull()?.let { return it }
        }
        return null
    }

    @Serializable
    private data class VideoLdDto(
        val name: String? = null,
        val description: String? = null,
        val thumbnailUrl: List<String>? = null,
        val uploadDate: String? = null,
        val duration: String? = null,
    )

    @Serializable
    private data class ProfileVideosDto(
        @SerialName("nb_videos") val nbVideos: Int = 0,
        @SerialName("nb_per_page") val nbPerPage: Int = 36,
        @SerialName("current_page") val currentPage: Int = 0,
        val videos: List<ProfileVideoDto> = emptyList(),
    )

    @Serializable
    private data class ProfileVideoDto(
        val eid: String,
        val u: String = "",
        val i: String? = null,
        val tf: String? = null,
        val t: String? = null,
        val pn: String? = null,
    )

    private interface PlaylistEnvelope {
        val result: Boolean
        val message: String?
        val metadata: PlaylistMetaDto?
    }

    @Serializable
    private data class PlaylistMetaDto(
        val isLogged: Boolean = true,
        val nbLists: Int = 0,
        val nbPerPage: Int = 30,
        val page: Int = 0,
    )

    @Serializable
    private data class PlaylistsAlphaDto(
        override val result: Boolean = false,
        override val message: String? = null,
        override val metadata: PlaylistMetaDto? = null,
        val data: PlaylistsAlphaDataDto? = null,
    ) : PlaylistEnvelope

    @Serializable
    private data class PlaylistsAlphaDataDto(val lists: List<PlaylistDto> = emptyList())

    @Serializable
    private data class PlaylistDetailDto(
        override val result: Boolean = false,
        override val message: String? = null,
        override val metadata: PlaylistMetaDto? = null,
        val data: PlaylistDetailDataDto? = null,
    ) : PlaylistEnvelope

    @Serializable
    private data class PlaylistDetailDataDto(val list: PlaylistDto? = null)

    @Serializable
    private data class PlaylistDto(
        val id: Long,
        val url: String,
        val name: String = "",
        val status: String? = null,
        @SerialName("nb_videos") val nbVideos: Int = 0,
        @SerialName("nb_more") val nbMore: Int = 0,
        @SerialName("watch_later") val watchLater: Boolean = false,
        val cover: List<String> = emptyList(),
        val videos: List<PlaylistVideoDto> = emptyList(),
    )

    @Serializable
    private data class PlaylistVideoDto(
        val eid: String,
        val deleted: Boolean = false,
        val u: String? = null,
        val i: String? = null,
        val tf: String? = null,
        val t: String? = null,
        val d: String? = null,
        val pn: String? = null,
        val ut: Long? = null,
    )

    companion object {
        private const val LISTING_SELECTOR = "div.mozaique div.thumb-block, div#content div.thumb-block"
        private const val NEXT_PAGE_SELECTOR = "a.next-page, .pagination a.next, .pagination li.next a"

        private val ACCOUNT_SECTIONS = setOf("videos-i-like", "watch-later", "history")
        private val PROFILE_PREFIXES = setOf("channels", "models", "pornstars", "profiles", "amateur-channels", "model-channels")

        private const val LOGIN_HINT = "请先登录：在本源的浏览页点击右上角「在 WebView 中打开」，登录 XVideos 账户后返回重试"

        private val PLAYER_HLS_REGEX = Regex("""html5player\.setVideoHLS\('([^']+)'\)""")
        private val PLAYER_HIGH_REGEX = Regex("""html5player\.setVideoUrlHigh\('([^']+)'\)""")
        private val PLAYER_LOW_REGEX = Regex("""html5player\.setVideoUrlLow\('([^']+)'\)""")
        private val QUALITY_NUMBER_REGEX = Regex("""(\d{3,4})p""")
        private val FAVORITE_PATH_REGEX = Regex("""/(favorite|playlist)/(\d+)""")
        private const val PLAYLISTS_API = "/api/playlists"
        private const val MAX_LIST_PAGES = 80 // 27 videos per page => ~2000 videos

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

        private const val PREF_QUALITY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val QUALITY_LIST = arrayOf("2160p", "1440p", "1080p", "720p", "480p", "360p", "250p")

        private const val PREF_PREFER_HLS = "prefer_hls"
        private const val PREF_BEST_CURRENT_MONTH = "best_current_month"
    }
}
