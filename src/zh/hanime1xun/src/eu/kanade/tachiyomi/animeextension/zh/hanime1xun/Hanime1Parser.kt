// Modified from yuzono/anime-extensions src/zh/hanime1 v7 (Apache-2.0).
// https://github.com/yuzono/anime-extensions/tree/master/src/zh/hanime1

package eu.kanade.tachiyomi.animeextension.zh.hanime1xun

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

internal const val INVISIBLE_TITLE_SUFFIX = "\u200B"

internal const val MAX_AUTHOR_PAGES = 30

internal val HANIME1_SERIES_TYPES = setOf("裏番", "泡麵番")

internal fun String.hanime1ListingTitle(): String = this + INVISIBLE_TITLE_SUFFIX

internal fun authorCollectionDescription(count: Int): String = "作者合集：$count 部影片"

/**
 * Same id as yuzono Hanime1 v7. Anikku's [eu.kanade.tachiyomi.animesource.online.AnimeHttpSource]
 * defaults `versionId` to 1 and sets `id` to the top 64 bits of
 * `MD5("${name.lowercase()}/$lang/$versionId")` with the sign bit cleared.
 * [Hanime1] must not override `id` or `versionId`.
 */
internal object Hanime1SourceId {
    const val NAME = "Hanime1.me"
    const val LANG = "zh"
    const val VERSION_ID = 1

    fun generate(name: String = NAME, lang: String = LANG, versionId: Int = VERSION_ID): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }
            .reduce(Long::or) and Long.MAX_VALUE
    }
}

internal enum class ListingKind { VIDEO, AUTHOR }

internal data class ListingItem(
    val url: String,
    val title: String,
    val thumbnail: String?,
    val author: String?,
    val kind: ListingKind,
    val description: String? = null,
)

internal data class ListingPage(
    val items: List<ListingItem>,
    val hasNextPage: Boolean,
)

internal data class ParsedEpisode(
    val url: String,
    val name: String,
    val episodeNumber: Float,
    val dateUpload: Long,
)

internal data class AuthorDetails(
    val name: String,
    val description: String,
    val videoCount: Int,
)

internal fun Document.parseListing(): ListingPage {
    val seen = linkedSetOf<String>()
    val items = mutableListOf<ListingItem>()
    select(".search-artist-card, .horizontal-row .video-item-container, a > .search-videos").forEach { element ->
        val item = when {
            element.hasClass("search-artist-card") -> element.toAuthorItem()
            element.hasClass("video-item-container") -> element.toHorizontalItem()
            else -> element.toVerticalItem()
        } ?: return@forEach
        if (seen.add(item.url)) {
            items += item
        }
    }
    return ListingPage(items, hasNextListingPage())
}

internal fun Document.hasNextListingPage(): Boolean {
    if (select("li.page-item a.page-link[rel=next]").isNotEmpty()) {
        return true
    }
    return select("a.skip-page-button").any { link ->
        link.text().trim() == "下一頁" && link.attr("href").let { it.isNotBlank() && it != "#" }
    }
}

internal fun Document.parseAuthorCollectionDetails(requestUrl: String): AuthorDetails {
    val videos = parseListing().items.filter { it.kind == ListingKind.VIDEO }
    val name = authorQuery(requestUrl).orEmpty()
    return AuthorDetails(
        name = name,
        description = authorCollectionDescription(videos.size),
        videoCount = videos.size,
    )
}

internal fun Document.parseWatchEpisodes(pageUrl: String): List<ParsedEpisode> {
    val type = select("a#video-artist-name + a").text().trim()
    if (type.isNotEmpty() && type !in HANIME1_SERIES_TYPES) {
        return listOf(singleEpisode(pageUrl))
    }
    return playlistEpisodes(pageUrl)
}

internal suspend fun authorEpisodesFrom(fetch: suspend (page: Int) -> Document): List<ParsedEpisode> {
    val pages = mutableListOf<ListingPage>()
    for (page in 1..MAX_AUTHOR_PAGES) {
        val listing = fetch(page).parseListing()
        pages += listing
        if (!listing.hasNextPage) break
    }
    return episodesFromListingPages(pages)
}

