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
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

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
    override fun latestUpdatesRequest(page: Int): Request = if (page <= 1) GET("$baseUrl/", headers) else GET("$baseUrl/new/${page - 1}", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListingPage(response)

    override fun latestUpdatesSelector(): String = LISTING_SELECTOR

    override fun latestUpdatesFromElement(element: Element): SAnime = listingItemFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = NEXT_PAGE_SELECTOR

    // =============================== Search ===============================

    override fun getFilterList(): AnimeFilterList {
        val lists = myLists
        if (lists == null) fetchMyListsAsync()
        return buildFilterList(lists, isLoggedIn())
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val p = page - 1

        // Keyword + a favorites list: search inside that list (all pages are fetched and filtered locally,
        // so everything comes back on page 1). The keyword rides in the URL fragment, which OkHttp never sends.
        val listInput = filters.firstOfType<FavoriteListFilter>()?.state?.trim().orEmpty()
        if (query.isNotBlank() && listInput.isNotBlank()) {
            val listPath = resolveFavoritePath(listInput)
            val fragment = if (page > 1) IN_LIST_DONE else IN_LIST_QUERY + query.trim()
            return GET("$baseUrl$listPath/0#$fragment", headers)
        }

        filters.firstOfType<MyListVideosFilter>()?.selectedValue?.takeIf { it.isNotBlank() }?.let { id ->
            requireLogin()
            // With a keyword every page is fetched and filtered locally, so everything comes back on page 1.
            if (query.isNotBlank()) {
                val fragment = if (page > 1) IN_LIST_DONE else IN_LIST_QUERY + query.trim()
                return POST("$baseUrl$PLAYLISTS_API/list/$id/0#$fragment", ajaxHeaders)
            }
            return POST("$baseUrl$PLAYLISTS_API/list/$id/$p", ajaxHeaders)
        }

        if (query.isNotBlank()) {
            // Anikku searches the plain uploader / model name when it is tapped on a details page; the site's
            // keyword search does not match names, so go straight to the profile we saw on that page.
            profilePaths[query.trim().lowercase()]?.let { profilePath ->
                return GET("$baseUrl$profilePath/videos/best/$p", ajaxHeaders)
            }
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
        val isListApi = url.encodedPath.startsWith("$PLAYLISTS_API/list/")
        url.fragment?.let { fragment ->
            if (fragment == IN_LIST_DONE) return AnimesPage(emptyList(), false)
            if (fragment.startsWith(IN_LIST_QUERY)) {
                val query = fragment.removePrefix(IN_LIST_QUERY)
                return if (isListApi) searchInsideListApi(response, query) else searchInsideList(response, query)
            }
        }
        if (isListApi) {
            val list = parsePlaylistDetail(response)
            return AnimesPage(list.videos.filter { it.isLive() }.map(::playlistVideoToAnime), list.nbMore > 0)
        }
        if (url.encodedPath.startsWith("$PLAYLISTS_API/alpha")) {
            return parsePlaylistsJson(response)
        }
        val isProfileJson = url.pathSegments.contains("videos") && url.pathSegments.contains("best")
        if (isProfileJson) {
            return parseProfileJson(response)
        }
        val keyword = url.queryParameter("k")?.trim().orEmpty()
        if (keyword.isNotBlank() && url.queryParameter("p") == "0") {
            val document = response.asJsoup()
            val page = parseListingPage(response, document)
            if (page.animes.isEmpty()) return searchProfileFallback(document, keyword)
            return page
        }
        return parseListingPage(response)
    }

    /**
     * A keyword search for a name usually returns nothing because the site only matches titles. Try the
     * channel / model tile the results page may carry, then a plain user profile with that name.
     */
    private fun searchProfileFallback(document: Document, keyword: String): AnimesPage {
        val tile = document.select("div.thumb-block-profile").firstOrNull { tile ->
            tile.selectFirst("p.profile-name a")?.text()?.trim().equals(keyword, ignoreCase = true)
        }
        val candidates = listOfNotNull(
            tile?.selectFirst("p.profile-name a")?.attr("href")?.takeIf { it.isNotBlank() },
            "/profiles/" + keyword.lowercase().replace(Regex("[\\s_]+"), "-"),
        )
        for (profilePath in candidates) {
            val response = client.newCall(GET("$baseUrl$profilePath/videos/best/0", ajaxHeaders)).execute()
            if (!response.isSuccessful) {
                response.close()
                continue
            }
            val page = runCatching { parseProfileJson(response) }.getOrNull() ?: continue
            if (page.animes.isNotEmpty()) {
                profilePaths[keyword.lowercase()] = profilePath
                return page
            }
        }
        throw Exception("没有标题包含「$keyword」的视频；按上传者 / 模特名查到的主页也没有公开视频")
    }

    private fun parseListingPage(response: Response, document: Document = response.asJsoup()): AnimesPage {
        val url = response.request.url
        val isAccountSection = url.pathSegments.first() in ACCOUNT_SECTIONS
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

    private fun searchInsideListApi(firstPage: Response, query: String): AnimesPage {
        val keywords = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val id = firstPage.request.url.pathSegments.getOrNull(3) ?: throw Exception("无法识别收藏夹 id")
        val videos = collectPlaylistVideos(firstPage, id).second.filter { it.isLive() }
        val hits = videos.filter { v ->
            val haystack = "${v.tf ?: v.t ?: ""} ${v.pn ?: ""}".lowercase()
            keywords.all { haystack.contains(it) }
        }
        if (hits.isEmpty()) throw Exception("这个收藏夹里没有标题或上传者包含「$query」的视频（共 ${videos.size} 个）")
        return AnimesPage(hits.map(::playlistVideoToAnime), false)
    }

    private fun PlaylistVideoDto.isLive(): Boolean = !deleted && !u.isNullOrBlank()

    /** One favorites-list video as a standalone entry (its own details page, one episode). */
    private fun playlistVideoToAnime(v: PlaylistVideoDto): SAnime = SAnime.create().apply {
        url = canonicalVideoPath(v.u!!) ?: v.u.substringBefore('?')
        title = Parser.unescapeEntities(v.tf ?: v.t ?: v.eid, false)
        thumbnail_url = v.i
        author = v.pn
    }

    /**
     * The site writes the same video several ways: `/video.{eid}/{slug}` (API), `/video.{eid}/56819569/0/{slug}`
     * (listing pages, with a per-request tracking segment), `…?pl=123` (favorites pages) and
     * `/prof-video-click/{type}/{user}/{eid}/{slug}` (profile pages). Anikku keys entries by url, so collapse
     * all of them to `/video.{eid}/{slug}` or the same video ends up in the library several times.
     */
    private fun canonicalVideoPath(href: String): String? {
        val path = (href.toHttpUrlOrNull()?.encodedPath ?: href.substringBefore('?').substringBefore('#'))
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        val eid: String
        val slug: String
        when {
            segments.firstOrNull()?.startsWith("video.") == true -> {
                eid = segments.first().removePrefix("video.")
                slug = segments.drop(1).lastOrNull { !it.all(Char::isDigit) } ?: "_"
            }
            segments.firstOrNull() == "prof-video-click" && segments.size >= 5 -> {
                eid = segments[segments.size - 2]
                slug = segments.last()
            }
            else -> return null
        }
        if (eid.isBlank()) return null
        return "/video.$eid/${slug.trimEnd('.').ifBlank { "_" }}"
    }

    /**
     * The "pick one of my lists" filter needs the list names up front; fetch them once in the background
     * (filters are built on the UI thread) and ask the user to hit "reset" once they are in.
     */
    @Volatile private var myLists: List<Pair<String, Long>>? = null

    @Volatile private var myListsFetching = false

    private fun fetchMyListsAsync() {
        if (myListsFetching || !isLoggedIn()) return
        myListsFetching = true
        thread(isDaemon = true) {
            runCatching {
                val result = mutableListOf<Pair<String, Long>>()
                var page = 0
                while (page < MAX_ALPHA_PAGES) {
                    val response = client.newCall(POST("$baseUrl$PLAYLISTS_API/alpha/$page", ajaxHeaders)).execute()
                    val dto = parsePlaylistEnvelope<PlaylistsAlphaDto>(response)
                    val lists = dto.data?.lists.orEmpty()
                    result += lists.map { "${it.name}（${it.nbVideos}）" to it.id }
                    val meta = dto.metadata
                    if (lists.isEmpty() || meta == null || (meta.page + 1) * meta.nbPerPage >= meta.nbLists) break
                    page++
                }
                myLists = result
            }
            myListsFetching = false
        }
    }

    /**
     * Walks every page of a favorites list (HTML listing, so it works for public lists without login and
     * for the user's own lists with the WebView cookies) and keeps the videos whose title or uploader
     * contains all the keywords. Each hit is a normal video entry, so it opens as its own page.
     */
    private fun searchInsideList(firstPage: Response, query: String): AnimesPage {
        val keywords = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val listUrl = firstPage.request.url
        val basePath = listUrl.encodedPath.substringBeforeLast('/')
        val seen = HashSet<String>()
        val hits = mutableListOf<SAnime>()

        var document = firstPage.asJsoup()
        var page = 0
        while (true) {
            val items = document.select(LISTING_SELECTOR).filter { it.isVideoBlock() }
            if (items.isEmpty() && page == 0 && document.title().contains("login", ignoreCase = true)) {
                throw Exception(LOGIN_HINT)
            }
            for (element in items) {
                val anime = listingItemFromElement(element)
                if (!seen.add(anime.url.substringBefore('?'))) continue
                val haystack = "${anime.title} ${anime.author.orEmpty()}".lowercase()
                if (keywords.all { haystack.contains(it) }) hits += anime
            }
            page++
            val hasNext = document.selectFirst(NEXT_PAGE_SELECTOR) != null
            if (!hasNext || items.isEmpty() || page >= MAX_LIST_PAGES) break
            document = client.newCall(GET("$baseUrl$basePath/$page", headers)).execute().asJsoup()
        }

        if (hits.isEmpty()) throw Exception("列表里没有标题或上传者包含「$query」的视频（共翻了 $page 页）")
        return AnimesPage(hits, false)
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
                    appendLine("每个“剧集”对应列表里的一个视频，顺序与网站列表一致（新加入的在上面）；加入书架后列表有新增会像追更一样出现。")
                    appendLine("剧集太多时：右上角筛选 → 「排除翻译组」按上传者过滤，排序可选 按图源 / 按章节编号（= 加入收藏的先后）/ 按上传日期 / 按名称。")
                    appendLine()
                    appendLine("想让每个视频都是书架里独立的条目：浏览页 → 筛选 → 「我的某个收藏夹 → 每个视频一个条目」选这个收藏夹 → 长按任意一个进入批量选择 → 全选 → 加入书架（可顺手建一个同名分类）。")
                    appendLine("同一处再输入搜索词 = 只列出标题 / 上传者匹配的视频。")
                    append(baseUrl + list.url)
                }
            }
        }
        return super.animeDetailsParse(response)
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val ld = extractJsonLd(document)

        anime.title = ld?.name?.takeIf { it.isNotBlank() }
            // The title may be wrapped in an "automatically translated" span, so ownText() alone can be empty
            ?: document.selectFirst("h2.page-title")?.let { h2 ->
                h2.selectFirst("#title-auto-tr")?.text() ?: h2.ownText()
            }?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: ""

        anime.thumbnail_url = ld?.thumbnailUrl?.firstOrNull()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val uploaderLink = document.selectFirst("li.main-uploader a[href]")
        val uploader = uploaderLink?.selectFirst("span.name")?.text()?.trim()
        val modelLinks = document.select("li.model a[href], li.pornstar a[href]")
        val models = modelLinks.mapNotNull { it.selectFirst("span.name")?.text()?.trim()?.takeIf(String::isNotBlank) }
        anime.author = uploader
        anime.artist = models.joinToString().ifBlank { null }
        rememberProfile(uploader, uploaderLink?.attr("href"))
        modelLinks.forEach { rememberProfile(it.selectFirst("span.name")?.text()?.trim(), it.attr("href")) }

        anime.genre = document.select("a.is-keyword").map { it.text().trim() }.filter { it.isNotBlank() }.joinToString()
        anime.status = SAnime.COMPLETED

        val duration = document.selectFirst("h2.page-title span.duration")?.text()
        val hdMark = document.selectFirst("h2.page-title span.video-hd-mark")?.text()
        val views = document.selectFirst("#v-views strong")?.text()
        // The site now shows percentages (`<span class="rating-good-perc">99<span>.0</span>%</span>`)
        val likes = document.selectFirst("span.rating-good-perc, span.rating-good-nbr")?.text()?.replace(" ", "")
        val dislikes = document.selectFirst("span.rating-bad-perc, span.rating-bad-nbr")?.text()?.replace(" ", "")

        anime.description = buildString {
            uploader?.let { appendLine("上传者: $it") }
            if (models.isNotEmpty()) appendLine("出演: ${models.joinToString()}")
            duration?.let { appendLine("时长: $it") }
            hdMark?.let { appendLine("画质: $it") }
            views?.let { appendLine("观看: $it") }
            if (likes != null || dislikes != null) appendLine("好评/差评: ${likes ?: "-"} / ${dislikes ?: "-"}")
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
            preview_url = ld?.thumbnailUrl?.firstOrNull()
            summary = document.selectFirst("h2.page-title span.duration")?.text()?.trim()
        }
        return listOf(episode)
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================== Related ===============================

    /**
     * Anikku's "related" row. The request is [animeDetailsRequest]: for a favorites-list entry that is the
     * list API (its videos become the related entries, each opening as its own page); for a video page the
     * site's own recommendations live in a `video_related=[…]` script rather than in HTML tiles.
     */
    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        if (response.request.url.encodedPath.startsWith("$PLAYLISTS_API/list/")) {
            return parsePlaylistDetail(response).videos.filter { it.isLive() }.map(::playlistVideoToAnime)
        }
        val html = response.body.string()
        val array = extractJsArray(html, "video_related=") ?: return emptyList()
        return runCatching { json.decodeFromString<List<PlaylistVideoDto>>(array) }
            .getOrDefault(emptyList())
            .filter { it.isLive() }
            .map(::playlistVideoToAnime)
    }

    /** The JSON array that follows [marker] in a script, found by bracket matching (titles may contain `]`). */
    private fun extractJsArray(html: String, marker: String): String? {
        val start = html.indexOf(marker).takeIf { it >= 0 }?.let { html.indexOf('[', it) } ?: return null
        if (start < 0) return null
        var depth = 0
        var inString = false
        var i = start
        while (i < html.length) {
            val c = html[i]
            when {
                inString -> when (c) {
                    '\\' -> i++
                    '"' -> inString = false
                }
                c == '"' -> inString = true
                c == '[' -> depth++
                c == ']' -> if (--depth == 0) return html.substring(start, i + 1)
            }
            i++
        }
        return null
    }

    /** uploader / model name (lowercase) → profile path, filled while parsing details pages. */
    private val profilePaths = ConcurrentHashMap<String, String>()

    private fun rememberProfile(name: String?, href: String?) {
        if (name.isNullOrBlank() || href.isNullOrBlank()) return
        // Channel uploaders link as a bare "/javhd" on video pages; the videos API wants "/channels/javhd".
        val path = href.substringBefore('?').substringBefore('#')
        if (path.trim('/').isBlank()) return
        profilePaths[name.trim().lowercase()] = resolveProfilePath(path)
    }

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
        val href = link.absUrl("href").ifBlank { baseUrl + link.attr("href") }
        val canonical = canonicalVideoPath(href)
        if (canonical != null) url = canonical else setUrlWithoutDomain(href)
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
    private fun favoriteListId(url: String): Long? = FAVORITE_PATH_REGEX.find(url)?.groupValues?.get(2)?.toLongOrNull()

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

    private fun parsePlaylistDetail(response: Response): PlaylistDto = parsePlaylistEnvelope<PlaylistDetailDto>(response).data?.list
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
    private fun collectPlaylistVideos(firstPage: Response, id: String): Pair<PlaylistDto, List<PlaylistVideoDto>> {
        val videos = mutableListOf<PlaylistVideoDto>()
        val first = parsePlaylistDetail(firstPage)
        var list = first
        var page = 0
        while (true) {
            videos += list.videos
            page++
            if (list.nbMore <= 0 || list.videos.isEmpty() || page >= MAX_LIST_PAGES) break
            val next = client.newCall(POST("$baseUrl$PLAYLISTS_API/list/$id/$page", ajaxHeaders)).execute()
            list = parsePlaylistDetail(next)
        }
        return first to videos
    }

    private fun playlistEpisodes(response: Response): List<SEpisode> {
        val id = response.request.url.pathSegments.getOrNull(3)
            ?: throw Exception("无法识别收藏夹 id")
        val videos = collectPlaylistVideos(response, id).second

        val live = videos.filter { it.isLive() }
        val total = live.size
        return live.mapIndexed { index, v ->
            SEpisode.create().apply {
                url = v.u!!.substringBefore('?')
                name = Parser.unescapeEntities(v.tf ?: v.t ?: v.eid, false)
                episode_number = (total - index).toFloat()
                scanlator = v.pn
                date_upload = v.ut?.let { it * 1000 } ?: 0L
                // Anikku renders these as a thumbnail + subtitle line in the episode list
                preview_url = v.i?.takeIf { it.isNotBlank() }
                summary = listOfNotNull(
                    v.d?.takeIf { it.isNotBlank() },
                    v.n?.takeIf { it.isNotBlank() }?.let { "$it 观看" },
                    v.r?.takeIf { it.isNotBlank() }?.let { "好评 $it" },
                ).joinToString(" · ").ifBlank { null }
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
        val n: String? = null,
        val r: String? = null,
        val pn: String? = null,
        val ut: Long? = null,
    )

    companion object {
        private const val LISTING_SELECTOR = "div.mozaique div.thumb-block, div#content div.thumb-block"
        private const val NEXT_PAGE_SELECTOR = "a.next-page, .pagination a.next, .pagination li.next a"

        private val ACCOUNT_SECTIONS = setOf("videos-i-like", "watch-later", "history")
        private val PROFILE_PREFIXES = setOf("channels", "models", "pornstars", "profiles", "amateur-channels", "model-channels", "webcam-models")

        private const val LOGIN_HINT = "请先登录：在本源的浏览页点击右上角「在 WebView 中打开」，登录 XVideos 账户后返回重试"

        private val PLAYER_HLS_REGEX = Regex("""html5player\.setVideoHLS\('([^']+)'\)""")
        private val PLAYER_HIGH_REGEX = Regex("""html5player\.setVideoUrlHigh\('([^']+)'\)""")
        private val PLAYER_LOW_REGEX = Regex("""html5player\.setVideoUrlLow\('([^']+)'\)""")
        private val QUALITY_NUMBER_REGEX = Regex("""(\d{3,4})p""")
        private val FAVORITE_PATH_REGEX = Regex("""/(favorite|playlist)/(\d+)""")
        private const val PLAYLISTS_API = "/api/playlists"
        private const val IN_LIST_QUERY = "inlist="
        private const val IN_LIST_DONE = "inlist-done"
        private const val MAX_LIST_PAGES = 80 // 27 videos per page => ~2000 videos
        private const val MAX_ALPHA_PAGES = 10 // 30 lists per page

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

        private const val PREF_QUALITY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val QUALITY_LIST = arrayOf("2160p", "1440p", "1080p", "720p", "480p", "360p", "250p")

        private const val PREF_PREFER_HLS = "prefer_hls"
        private const val PREF_BEST_CURRENT_MONTH = "best_current_month"
    }
}
