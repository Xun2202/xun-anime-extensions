package eu.kanade.tachiyomi.animeextension.all.e621

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
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.network.rateLimit
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

/**
 * e621.net videos. Public JSON only (`/posts.json`, `/tags.json`), no login.
 * The client stays at 2 requests per second, which is what e621 asks for.
 */
class E621 :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = E621Parser.SOURCE_NAME

    override val baseUrl = "https://e621.net"

    override val lang = E621Parser.SOURCE_LANG

    override val supportsLatest = true

    override val supportsRelatedAnimes = false

    override val client = network.client.newBuilder()
        .rateLimit(2)
        .build()

    private val preferences by getPreferencesLazy()

    private var cachedArtist: String? = null
    private var cachedPosts: List<E621Parser.Post>? = null

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", E621Parser.USER_AGENT)
        .set("Accept", "application/json")

    private val playbackHeaders: Headers by lazy {
        Headers.Builder()
            .add("User-Agent", E621Parser.USER_AGENT)
            .add("Referer", "$baseUrl/")
            .add("Accept", "video/webm,video/mp4,video/*;q=0.9,*/*;q=0.5")
            .build()
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = postsRequest(E621Parser.popularTags(), page, E621Parser.LISTING_PAGE_SIZE)

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response, E621Parser.LISTING_PAGE_SIZE)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = postsRequest(E621Parser.latestTags(), page, E621Parser.LISTING_PAGE_SIZE)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListing(response, E621Parser.LISTING_PAGE_SIZE)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val tags = E621Parser.searchTags(query, filters.uriPart<OrderFilter>(), filters.extraTags())
        val listing = fetchListing(tags, page, E621Parser.LISTING_PAGE_SIZE)
        val entries = listing.posts.mapNotNull(E621Parser::entryFromPost).map { it.toSAnime() }
        val artist = if (page == 1) E621Parser.artistCandidate(query)?.let { lookupArtist(it) } else null
        if (artist == null) return AnimesPage(entries, listing.hasNextPage)
        val thumbnail = listing.posts.firstOrNull()?.let(E621Parser::thumbnailOf)
        val collection = E621Parser.collectionPlaceholder(artist, thumbnail).toSAnime()
        return AnimesPage(listOf(collection) + entries, listing.hasNextPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val tag = E621Parser.artistFromCollection(anime.url) ?: return super.getAnimeDetails(anime)
        return E621Parser.collectionEntry(tag, loadCollection(tag)).toSAnime().apply { initialized = true }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val post = response.use { E621Parser.parsePostOrNull(it.body.string()) }
            ?: throw Exception("e621 没有返回帖子")
        return E621Parser.entryFromPost(post)?.toSAnime()
            ?: throw Exception("这条帖子没有可播放的视频")
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val tag = E621Parser.artistFromCollection(anime.url) ?: return super.getEpisodeList(anime)
        return E621Parser.collectionEpisodes(loadCollection(tag)).map { it.toSEpisode() }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val post = response.use { E621Parser.parsePostOrNull(it.body.string()) } ?: return emptyList()
        return listOf(E621Parser.singleEpisode(post).toSEpisode())
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val post = response.use { E621Parser.parsePostOrNull(it.body.string()) } ?: return emptyList()
        val preferred = preferences.getString(PREF_QUALITY_KEY, E621Parser.QUALITY_BEST) ?: E621Parser.QUALITY_BEST
        return E621Parser.sortQualities(E621Parser.videoLinks(post), preferred).map { link ->
            Video(
                videoUrl = link.url,
                videoTitle = link.label,
                resolution = link.resolution,
                headers = playbackHeaders,
            )
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val preferred = preferences.getString(PREF_QUALITY_KEY, E621Parser.QUALITY_BEST) ?: E621Parser.QUALITY_BEST
        val ranked = sortedByDescending { it.resolution ?: 0 }
        val want = preferred.substringBefore('p').toIntOrNull() ?: return ranked
        return ranked.sortedByDescending { it.resolution == want }
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        response.close()
        return emptyList()
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "首选清晰度"
            entries = E621Parser.QUALITY_OPTIONS
            entryValues = E621Parser.QUALITY_OPTIONS
            setDefaultValue(E621Parser.QUALITY_BEST)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("搜索作者名（如 vicineko）或 artist:标签 时，第一条是作者合集"),
        OrderFilter(),
        ExtraTagFilter(),
    )

    // ============================== Requests ==============================

    private fun parseListing(response: Response, limit: Int): AnimesPage {
        val listing = response.use { E621Parser.listing(it.body.string(), limit) }
        return AnimesPage(listing.posts.mapNotNull(E621Parser::entryFromPost).map { it.toSAnime() }, listing.hasNextPage)
    }

    private suspend fun fetchPosts(tags: String, page: Int, limit: Int): List<E621Parser.Post> = client.newCall(postsRequest(tags, page, limit)).awaitSuccess().use { response ->
        E621Parser.parsePosts(response.body.string())
    }

    private suspend fun fetchListing(tags: String, page: Int, limit: Int): E621Parser.Listing = client.newCall(postsRequest(tags, page, limit)).awaitSuccess().use { response ->
        E621Parser.listing(response.body.string(), limit)
    }

    private suspend fun lookupArtist(name: String): String? = runCatching {
        val url = "$baseUrl/tags.json".toHttpUrl().newBuilder()
            .addQueryParameter("search[name_matches]", name)
            .addQueryParameter("search[category]", E621Parser.ARTIST_CATEGORY.toString())
            .addQueryParameter("limit", "20")
            .build()
        val body = client.newCall(GET(url, headers)).awaitSuccess().use { it.body.string() }
        E621Parser.exactArtist(body, name)
    }.getOrNull()

    private suspend fun loadCollection(tag: String): List<E621Parser.Post> {
        synchronized(collectionLock) {
            if (cachedArtist == tag && cachedPosts != null) return cachedPosts!!
        }
        val posts = E621Parser.collectPages(
            pageSize = E621Parser.COLLECTION_PAGE_SIZE,
            maxPages = E621Parser.MAX_COLLECTION_PAGES,
        ) { page ->
            fetchPosts(E621Parser.collectionTags(tag), page, E621Parser.COLLECTION_PAGE_SIZE)
        }
        synchronized(collectionLock) {
            cachedArtist = tag
            cachedPosts = posts
        }
        return posts
    }

    private fun postsRequest(tags: String, page: Int, limit: Int): Request {
        val url = "$baseUrl/posts.json".toHttpUrl().newBuilder()
            .addQueryParameter("tags", tags)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    private fun E621Parser.Entry.toSAnime(): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(baseUrl + path)
        title = this@toSAnime.title
        author = this@toSAnime.author
        description = this@toSAnime.description
        genre = genres
        thumbnail_url = thumbnail
        status = if (collection) SAnime.ONGOING else SAnime.COMPLETED
    }

    private fun E621Parser.EpisodeDraft.toSEpisode(): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(baseUrl + path)
        name = this@toSEpisode.name
        episode_number = episodeNumber
        date_upload = dateUpload
        preview_url = preview
    }

    private fun AnimeFilterList.extraTags(): String = (find { it is ExtraTagFilter } as? ExtraTagFilter)?.state.orEmpty()

    private inline fun <reified R> AnimeFilterList.uriPart(): String = (find { it is R } as? UriPartFilter)?.toUriPart().orEmpty()

    private class OrderFilter :
        UriPartFilter(
            "排序",
            arrayOf(
                Pair("默认", ""),
                Pair("评分", "score"),
                Pair("收藏数", "favcount"),
                Pair("最新", "id"),
            ),
        )

    private class ExtraTagFilter : AnimeFilter.Text("附加标签", "")

    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val collectionLock = Any()
    }
}