internal fun episodesFromListingPages(pages: List<ListingPage>): List<ParsedEpisode> {
    val items = mutableListOf<ListingItem>()
    val seen = linkedSetOf<String>()
    for (listing in pages) {
        for (item in listing.items) {
            if (item.kind != ListingKind.VIDEO) continue
            if (seen.add(item.url)) items += item
        }
    }
    return items.mapIndexed { index, item ->
        ParsedEpisode(
            url = item.url,
            name = item.title.removeSuffix(INVISIBLE_TITLE_SUFFIX),
            episodeNumber = (items.size - index).toFloat(),
            dateUpload = 0L,
        )
    }
}

internal fun isAuthorCollection(url: String): Boolean {
    val httpUrl = toHanimeUrl(url) ?: return false
    return httpUrl.encodedPath == "/search" && !httpUrl.queryParameter("query").isNullOrBlank()
}

internal fun authorQuery(url: String): String? = toHanimeUrl(url)?.queryParameter("query")?.takeIf { it.isNotBlank() }

internal fun authorCollectionUrl(name: String): String {
    val built = "https://hanime1.me".toHttpUrl().newBuilder()
        .addPathSegment("search")
        .addQueryParameter("query", name)
        .build()
    val query = built.encodedQuery
    return if (query.isNullOrEmpty()) built.encodedPath else built.encodedPath + "?" + query
}

internal fun hanime1SearchUrl(
    baseUrl: String,
    page: Int,
    query: String,
    filters: AnimeFilterList,
): String {
    val searchUrl = baseUrl.toHttpUrl().newBuilder().addPathSegment("search")
    if (query.isNotEmpty()) {
        searchUrl.addQueryParameter("query", query)
    }
    val timeFilters = mutableListOf<TimeFilter>()
    filters.list.flatMap {
        when (it) {
            is TagsFilter -> {
                it.state.flatMap { inner ->
                    if (inner is CategoryFilter) {
                        inner.state
                    } else {
                        listOf(inner)
                    }
                }
            }

            is AnimeFilter.Group<*> -> it.state
            else -> listOf(it)
        }
    }.forEach {
        when (it) {
            is ArtistSearchFilter -> {
                if (it.state) {
                    searchUrl.addQueryParameter("type", "artist")
                }
            }

            is QueryFilter -> {
                if (it.selected.isNotEmpty()) {
                    searchUrl.addQueryParameter(it.key, it.selected)
                }
            }

            is BroadMatchFilter -> {
                if (it.state) {
                    searchUrl.addQueryParameter(it.key, "on")
                }
            }

            is TagFilter -> {
                if (it.state) {
                    searchUrl.addQueryParameter(it.key, it.name)
                }
            }

            is TimeFilter -> {
                timeFilters.add(it)
            }

            else -> {}
        }
    }
    if (timeFilters.isNotEmpty()) {
        timeFilters.sort()
        timeFilters.joinToString(separator = "") { it.selected }.let {
            searchUrl.addEncodedQueryParameter("date", it)
        }
    }
    if (page > 1) {
        searchUrl.addQueryParameter("page", "$page")
    }
    return searchUrl.build().toString()
}

internal fun urlWithoutDomain(orig: String): String {
    return try {
        val uri = URI(orig)
        val path = uri.path ?: return orig
        buildString {
            append(path)
            if (uri.query != null) {
                append('?')
                append(uri.query)
            }
            if (uri.fragment != null) {
                append('#')
                append(uri.fragment)
            }
        }
    } catch (_: URISyntaxException) {
        orig
    }
}

internal fun isSiteWatchUrl(href: String): Boolean {
    val trimmed = href.trim()
    if (trimmed.isEmpty() || trimmed == "#") return false
    return try {
        val uri = URI(trimmed)
        val path = uri.path ?: return false
        if (path != "/watch" && !path.endsWith("/watch")) return false
        val host = uri.host
        host == null || host.equals("hanime1.me", ignoreCase = true) ||
            host.endsWith(".hanime1.me", ignoreCase = true)
    } catch (_: URISyntaxException) {
        false
    }
}

