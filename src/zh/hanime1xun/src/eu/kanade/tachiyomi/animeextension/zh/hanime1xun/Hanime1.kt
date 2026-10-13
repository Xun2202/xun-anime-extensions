// Modified from yuzono/anime-extensions src/zh/hanime1 v7 (Apache-2.0).
// https://github.com/yuzono/anime-extensions/tree/master/src/zh/hanime1

package eu.kanade.tachiyomi.animeextension.zh.hanime1xun

import android.util.Log
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
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

enum class FilterUpdateState {
    NONE,
    UPDATING,
    COMPLETED,
    FAILED,
}

class Hanime1 :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {
    override val baseUrl = "https://hanime1.me"

    // name / lang / versionId stay on the original values so the generated source id matches Hanime1 v7.
    override val lang = Hanime1SourceId.LANG
    override val name = Hanime1SourceId.NAME
    override val supportsLatest: Boolean
        get() = true

    override val client = network.client.newBuilder()
        .addInterceptor(::checkFiltersInterceptor)
        .build()

    private val preferences by getPreferencesLazy()

    private var filterUpdateState = FilterUpdateState.NONE

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        if (!isAuthorCollection(anime.url)) {
            return super.getAnimeDetails(anime)
        }
        val name = authorQuery(anime.url).orEmpty()
        val episodes = loadAuthorEpisodes(anime.url)
        return SAnime.create().apply {
            url = anime.url
            title = anime.title.ifBlank { name.hanime1ListingTitle() }
            author = name
            thumbnail_url = anime.thumbnail_url
            description = authorCollectionDescription(episodes.size)
            initialized = true
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        if (!isAuthorCollection(anime.url)) {
            return super.getEpisodeList(anime)
        }
        return loadAuthorEpisodes(anime.url).map { it.toSEpisode() }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val requestUrl = response.request.url.toString()
        val doc = response.useAsJsoup()
        if (isAuthorCollection(requestUrl)) {
            val details = doc.parseAuthorCollectionDetails(requestUrl)
            return SAnime.create().apply {
                setUrlWithoutDomain(requestUrl)
                title = details.name.hanime1ListingTitle()
                author = details.name
                description = details.description
            }
        }
        return SAnime.create().apply {
            genre = doc.select(".single-video-tag").not("[data-toggle]").eachText()
                // Convert `# 博麗靈夢`, `1080p (1)` to `博麗靈夢`, `1080p`
                .joinToString { it.removePrefix("# ").replace(REGEX_VIDEO_TAG_SUFFIX, "") }
            author = doc.select("#video-artist-name").text()
            val realTitle = doc.select("#shareBtn-title").text().takeIf { it.isNotBlank() }
                ?: doc.select("meta[property=og:title]").attr("content").takeIf { it.isNotBlank() }
            // Leave the title empty when unavailable so the details page falls back to the title from the list page.
            title = realTitle?.appendInvisibleChar() ?: ""
            description = doc.select("meta[property=og:description]").attr("content")
            thumbnail_url = doc.select("meta[property=og:image]").attr("content")
            val type = doc.select("a#video-artist-name + a").text().trim()
            if ((type == "裏番" || type == "泡麵番") && realTitle != null) {
                // Use the series cover image for bangumi entries instead of the episode image.
                runBlocking {
                    try {
                        val animesPage =
                            getSearchAnime(
                                1,
                                realTitle,
                                AnimeFilterList(GenreFilter(arrayOf("", type)).apply { state = 1 }),
                            )
                        thumbnail_url = animesPage.animes.firstOrNull()?.thumbnail_url
                    } catch (e: Exception) {
                        Log.e(name, "Failed to get bangumi cover image", e)
                    }
                }
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val requestUrl = response.request.url.toString()
        val document = response.useAsJsoup()
        val parsed = if (isAuthorCollection(requestUrl)) {
            episodesFromListingPages(listOf(document.parseListing()))
        } else {
            document.parseWatchEpisodes(requestUrl)
        }
        return parsed.map { it.toSEpisode() }
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.useAsJsoup()
        val sourceList = doc.select("video source")
        val preferQuality = preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)
        return sourceList.map {
            val quality = it.attr("size")
            val url = it.attr("src")
            Video(url, "${quality}P", videoUrl = url)
        }.sortedByDescending { preferQuality == it.videoTitle }
            .ifEmpty {
                // Try to find the source from the script content.
                val videoUrl = doc.select("script:containsData(source)").first()!!.data()
                    .substringAfter("source = '").substringBefore("'")
                listOf(Video(videoUrl, "Raw", videoUrl = videoUrl))
            }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList())

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun popularAnimeRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList(HotFilter))

