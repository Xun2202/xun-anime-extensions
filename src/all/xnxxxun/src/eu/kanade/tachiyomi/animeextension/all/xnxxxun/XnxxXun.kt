package eu.kanade.tachiyomi.animeextension.all.xnxxxun

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Independent source because the stock Xnxx extension's card selector no longer matches the site.
 * Source structure and the player method names derive from yuzono/anime-extensions' Xnxx
 * (Apache-2.0); see the repository LICENSE.
 */
class XnxxXun : ParsedAnimeHttpLegacySource(), ConfigurableAnimeSource {
    override val name = "XNXX Xun"
    override val baseUrl = "https://www.xnxx.com"
    override val lang = "all"

    // The site's /new path currently returns 404 and its home page only sends category placeholders.
    // Exposing a "latest" tab backed by /hits would mislabel popular videos as new uploads.
    override val supportsLatest = false

    private val preferences by getPreferencesLazy()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "en-US,en;q=0.9")

    override fun popularAnimeRequest(page: Int): Request {
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Calendar.getInstance().time)
        return GET("$baseUrl/best/$month/${page - 1}", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parsePage(response, true)
    override fun popularAnimeSelector() = XnxxXunParser.LIST_SELECTOR
    override fun popularAnimeFromElement(element: Element): SAnime = anime(XnxxXunParser.item(element) ?: error("视频卡片格式已变化，请更新插件"))
    override fun popularAnimeNextPageSelector() = XnxxXunParser.NEXT_SELECTOR

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        if (query.isBlank()) popularAnimeRequest(page) else {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            GET("$baseUrl/search/$encoded/${page - 1}", headers)
        }

    override fun searchAnimeParse(response: Response): AnimesPage = parsePage(response, response.request.url.encodedPath.startsWith("/best/"))
    override fun searchAnimeSelector() = XnxxXunParser.LIST_SELECTOR
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = XnxxXunParser.NEXT_SELECTOR

    private fun parsePage(response: Response, required: Boolean): AnimesPage {
        val document = response.asJsoup()
        val items = XnxxXunParser.listing(document)
        if (required && items.isEmpty() && response.request.url.pathSegments.lastOrNull() == "0") {
            error("网站视频卡片格式已变化，请更新插件")
        }
        val hasNext = document.select(XnxxXunParser.NEXT_SELECTOR).any { it.attr("href").isNotBlank() }
        return AnimesPage(items.map(::anime), hasNext)
    }

    private fun anime(item: XnxxItem): SAnime = SAnime.create().apply {
        url = item.path
        title = item.title
        thumbnail_url = item.thumbnail
        author = item.author
    }

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val details = XnxxXunParser.details(document)
        title = details.title
        author = details.author
        genre = details.tags
        description = details.description
        thumbnail_url = details.thumbnail
        status = SAnime.COMPLETED
    }

    override fun episodeListParse(response: Response): List<SEpisode> = listOf(SEpisode.create().apply {
        name = "Video"
        episode_number = 1F
        setUrlWithoutDomain(response.request.url.toString())
    })
    override fun episodeListSelector(): String = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val html = response.body.string()
        if (!XnxxXunParser.relatedMarkerPresent(html)) return emptyList()
        return XnxxXunParser.related(html).map(::anime)
    }

    override fun videoListParse(response: Response): List<Video> {
        val sources = XnxxXunParser.sources(response.body.string())
        val videos = mutableListOf<Video>()
        sources.hls?.let { hls ->
            runCatching {
                playlistUtils.extractFromHls(hls, "$baseUrl/", videoNameGen = { quality -> "HLS $quality" })
            }.getOrNull()?.let(videos::addAll)
            if (videos.isEmpty()) videos += Video(hls, "HLS (auto)", hls, headers = headers)
        }
        sources.high?.let { videos += Video(it, "MP4 High", it, headers = headers) }
        sources.low?.takeIf { it != sources.high }?.let { videos += Video(it, "MP4 Low", it, headers = headers) }
        if (videos.isEmpty()) error("未找到视频地址，网站播放脚本可能已变化")
        return videos
    }
    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val preferred = preferences.getString("preferred_quality", "HLS") ?: "HLS"
        return sortedByDescending { it.videoTitle.startsWith(preferred) }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "首选视频源"
            entries = arrayOf("HLS", "MP4 High", "MP4 Low")
            entryValues = entries
            setDefaultValue("HLS")
            summary = "%s"
        })
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()
}
