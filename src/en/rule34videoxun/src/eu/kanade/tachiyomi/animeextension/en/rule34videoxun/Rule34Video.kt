/*
 * Copyright the original authors of yuzono/anime-extensions.
 * Licensed under the Apache License, Version 2.0.
 * 改自 yuzono/anime-extensions src/en/rule34video v10，已修改。
 */
package eu.kanade.tachiyomi.animeextension.en.rule34videoxun

import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.Executors

/**
 * Rule34Video with author collections and a default of the highest quality on the page.
 *
 * `get_file` links carry a `v-acctoken`. Without the session cookie they die after one use;
 * with the cookie the same link 302s repeatedly, and the boomio CDN URL accepts many Range
 * requests (seek). [videoListParse] follows that 302 and hands the CDN URL to the player.
 * `name` / `lang` stay "Rule34Video" / "en" so the generated source id matches v10.
 */
class Rule34Video :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = Rule34VideoParser.SOURCE_NAME

    override val baseUrl = "https://rule34video.com"

    override val lang = Rule34VideoParser.SOURCE_LANG

    override val supportsLatest = false

    private val ddgInterceptor = DdosGuardInterceptor(network.client)

    override val client = network.client
        .newBuilder()
        .addInterceptor(ddgInterceptor)
        .build()

    private val preferences by getPreferencesLazy()

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val uploaderOnly = preferences.getBoolean(PREF_UPLOADER_FILTER_ENABLED_KEY, false)
        val uploaderId = preferences.getString(PREF_UPLOADER_ID_KEY, "").orEmpty()
        return if (uploaderOnly && uploaderId.isNotBlank()) {
            GET(
                "$baseUrl/members/$uploaderId/videos/?mode=async&function=get_block" +
                    "&block_id=list_videos_uploaded_videos&sort_by=&from_videos=$page",
                headers,
            )
        } else {
            GET("$baseUrl/latest-updates/$page/", headers)
        }
    }

    override fun popularAnimeSelector() = "div.item.thumb"

    override fun popularAnimeFromElement(element: Element) = videoAnime(element)

    override fun popularAnimeNextPageSelector() = "div.item.pager.next a"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        Rule34VideoParser.pasted(query)?.let { return pastedPage(page, it) }
        if (isModelSearch(filters)) return searchModels(page, query, filters)
        val result = super.getSearchAnime(page, query, filters)
        if (page != 1) return result
        val slug = Rule34VideoParser.exactModelSlug(query) ?: return result
        val exact = findExactModel(slug) ?: return result
        if (result.animes.any { it.url.startsWith("/models/$slug") }) return result
        return AnimesPage(listOf(exact) + result.animes, result.hasNextPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (!isModelSearch(filters)) refreshTagDocument(filters)
        val (from, to) = Rule34VideoParser.durationBounds(filters.uriPart<DurationFilter>())
        val sort = Rule34VideoParser.sortByParam(filters.uriPart<OrderFilter>())
        val url = if (isModelSearch(filters)) {
            if (query.isBlank()) {
                Rule34VideoParser.modelBrowseUrl(baseUrl, page)
            } else {
                Rule34VideoParser.modelsJsonUrl(baseUrl, query, page)
            }
        } else {
            val tagId = (filters.find { it is TagSearch } as? TagSearch)?.toUriPart().orEmpty()
            Rule34VideoParser.videoSearchUrl(
                baseUrl = baseUrl,
                query = query,
                page = page,
                sortBy = sort,
                category = filters.uriPart<CategoryBy>(),
                tagId = tagId,
                durationFrom = from,
                durationTo = to,
            )
        }
        return GET(url, headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = if (Rule34VideoParser.isModelDocument(document)) {
        val details = Rule34VideoParser.parseModelDetails(document)
        SAnime.create().apply {
            title = details.name
            author = details.name
            thumbnail_url = details.avatar
            description = details.description
            status = SAnime.ONGOING
        }
    } else {
        val details = Rule34VideoParser.parseVideoDetails(document)
        SAnime.create().apply {
            title = details.title
            author = details.author
            description = details.description
            genre = details.genres
            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        if (!Rule34VideoParser.isModelEntry(anime.url)) {
            return listOf(
                SEpisode.create().apply {
                    url = anime.url
                    name = "Video"
                    episode_number = 1f
                },
            )
        }
        val collection = baseUrl + anime.url
        val cards = mutableListOf<Rule34VideoParser.VideoCard>()
        val seen = mutableSetOf<Long>()
        var pages = 1
        for (page in 1..Rule34VideoParser.MAX_MODEL_PAGES) {
            if (page > pages) break
            val document = client.newCall(GET(Rule34VideoParser.modelPageUrl(collection, page), headers))
                .execute()
                .asJsoup()
            if (page == 1) {
                pages = minOf(Rule34VideoParser.pageCount(document), Rule34VideoParser.MAX_MODEL_PAGES)
            }
            val fresh = Rule34VideoParser.parseVideoCards(document).filter { seen.add(it.videoId) }
            if (fresh.isEmpty()) break
            cards += fresh
        }
        return Rule34VideoParser.episodesFrom(cards, System.currentTimeMillis()).map { draft ->
            SEpisode.create().apply {
                url = draft.url
                name = draft.name
                episode_number = draft.episodeNumber
                date_upload = draft.dateUpload
                preview_url = draft.preview
            }
        }
    }

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()
        val links = Rule34VideoParser.parseDownloadLinks(document)
        if (links.isEmpty()) return emptyList()
        val playbackHeaders = playbackHeaders(pageUrl)
        val videos = links.mapNotNull { link ->
            val cdn = resolveCdn(link.fileUrl, pageUrl) ?: return@mapNotNull null
            Video(
                videoUrl = cdn,
                videoTitle = link.label,
                resolution = link.resolution,
                headers = playbackHeaders,
            )
        }
        if (videos.isEmpty()) {
            throw Exception("下载链接无法打开（站点拒绝了跳转）")
        }
        return videos.sortVideos()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val preferred = preferences.getString(PREF_QUALITY_KEY, Rule34VideoParser.QUALITY_BEST)
            ?: Rule34VideoParser.QUALITY_BEST
        val ranked = sortedByDescending { it.resolution ?: 0 }
        val want = preferred.substringBefore('p').toIntOrNull() ?: return ranked
        return ranked.sortedByDescending { it.resolution == want || it.videoTitle == preferred }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_UPLOADER_FILTER_ENABLED_KEY
            title = "Filter by Uploader"
            summary = "Load videos only from the specified uploader ID."
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_UPLOADER_ID_KEY
            title = "Uploader ID"
            summary = "Enter the ID of the uploader (e.g., 98965). Requires \"Filter by Uploader\" to be enabled."
            dialogTitle = "Enter Uploader ID"
            setOnPreferenceChangeListener { _, newValue ->
                newValue?.toString().isNullOrBlank().not()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = Rule34VideoParser.QUALITY_OPTIONS
            entryValues = Rule34VideoParser.QUALITY_OPTIONS
            setDefaultValue(Rule34VideoParser.QUALITY_BEST)
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

    private var tagDocument = Document("")

    private fun refreshTagDocument(filters: AnimeFilterList) {
        val tagFilter = (filters.find { it is TagFilter } as? TagFilter)?.state.orEmpty()
        val url = "$baseUrl/search_ajax.php?tag=${tagFilter.ifBlank { "." }}"
        client.newCall(GET(url, headers)).execute().use { response ->
            tagDocument = response.asJsoup()
        }
    }

    private fun tagsResults(document: Document): Array<Pair<String, String>> {
        val tagList = mutableListOf(Pair("<Select>", ""))
        tagList += document.select("div.item").mapNotNull { item ->
            val tagValue = item.selectFirst("input")?.attr("value") ?: return@mapNotNull null
            val tagName = item.selectFirst("label")?.text() ?: return@mapNotNull null
            Pair(tagName, tagValue)
        }
        return tagList.toTypedArray()
    }

    override fun getFilterList(): AnimeFilterList {
        val uploaderOn = preferences.getBoolean(PREF_UPLOADER_FILTER_ENABLED_KEY, false) &&
            preferences.getString(PREF_UPLOADER_ID_KEY, "").orEmpty().isNotBlank()
        if (uploaderOn) return AnimeFilterList()
        return AnimeFilterList(
            SearchTypeFilter(),
            OrderFilter(),
            DurationFilter(),
            CategoryBy(),
            AnimeFilter.Separator(),
            AnimeFilter.Header("Entered a \"tag\", click on \"filter\" then Click \"reset\" to load tags."),
            TagFilter(),
            TagSearch(tagsResults(tagDocument)),
        )
    }

    private fun pastedPage(page: Int, target: Rule34VideoParser.Pasted): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val anime = when (target) {
            is Rule34VideoParser.Pasted.Model -> {
                val document = client.newCall(GET("$baseUrl/models/${target.dir}/", headers)).execute().asJsoup()
                val details = Rule34VideoParser.parseModelDetails(document)
                modelAnime(target.dir, details.name, details.avatar, "", null, null)
            }
            is Rule34VideoParser.Pasted.Video -> {
                val document = client.newCall(GET(baseUrl + target.path, headers)).execute().asJsoup()
                val details = Rule34VideoParser.parseVideoDetails(document)
                SAnime.create().apply {
                    setUrlWithoutDomain(target.path)
                    title = details.title.ifBlank { target.path }
                    author = details.author
                    thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                    status = SAnime.COMPLETED
                }
            }
        }
        return AnimesPage(listOf(anime), false)
    }

    private fun searchModels(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val sort = Rule34VideoParser.sortByParam(filters.uriPart<OrderFilter>())
        val (from, to) = Rule34VideoParser.durationBounds(filters.uriPart<DurationFilter>())
        if (query.isBlank()) {
            val document = client.newCall(GET(Rule34VideoParser.modelBrowseUrl(baseUrl, page), headers)).execute().asJsoup()
            val animes = Rule34VideoParser.parseModelCards(document).map { card ->
                modelAnime(card.dir, card.name, card.thumbnail, sort, from, to)
            }
            return AnimesPage(animes, Rule34VideoParser.hasNextPage(document))
        }
        val body = client.newCall(GET(Rule34VideoParser.modelsJsonUrl(baseUrl, query, page), headers))
            .execute()
            .use { it.body.string() }
        val models = Rule34VideoParser.parseModelsJson(body)
        val avatars = fetchAvatars(models.map { it.dir })
        val animes = models.map { card ->
            modelAnime(card.dir, card.name, avatars[card.dir], sort, from, to)
        }
        return AnimesPage(animes, models.size >= Rule34VideoParser.MODELS_JSON_PAGE_SIZE)
    }

    private fun findExactModel(slug: String): SAnime? = runCatching {
        val body = client.newCall(GET(Rule34VideoParser.modelsJsonUrl(baseUrl, slug, 1), headers))
            .execute()
            .use { it.body.string() }
        val hit = Rule34VideoParser.parseModelsJson(body).firstOrNull { it.dir.equals(slug, ignoreCase = true) }
            ?: return null
        modelAnime(hit.dir, hit.name, fetchAvatar(hit.dir), "", null, null)
    }.getOrNull()

    private fun modelAnime(
        dir: String,
        name: String,
        thumbnail: String?,
        sortBy: String,
        durationFrom: String?,
        durationTo: String?,
    ): SAnime = SAnime.create().apply {
        val absolute = Rule34VideoParser.modelCollectionUrl(baseUrl, dir, sortBy, durationFrom, durationTo)
        setUrlWithoutDomain(absolute)
        title = name
        thumbnail_url = thumbnail
        description = "作者合集"
        author = name
        status = SAnime.ONGOING
    }

    private fun videoAnime(element: Element): SAnime = SAnime.create().apply {
        val card = Rule34VideoParser.parseVideoCards(element).firstOrNull()
        if (card != null) {
            setUrlWithoutDomain(card.path)
            title = card.title
            thumbnail_url = card.thumbnail
            return@apply
        }
        val anchor = element.selectFirst("a.th[href*=/video/]") ?: element.selectFirst("a.th")!!
        setUrlWithoutDomain(anchor.attr("href"))
        title = element.selectFirst(".thumb_title")?.text()?.trim().orEmpty().ifBlank { anchor.attr("title") }
        thumbnail_url = element.selectFirst("img")?.let { img ->
            listOf(img.attr("abs:data-rd-src"), img.attr("data-rd-src"), img.attr("abs:src"), img.attr("src"))
                .firstOrNull { it.startsWith("http") }
        }
    }

    private fun fetchAvatars(dirs: List<String>): Map<String, String> {
        val pending = dirs.distinct().filter { it.isNotBlank() }
        if (pending.isEmpty()) return emptyMap()
        val pool = Executors.newFixedThreadPool(minOf(4, pending.size))
        return try {
            pending.map { dir -> pool.submit<Pair<String, String?>> { dir to fetchAvatar(dir) } }
                .mapNotNull { future ->
                    val (dir, avatar) = future.get()
                    avatar?.let { dir to it }
                }
                .toMap()
        } finally {
            pool.shutdown()
        }
    }

    /** Author avatars sit near the top of the model page; stop once that tag is in hand. */
    private fun fetchAvatar(dir: String): String? {
        return client.newCall(GET("$baseUrl/models/$dir/", headers)).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val source = response.body.source()
            val window = Buffer()
            while (!source.exhausted() && window.size < AVATAR_SCAN_BYTES) {
                source.read(window, 8192)
                val avatar = Rule34VideoParser.avatarSrc(window.snapshot().utf8())
                if (avatar != null) return@use avatar
            }
            null
        }
    }

    private fun resolveCdn(fileUrl: String, pageUrl: String): String? {
        val requestUrl = Rule34VideoParser.directFileUrl(fileUrl)
        repeat(2) { attempt ->
            val response = noRedirectClient.newCall(
                Request.Builder()
                    .url(requestUrl)
                    .header("Referer", pageUrl)
                    .header("User-Agent", headers["User-Agent"].orEmpty())
                    .header("Accept", VIDEO_ACCEPT)
                    .build(),
            ).execute()
            response.use { resolved ->
                val location = resolved.header("Location")
                if (resolved.code in REDIRECTS && !location.isNullOrBlank()) {
                    return resolved.request.url.resolve(location)?.toString()
                }
                if (attempt == 1) {
                    Log.w(LOG_TAG, "no cdn redirect (HTTP ${resolved.code})")
                }
            }
        }
        return null
    }

    private fun playbackHeaders(pageUrl: String): Headers = Headers.Builder()
        .add("User-Agent", headers["User-Agent"].orEmpty())
        .add("Referer", pageUrl)
        .add("Accept", VIDEO_ACCEPT)
        .build()

    private fun isModelSearch(filters: AnimeFilterList): Boolean = filters.uriPart<SearchTypeFilter>() == "models"

    private inline fun <reified R> AnimeFilterList.uriPart(): String = (find { it is R } as? UriPartFilter)?.toUriPart().orEmpty()

    private class SearchTypeFilter :
        UriPartFilter(
            "Search type",
            arrayOf(
                Pair("Videos", "videos"),
                Pair("Models", "models"),
            ),
        )

    private class DurationFilter :
        UriPartFilter(
            "Duration",
            arrayOf(
                Pair("Any", ""),
                Pair("Under 1 min", "0-60"),
                Pair("1-5 min", "60-300"),
                Pair("5-20 min", "300-1200"),
                Pair("Over 20 min", "1200-"),
            ),
        )

    private class TagFilter : AnimeFilter.Text("Click \"reset\" without any text to load all A-Z tags.", "")

    private class TagSearch(results: Array<Pair<String, String>>) : UriPartFilter("Tag Filter ", results)

    private class CategoryBy :
        UriPartFilter(
            "Category Filter ",
            arrayOf(
                Pair("All", ""),
                Pair("Straight", "2109"),
                Pair("Futa", "15"),
                Pair("Gay", "192"),
                Pair("Music", "4747"),
                Pair("Iwara", "1821"),
            ),
        )

    private class OrderFilter :
        UriPartFilter(
            "Sort By ",
            arrayOf(
                Pair("Latest", "latest-updates"),
                Pair("Most Viewed", "most-popular"),
                Pair("Top Rated", "top-rated"),
                Pair("Longest", "longest"),
            ),
        )

    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    companion object {
        private const val LOG_TAG = "Rule34Video"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_UPLOADER_FILTER_ENABLED_KEY = "uploader_filter_enabled"
        private const val PREF_UPLOADER_ID_KEY = "uploader_id"
        private const val AVATAR_SCAN_BYTES = 96L * 1024
        private const val VIDEO_ACCEPT =
            "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5"
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