    /**
     * On the details page, the episode title is calculated as `SEpisode.name.removePrefix(SAnime.title)`.
     * If [SEpisode.name] is identical to [SAnime.title], the episode title becomes empty. To avoid this issue,
     * we use this method to append an invisible character to [SAnime.title].
     */
    private fun String.appendInvisibleChar(): String = "${this}\u200B"

    override fun searchAnimeParse(response: Response): AnimesPage {
        return response.useAsJsoup().parseListing().toAnimesPage()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET(hanime1SearchUrl(baseUrl, page, query, filters))
    }

    private fun checkFiltersInterceptor(chain: Interceptor.Chain): Response {
        if (filterUpdateState == FilterUpdateState.NONE) {
            updateFilters()
        }
        return chain.proceed(chain.request())
    }

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    private fun updateFilters() {
        filterUpdateState = FilterUpdateState.UPDATING
        val exceptionHandler =
            CoroutineExceptionHandler { _, _ -> filterUpdateState = FilterUpdateState.FAILED }
        scope.launch(exceptionHandler) {
            val jsoup = client.newCall(GET("$baseUrl/search")).awaitSuccess().useAsJsoup()
            val genreList = jsoup.select("div.genre-option div.hentai-sort-options").eachText()
            val sortList =
                jsoup.select("div.hentai-sort-options-wrapper div.hentai-sort-options").eachText()
            val yearList = jsoup.select("select#date-year option").eachAttr("value")
                .map { it.ifEmpty { "全部年份" } }
            val monthList = jsoup.select("select#date-month option").eachAttr("value")
                .map { it.ifEmpty { "全部月份" } }
            val categoryDict = mutableMapOf<String, MutableList<String>>()
            var currentKey = ""
            jsoup.select("div#tags div.modal-body").first()?.children()?.forEach {
                if (it.tagName() == "h5") {
                    currentKey = it.text()
                }
                if (it.tagName() == "label") {
                    if (currentKey in categoryDict) {
                        categoryDict[currentKey]
                    } else {
                        categoryDict[currentKey] = mutableListOf()
                        categoryDict[currentKey]
                    }!!.add(it.select("input[name]").attr("value"))
                }
            }
            preferences.edit().putString(PREF_KEY_GENRE_LIST, genreList.joinToString())
                .putString(PREF_KEY_SORT_LIST, sortList.joinToString())
                .putString(PREF_KEY_YEAR_LIST, yearList.joinToString())
                .putString(PREF_KEY_MONTH_LIST, monthList.joinToString())
                .putString(PREF_KEY_CATEGORY_LIST, categoryDict.toJsonString()).apply()
            filterUpdateState = FilterUpdateState.COMPLETED
        }
    }

    private fun <T : AnimeFilter.Select<*>> createFilter(
        prefKey: String,
        block: (Array<String>) -> T,
    ): T {
        val savedOptions = preferences.getString(prefKey, "")
        if (savedOptions.isNullOrEmpty()) {
            return block(emptyArray())
        }
        return block(savedOptions.split(", ").toTypedArray())
    }