internal fun parseUploadDate(text: String?): Long {
    val value = UPLOAD_DATE.find(text ?: return 0L)?.value
    return SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).tryParse(value)
}

private fun Element.toHorizontalItem(): ListingItem? {
    val link = selectFirst("a.video-link") ?: return null
    if (link.attr("target").equals("_blank", ignoreCase = true)) return null
    val href = link.attr("href")
    if (!isSiteWatchUrl(href)) return null
    val thumb = selectFirst(".main-thumb")?.let { image ->
        image.attr("abs:src").ifBlank { image.attr("src") }.ifBlank { null }
    }
    val author = select(".subtitle").text().split("•").getOrNull(0)?.trim()?.ifBlank { null }
    return ListingItem(
        url = urlWithoutDomain(href),
        title = select(".title").text().hanime1ListingTitle(),
        thumbnail = thumb,
        author = author,
        kind = ListingKind.VIDEO,
    )
}

private fun Element.toVerticalItem(): ListingItem? {
    val parent = parent() ?: return null
    if (!parent.tagName().equals("a", ignoreCase = true)) return null
    if (parent.attr("target").equals("_blank", ignoreCase = true)) return null
    val href = parent.attr("href")
    if (!isSiteWatchUrl(href)) return null
    return ListingItem(
        url = urlWithoutDomain(href),
        title = select(".home-rows-videos-title").text().hanime1ListingTitle(),
        thumbnail = selectFirst("img")?.attr("src")?.ifBlank { null },
        author = null,
        kind = ListingKind.VIDEO,
    )
}

private fun Element.toAuthorItem(): ListingItem? {
    val title = select(".search-artist-title").text().trim().ifBlank {
        authorQuery(selectFirst("a.overlay")?.attr("href").orEmpty()).orEmpty()
    }
    if (title.isBlank()) return null
    val images = select("img")
    val avatar = images.firstOrNull { image ->
        image.attr("src").contains("/avatar/") || image.attr("abs:src").contains("/avatar/")
    } ?: images.getOrNull(1) ?: images.firstOrNull()
    val count = FILM_COUNT.find(select(".search-artist-count").text())?.groupValues?.get(1)?.toIntOrNull()
    return ListingItem(
        url = authorCollectionUrl(title),
        title = title.hanime1ListingTitle(),
        thumbnail = avatar?.let { image ->
            image.attr("abs:src").ifBlank { image.attr("src") }.ifBlank { null }
        },
        author = title,
        kind = ListingKind.AUTHOR,
        description = count?.let { authorCollectionDescription(it) },
    )
}

private fun Document.singleEpisode(pageUrl: String): ParsedEpisode {
    val title = select("#shareBtn-title").text().trim().ifBlank {
        select("meta[property=og:title]").attr("content").trim()
    }
    return ParsedEpisode(
        url = urlWithoutDomain(pageUrl),
        name = title,
        episodeNumber = 1f,
        dateUpload = uploadDateMillis(),
    )
}

private fun Document.playlistEpisodes(pageUrl: String): List<ParsedEpisode> {
    val page = pageUrl.toHttpUrl()
    val nodes = select("#playlist-scroll").first()!!.select(">div")
    return nodes.mapIndexed { index, element ->
        val href = element.select(".thumb-container a").attr("href")
        val resolved = runCatching { page.resolve(href) }.getOrNull()
        ParsedEpisode(
            url = urlWithoutDomain(href.ifBlank { resolved?.toString() ?: pageUrl }),
            name = element.select(".video-title").text(),
            episodeNumber = (nodes.size - index).toFloat(),
            dateUpload = if (resolved == page) uploadDateMillis() else 0L,
        )
    }
}

private fun Document.uploadDateMillis(): Long = parseUploadDate(select("#shareBtn-title + div").text())

private fun toHanimeUrl(url: String): HttpUrl? {
    val absolute = when {
        url.startsWith("https://") || url.startsWith("http://") -> url
        url.startsWith("/") -> "https://hanime1.me$url"
        url.isBlank() -> return null
        else -> "https://hanime1.me/$url"
    }
    return absolute.toHttpUrlOrNull()
}

private val UPLOAD_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
private val FILM_COUNT = Regex("""(\d+)\s*部影片""")
