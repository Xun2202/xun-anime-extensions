package eu.kanade.tachiyomi.animeextension.all.kemonoxun

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import keiyoushi.network.rateLimit
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.applicationContext
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Kemono.cr / Coomer.st videos. A creator is one anime; each post that contains a video file is one episode.
 * API calls send `Accept: text/css` (anything else is 403) and stay at about one request per second.
 */
class KemonoXun :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = KemonoXunParser.SOURCE_NAME

    override val lang = KemonoXunParser.SOURCE_LANG

    override val supportsLatest = true

    override val supportsRelatedAnimes = false

    override val baseUrl: String
        get() = KemonoXunParser.effectiveBase(
            preferences.getString(PREF_BASE, KemonoXunParser.DEFAULT_BASE),
            preferences.getString(PREF_BASE_CUSTOM, ""),
        )

    override val client = network.client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .rateLimit(1)
        .build()

    private val preferences by getPreferencesLazy()

    private val creatorCache by lazy {
        KemonoXunParser.CreatorCache(File(cacheDir(), "kemonoxun"))
    }

    private val creatorMutex = Mutex()
    private val walkMutex = Mutex()
    private var walkKey: String? = null
    private var walk: KemonoXunParser.PostWalk? = null

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", KemonoXunParser.USER_AGENT)
        .set("Accept", "text/css")

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = creatorBrowse(
        page = page,
        query = "",
        include = emptySet(),
        exclude = emptySet(),
        sort = KemonoXunParser.CreatorSort.FAVORITED,
    )

    override fun popularAnimeRequest(page: Int): Request = GET(api("api", "v1", "creators"), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = creatorPageFromResponse(
        response,
        KemonoXunParser.CreatorSort.FAVORITED,
    )

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = creatorBrowse(
        page = page,
        query = "",
        include = emptySet(),
        exclude = emptySet(),
        sort = KemonoXunParser.CreatorSort.UPDATED,
    )

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = creatorPageFromResponse(
        response,
        KemonoXunParser.CreatorSort.UPDATED,
    )

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val direct = KemonoXunParser.parseDirectLink(query)
        if (direct != null) {
            if (page > 1) return AnimesPage(emptyList(), false)
            return directPage(direct)
        }
        if (filters.mode() == MODE_VIDEOS) return recentVideos(page, query)
        val (include, exclude) = filters.services()
        return creatorBrowse(page, query, include, exclude, filters.sort())
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = when (val link = KemonoXunParser.parseDirectLink(anime.url)) {
        is KemonoXunParser.DirectLink.User -> creatorDetails(link.service, link.id, anime.title)
        is KemonoXunParser.DirectLink.Post -> postDetails(link)
        null -> anime.apply { initialized = true }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val profile = response.use { KemonoXunParser.parseProfile(it.body.string()) }
        val service = profile.service.ifBlank { "patreon" }
        val id = profile.id
        return KemonoXunParser.entryFromCreator(
            KemonoXunParser.Creator(
                id = id,
                name = profile.displayName(),
                service = service,
            ),
            baseUrl,
        )?.toSAnime() ?: throw IllegalStateException(KemonoXunParser.BAD_JSON_MESSAGE)
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = when (val link = KemonoXunParser.parseDirectLink(anime.url)) {
        is KemonoXunParser.DirectLink.User -> {
            val loaded = loadWalk(link.service, link.id, currentMaxPages())
            val episodes = KemonoXunParser.videoEpisodes(loaded.posts, baseUrl)
            if (episodes.isEmpty()) noteNoVideos(anime)
            episodes.map { it.toSEpisode() }
        }
        is KemonoXunParser.DirectLink.Post -> {
            val detail = loadDetail(link)
            val episode = KemonoXunParser.episodeFromDetail(detail, baseUrl)
            if (episode == null) {
                noteNoVideos(anime)
                emptyList()
            } else {
                listOf(episode.toSEpisode())
            }
        }
        null -> emptyList()
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val posts = response.use { KemonoXunParser.parsePosts(it.body.string()) }
        return KemonoXunParser.videoEpisodes(posts, baseUrl).map { it.toSEpisode() }
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val link = KemonoXunParser.parseDirectLink(episode.url) as? KemonoXunParser.DirectLink.Post
            ?: throw IllegalStateException(KemonoXunParser.NO_PLAYABLE_MESSAGE)
        val detail = loadDetail(link)
        return videosFrom(detail)
    }

    override fun videoListParse(response: Response): List<Video> {
        val body = response.use { it.body.string() }
        KemonoXunParser.ensureOk(response.code, body)
        return videosFrom(KemonoXunParser.parseDetail(body))
    }

    override fun List<Video>.sortVideos(): List<Video> = sortedByDescending { it.resolution ?: 0 }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        response.close()
        return emptyList()
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val pageValues = (1..KemonoXunParser.MAX_POST_PAGES).map { it.toString() }.toTypedArray()
        val pageLabels = pageValues.map { raw ->
            val pages = raw.toInt()
            "$pages 页（最多 ${pages * KemonoXunParser.PAGE_SIZE} 帖）"
        }.toTypedArray()

        ListPreference(screen.context).apply {
            key = PREF_BASE
            title = "站点"
            entries = arrayOf("kemono.cr", "coomer.st")
            entryValues = KemonoXunParser.BASE_CHOICES
            setDefaultValue(KemonoXunParser.DEFAULT_BASE)
            summary = "%s\n书架里存的是不带域名的路径，换站点后同一条创作者还能打开。"
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_BASE_CUSTOM
            title = "自定义站点"
            dialogTitle = "自定义站点"
            dialogMessage = "留空则用上面的列表。只接受 https:// 开头、没有路径的地址。"
            summary = "留空使用上方列表"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val text = newValue?.toString().orEmpty()
                text.isBlank() || KemonoXunParser.normalizeBaseUrl(text) != null
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_MAX_PAGES
            title = "每个创作者最多加载"
            entries = pageLabels
            entryValues = pageValues
            setDefaultValue(KemonoXunParser.DEFAULT_POST_PAGES.toString())
            summary = "%s\n每页 50 帖，只保留里面的视频。越大越慢。"
        }.also(screen::addPreference)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("创作者：按名字。近期视频帖：搜索词发给站点，留空则用 mp4。也可以粘贴创作者或帖子链接。排序只对创作者列表生效。"),
        ModeFilter(),
        SortFilter(),
        ServiceFilter(),
    )

    // ============================== Requests ==============================

    private suspend fun creatorBrowse(
        page: Int,
        query: String,
        include: Set<String>,
        exclude: Set<String>,
        sort: KemonoXunParser.CreatorSort,
    ): AnimesPage {
        val creators = loadCreators()
        val listing = KemonoXunParser.browseCreators(
            creators = creators,
            page = page,
            query = query,
            includeServices = include,
            excludeServices = exclude,
            sort = sort,
        )
        val entries = listing.creators.mapNotNull { KemonoXunParser.entryFromCreator(it, baseUrl) }
        return AnimesPage(entries.map { it.toSAnime() }, listing.hasNextPage)
    }

    private fun creatorPageFromResponse(response: Response, sort: KemonoXunParser.CreatorSort): AnimesPage {
        val creators = response.use { body ->
            val text = body.body.string()
            KemonoXunParser.ensureOk(body.code, text)
            KemonoXunParser.parseCreators(text)
        }
        val listing = KemonoXunParser.browseCreators(creators, page = 1, sort = sort)
        val entries = listing.creators.mapNotNull { KemonoXunParser.entryFromCreator(it, baseUrl) }
        return AnimesPage(entries.map { it.toSAnime() }, listing.hasNextPage)
    }

    private suspend fun recentVideos(page: Int, query: String): AnimesPage {
        val keyword = KemonoXunParser.recentQuery(query)
        val offset = (page - 1).coerceAtLeast(0) * KemonoXunParser.PAGE_SIZE
        val url = api("api", "v1", "posts", query = mapOf("q" to keyword, "o" to offset.toString()))
        val fetched = open(url)
        if (KemonoXunParser.isOffsetError(fetched.code, fetched.body)) return AnimesPage(emptyList(), false)
        KemonoXunParser.ensureOk(fetched.code, fetched.body)
        val posts = KemonoXunParser.parsePosts(fetched.body)
        val entries = posts.mapNotNull { KemonoXunParser.entryFromPost(it, baseUrl, requireVideo = true) }
        return AnimesPage(entries.map { it.toSAnime() }, KemonoXunParser.recentHasNext(posts.size))
    }

    private suspend fun directPage(link: KemonoXunParser.DirectLink): AnimesPage {
        val entry = when (link) {
            is KemonoXunParser.DirectLink.User -> {
                val profile = runCatching { KemonoXunParser.parseProfile(getJson(profileUrl(link))) }.getOrNull()
                KemonoXunParser.entryFromCreator(
                    KemonoXunParser.Creator(
                        id = link.id,
                        name = profile?.displayName() ?: link.id,
                        service = link.service,
                    ),
                    baseUrl,
                )
            }
            is KemonoXunParser.DirectLink.Post -> {
                val detail = loadDetail(link)
                val post = detail.post ?: return AnimesPage(emptyList(), false)
                KemonoXunParser.entryFromPost(post, baseUrl, requireVideo = false)
            }
        }
        return AnimesPage(listOfNotNull(entry?.toSAnime()), false)
    }

    private suspend fun creatorDetails(service: String, id: String, fallbackTitle: String): SAnime {
        val profile = KemonoXunParser.parseProfile(getJson(profileUrl(KemonoXunParser.DirectLink.User(service, id))))
        val maxPages = currentMaxPages()
        val loaded = loadWalk(service, id, maxPages)
        val videos = KemonoXunParser.videoEpisodes(loaded.posts, baseUrl)
        val display = profile.displayName().ifBlank { fallbackTitle.ifBlank { id } }
        return SAnime.create().apply {
            setUrlWithoutDomain(baseUrl.trimEnd('/') + KemonoXunParser.creatorPath(service, id))
            title = display
            author = KemonoXunParser.serviceName(service)
            genre = KemonoXunParser.serviceName(service)
            thumbnail_url = KemonoXunParser.iconUrl(baseUrl, service, id)
            background_url = KemonoXunParser.bannerUrl(baseUrl, service, id)
            description = KemonoXunParser.creatorDescription(
                base = baseUrl,
                service = service,
                id = id,
                name = display,
                postCount = profile.postCount,
                videoPosts = videos.size,
                pagesLoaded = loaded.pagesFetched,
                maxPages = maxPages,
            )
            status = SAnime.ONGOING
            initialized = true
        }
    }

    private suspend fun postDetails(link: KemonoXunParser.DirectLink.Post): SAnime {
        val detail = loadDetail(link)
        val post = detail.post ?: throw IllegalStateException(KemonoXunParser.BAD_JSON_MESSAGE)
        val entry = KemonoXunParser.entryFromPost(post, baseUrl, requireVideo = false)
            ?: throw IllegalStateException(KemonoXunParser.BAD_JSON_MESSAGE)
        val note = if (KemonoXunParser.detailHasVideo(detail)) "" else "\n" + KemonoXunParser.NO_VIDEO_NOTE
        return entry.toSAnime().apply {
            description = entry.description + note
            initialized = true
        }
    }

    private suspend fun loadCreators(): List<KemonoXunParser.Creator> {
        val base = baseUrl
        creatorCache.read(base)?.let { return it }
        return creatorMutex.withLock {
            creatorCache.read(base)?.let { return@withLock it }
            val body = getJson(api("api", "v1", "creators"))
            val parsed = KemonoXunParser.parseCreators(body)
            runCatching { creatorCache.write(base, body, parsed) }
            parsed
        }
    }

    private suspend fun loadWalk(service: String, id: String, maxPages: Int): KemonoXunParser.PostWalk {
        val key = "$baseUrl|$service|$id|$maxPages"
        walkMutex.withLock {
            val cached = walk
            if (walkKey == key && cached != null) return cached
        }
        val loaded = KemonoXunParser.walkPosts(maxPages) { offset ->
            val fetched = open(postsUrl(service, id, offset))
            if (KemonoXunParser.isOffsetError(fetched.code, fetched.body)) {
                KemonoXunParser.PostBatch.End
            } else {
                KemonoXunParser.ensureOk(fetched.code, fetched.body)
                KemonoXunParser.PostBatch.Page(KemonoXunParser.parsePosts(fetched.body))
            }
        }
        walkMutex.withLock {
            walkKey = key
            walk = loaded
        }
        return loaded
    }

    private suspend fun loadDetail(link: KemonoXunParser.DirectLink.Post): KemonoXunParser.PostDetail {
        val body = getJson(postApiUrl(link))
        return KemonoXunParser.parseDetail(body)
    }

    private fun videosFrom(detail: KemonoXunParser.PostDetail): List<Video> {
        val links = KemonoXunParser.videosFromDetail(detail, baseUrl)
        if (links.isEmpty()) throw IllegalStateException(KemonoXunParser.NO_PLAYABLE_MESSAGE)
        val playback = playbackHeaders()
        return links.map { link ->
            Video(
                videoUrl = link.url,
                videoTitle = link.label,
                resolution = link.resolution.takeIf { it > 0 },
                headers = playback,
            )
        }
    }

    private suspend fun getJson(url: String): String {
        val fetched = open(url)
        KemonoXunParser.ensureOk(fetched.code, fetched.body)
        return fetched.body
    }

    private suspend fun open(url: String): Fetched {
        for (attempt in 0..KemonoXunParser.MAX_RETRIES) {
            val code: Int
            val body: String
            try {
                val response = client.newCall(GET(url, headers)).await()
                code = response.code
                body = response.use { it.body.string() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val mapped = KemonoXunParser.transportMessage(error)
                if (mapped != null) throw IllegalStateException(mapped, error)
                throw error
            }
            if (KemonoXunParser.shouldRetryStatus(code, attempt)) {
                delay(KemonoXunParser.retryDelayMillis(attempt))
                continue
            }
            return Fetched(code, body)
        }
        throw IllegalStateException(KemonoXunParser.RATE_LIMIT_MESSAGE)
    }

    private fun postsUrl(service: String, id: String, offset: Int): String = api(
        "api",
        "v1",
        service,
        "user",
        id,
        "posts",
        query = mapOf("o" to offset.toString()),
    )

    private fun postApiUrl(link: KemonoXunParser.DirectLink.Post): String = api(
        "api",
        "v1",
        link.service,
        "user",
        link.id,
        "post",
        link.postId,
    )

    private fun profileUrl(link: KemonoXunParser.DirectLink.User): String = api(
        "api",
        "v1",
        link.service,
        "user",
        link.id,
        "profile",
    )

    private fun api(vararg segments: String, query: Map<String, String> = emptyMap()): String {
        val builder = baseUrl.toHttpUrl().newBuilder()
        for (segment in segments) builder.addPathSegment(segment)
        for ((key, value) in query) builder.addQueryParameter(key, value)
        return builder.build().toString()
    }

    private fun playbackHeaders(): Headers = Headers.Builder()
        .add("User-Agent", KemonoXunParser.USER_AGENT)
        .add("Referer", baseUrl.trimEnd('/') + "/")
        .add("Accept", "video/mp4,video/webm,video/*;q=0.9,*/*;q=0.5")
        .build()

    private fun currentMaxPages(): Int = KemonoXunParser.maxPages(
        preferences.getString(PREF_MAX_PAGES, KemonoXunParser.DEFAULT_POST_PAGES.toString()),
    )

    private fun noteNoVideos(anime: SAnime) {
        val note = KemonoXunParser.NO_VIDEO_NOTE
        val current = anime.description ?: ""
        if (note !in current) anime.description = current.trimEnd() + "\n" + note
    }

    private fun cacheDir(): File = applicationContext.cacheDir

    private fun KemonoXunParser.Entry.toSAnime(): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(baseUrl.trimEnd('/') + path)
        title = this@toSAnime.title
        author = this@toSAnime.author
        description = this@toSAnime.description
        genre = genres
        thumbnail_url = thumbnail
        if (background != null) background_url = background
        status = SAnime.ONGOING
    }

    private fun KemonoXunParser.EpisodeDraft.toSEpisode(): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(baseUrl.trimEnd('/') + path)
        name = this@toSEpisode.name
        episode_number = episodeNumber
        date_upload = dateUpload
        preview_url = preview
    }

    private fun KemonoXunParser.Profile.displayName(): String = name.ifBlank { publicId.orEmpty() }.ifBlank { id }

    private fun AnimeFilterList.mode(): String = uriPart<ModeFilter>().ifEmpty { MODE_CREATORS }

    private fun AnimeFilterList.sort(): KemonoXunParser.CreatorSort = when (uriPart<SortFilter>()) {
        "updated" -> KemonoXunParser.CreatorSort.UPDATED
        "name" -> KemonoXunParser.CreatorSort.NAME
        else -> KemonoXunParser.CreatorSort.FAVORITED
    }

    private fun AnimeFilterList.services(): Pair<Set<String>, Set<String>> {
        val group = find { it is ServiceFilter } as? ServiceFilter ?: return emptySet<String>() to emptySet()
        val include = mutableSetOf<String>()
        val exclude = mutableSetOf<String>()
        for (tri in group.state) {
            when {
                tri.isIncluded() -> include += tri.serviceId
                tri.isExcluded() -> exclude += tri.serviceId
            }
        }
        return include to exclude
    }

    private inline fun <reified R> AnimeFilterList.uriPart(): String = (find { it is R } as? UriPartFilter)?.toUriPart().orEmpty()

    private class ModeFilter :
        UriPartFilter(
            "列表",
            arrayOf(
                Pair("创作者", MODE_CREATORS),
                Pair("近期视频帖", MODE_VIDEOS),
            ),
        )

    private class SortFilter :
        UriPartFilter(
            "排序",
            arrayOf(
                Pair("收藏数", "favorited"),
                Pair("最近更新", "updated"),
                Pair("名称", "name"),
            ),
        )

    private class ServiceFilter :
        AnimeFilter.Group<ServiceTri>(
            "服务",
            KemonoXunParser.FILTER_SERVICES.map { (id, label) -> ServiceTri(label, id) },
        )

    private class ServiceTri(name: String, val serviceId: String) : AnimeFilter.TriState(name)

    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private data class Fetched(val code: Int, val body: String)

    companion object {
        private const val PREF_BASE = "base_url"
        private const val PREF_BASE_CUSTOM = "base_url_custom"
        private const val PREF_MAX_PAGES = "max_post_pages"
        private const val MODE_CREATORS = "creators"
        private const val MODE_VIDEOS = "videos"
    }
}