    private fun createCategoryFilters(): List<AnimeFilter<out Any>> {
        val result = mutableListOf<AnimeFilter<out Any>>(
            BroadMatchFilter(),
        )
        val savedCategories = preferences.getString(PREF_KEY_CATEGORY_LIST, "")
        if (savedCategories.isNullOrEmpty()) {
            return result
        }
        savedCategories.parseAs<Map<String, List<String>>>().forEach {
            result.add(CategoryFilter(it.key, it.value.map { value -> TagFilter("tags[]", value) }))
        }
        return result
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        ArtistSearchFilter(),
        createFilter(PREF_KEY_GENRE_LIST) { GenreFilter(it) },
        createFilter(PREF_KEY_SORT_LIST) { SortFilter(it) },
        DateFilter(
            createFilter(PREF_KEY_YEAR_LIST) { YearFilter(it) },
            createFilter(PREF_KEY_MONTH_LIST) { MonthFilter(it) },
        ),
        TagsFilter(createCategoryFilters()),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.apply {
            addPreference(
                ListPreference(context).apply {
                    key = PREF_KEY_VIDEO_QUALITY
                    title = "設置首選畫質"
                    entries = arrayOf("1080P", "720P", "480P")
                    entryValues = entries
                    setDefaultValue(DEFAULT_QUALITY)
                    summary =
                        "當前選擇：${preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)}"
                    setOnPreferenceChangeListener { _, newValue ->
                        summary = "當前選擇：${newValue as String}"
                        true
                    }
                },
            )
            addPreference(
                ListPreference(context).apply {
                    key = PREF_KEY_LANG
                    title = "設置首選語言"
                    summary = "該設置僅影響影片字幕"
                    entries = arrayOf("繁體中文", "簡體中文")
                    entryValues = arrayOf("zh-CHT", "zh-CHS")
                    setOnPreferenceChangeListener { _, newValue ->
                        val baseHttpUrl = baseUrl.toHttpUrl()
                        client.cookieJar.saveFromResponse(
                            baseHttpUrl,
                            listOf(
                                Cookie.parse(
                                    baseHttpUrl,
                                    "user_lang=${newValue as String}",
                                )!!,
                            ),
                        )
                        true
                    }
                },
            )
        }
    }

    companion object {
        const val PREF_KEY_VIDEO_QUALITY = "PREF_KEY_VIDEO_QUALITY"
        const val PREF_KEY_LANG = "PREF_KEY_LANG"

        const val PREF_KEY_GENRE_LIST = "PREF_KEY_GENRE_LIST"
        const val PREF_KEY_SORT_LIST = "PREF_KEY_SORT_LIST"
        const val PREF_KEY_YEAR_LIST = "PREF_KEY_YEAR_LIST"
        const val PREF_KEY_MONTH_LIST = "PREF_KEY_MONTH_LIST"
        const val PREF_KEY_CATEGORY_LIST = "PREF_KEY_CATEGORY_LIST"

        const val DEFAULT_QUALITY = "1080P"

        private val REGEX_VIDEO_TAG_SUFFIX = Regex(""" \(\d+\)$""")
    }

    private suspend fun loadAuthorEpisodes(animeUrl: String): List<ParsedEpisode> {
        val query = authorQuery(animeUrl) ?: return emptyList()
        return authorEpisodesFrom { page ->
            client.newCall(searchAnimeRequest(page, query, AnimeFilterList()))
                .awaitSuccess()
                .useAsJsoup()
        }
    }

    private fun ListingPage.toAnimesPage(): AnimesPage {
        val animes = items.map { item ->
            SAnime.create().apply {
                setUrlWithoutDomain("$baseUrl${item.url}")
                title = item.title
                thumbnail_url = item.thumbnail
                author = item.author
                description = item.description
            }
        }
        return AnimesPage(animes, hasNextPage)
    }

    private fun ParsedEpisode.toSEpisode(): SEpisode {
        val parsed = this
        return SEpisode.create().apply {
            setUrlWithoutDomain("$baseUrl${parsed.url}")
            name = parsed.name
            episode_number = parsed.episodeNumber
            date_upload = parsed.dateUpload
        }
    }
}
