package eu.kanade.tachiyomi.animeextension.all.xvideosxun

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URISyntaxException

/**
 * Which field supplied a listing title. [PRIMARY] is the anchor `title` used by home / newest /
 * search; [BLOCK_TITLE] is the block `title` used by the redesigned Best of tiles. Both count as
 * the main title selector for the listing health check.
 */
internal enum class TitleSource {
    PRIMARY,
    BLOCK_TITLE,
    ANCHOR_TEXT,
    IMG_ALT,
    LINK_TITLE,
    SLUG,
}

internal data class ListingItem(
    val href: String,
    val title: String,
    val titleSource: TitleSource,
    val thumb: String?,
    val author: String?,
)

internal data class ListingPage(
    val items: List<ListingItem>,
    val hasNext: Boolean,
    val primaryTitleHits: Int,
)

internal data class ProfileRef(
    val name: String,
    val href: String,
)

internal data class VideoDetails(
    val title: String,
    val thumbnailUrl: String?,
    val uploaderName: String?,
    val uploaderHref: String?,
    val models: List<ProfileRef>,
    val genres: List<String>,
    val duration: String?,
    val hdMark: String?,
    val views: String?,
    val likes: String?,
    val dislikes: String?,
    val uploadDate: String?,
    val description: String?,
)

internal data class PlayerSources(
    val hls: String?,
    val high: String?,
    val low: String?,
)

@Serializable
internal data class VideoJsonLd(
    val name: String? = null,
    val description: String? = null,
    val thumbnailUrl: List<String>? = null,
    val uploadDate: String? = null,
    val duration: String? = null,
)

/** One object from the `video_related=[…]` script array. */
@Serializable
internal data class RelatedVideo(
    val eid: String,
    val deleted: Boolean = false,
    val u: String? = null,
    val i: String? = null,
    val tf: String? = null,
    val t: String? = null,
    val pn: String? = null,
)

/**
 * HTML / script parsing for XvXun. No Android or animesource types: unit tests run this on a JVM.
 */
internal object XvideosXunParser {
    const val LISTING_SELECTOR = "div.mozaique div.thumb-block, div#content div.thumb-block"
    const val NEXT_PAGE_SELECTOR = "a.next-page, .pagination a.next, .pagination li.next a"

    private const val BEST_NEXT_PAGE_SELECTOR = ".pagination a.current + a[href]"
    private const val AUTHOR_SELECTOR = "p.metadata span.name, .video-metadata a.name, .video-metadata span.name"
    private const val THUMB_LINK = "div.thumb a[href]"
    private const val TITLE_ANCHOR = "p.title a, div.title a"
    private const val PAGE_TITLE = "h2.page-title"
    private const val TITLE_AUTO = "#title-auto-tr"
    private const val OG_TITLE = "meta[property=og:title]"
    private const val OG_IMAGE = "meta[property=og:image]"
    private const val UPLOADER_LINK = "li.main-uploader a[href]"
    private const val MODEL_LINKS = "li.model a[href], li.pornstar a[href]"
    private const val NAME_SPAN = "span.name"
    private const val KEYWORD_LINK = "a.is-keyword"
    private const val DURATION = "h2.page-title span.duration"
    private const val HD_MARK = "h2.page-title span.video-hd-mark"
    private const val VIEWS = "#v-views strong"
    private const val LIKES = "span.rating-good-perc, span.rating-good-nbr"
    private const val DISLIKES = "span.rating-bad-perc, span.rating-bad-nbr"
    private const val JSON_LD = "script[type=application/ld+json]"

    private val playerHlsRegex = Regex("""html5player\.setVideoHLS\('([^']+)'\)""")
    private val playerHighRegex = Regex("""html5player\.setVideoUrlHigh\('([^']+)'\)""")
    private val playerLowRegex = Regex("""html5player\.setVideoUrlLow\('([^']+)'\)""")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parseListing(document: Document, baseUrl: String, accountSection: Boolean): ListingPage {
        val items = document.select(LISTING_SELECTOR)
            .filter { it.isVideoBlock() }
            .map { parseListingItem(it, baseUrl) }
        val primaryTitleHits = items.count { it.titleSource == TitleSource.PRIMARY || it.titleSource == TitleSource.BLOCK_TITLE }
        val hasNext = document.selectFirst(NEXT_PAGE_SELECTOR) != null ||
            document.selectFirst(BEST_NEXT_PAGE_SELECTOR) != null ||
            // Account fragments carry no pagination markup; assume more while pages are full.
            (accountSection && items.size >= 30)
        return ListingPage(items, hasNext, primaryTitleHits)
    }

    fun canonicalVideoPath(href: String): String? {
        val path = href.toHttpUrlOrNull()?.encodedPath ?: href.substringBefore('?').substringBefore('#')
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

    /** Video slug from [href], underscores turned into spaces. Never blank. */
    fun titleFromSlug(href: String): String {
        val path = href.toHttpUrlOrNull()?.encodedPath ?: href.substringBefore('?').substringBefore('#')
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        val slug = segments.lastOrNull { segment ->
            segment != "_" && !segment.all(Char::isDigit) && !segment.startsWith("video.")
        }
        val readable = slug?.trimEnd('.')?.replace('_', ' ')?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (readable.isNotBlank()) return readable
        return segments.firstOrNull { it.startsWith("video.") }
            ?.removePrefix("video.")
            ?.takeIf { it.isNotBlank() }
            ?: "Video"
    }

    fun parseVideoDetails(document: Document): VideoDetails {
        val ld = extractJsonLd(document)
        val title = ld?.name?.takeIf { it.isNotBlank() }
            // The title may be wrapped in an "automatically translated" span, so ownText() alone can be empty.
            ?: headingTitle(document)
            ?: document.selectFirst(OG_TITLE)?.attr("content")
            ?: ""
        val uploaderLink = document.selectFirst(UPLOADER_LINK)
        val modelElements = document.select(MODEL_LINKS)
        return VideoDetails(
            title = title,
            thumbnailUrl = ld?.thumbnailUrl?.firstOrNull()
                ?: document.selectFirst(OG_IMAGE)?.attr("content"),
            uploaderName = uploaderLink?.selectFirst(NAME_SPAN)?.text()?.trim(),
            uploaderHref = uploaderLink?.attr("href"),
            models = modelElements.mapNotNull { link ->
                val name = link.selectFirst(NAME_SPAN)?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                ProfileRef(name, link.attr("href"))
            },
            genres = document.select(KEYWORD_LINK).map { it.text().trim() }.filter { it.isNotBlank() },
            duration = document.selectFirst(DURATION)?.text(),
            hdMark = document.selectFirst(HD_MARK)?.text(),
            views = document.selectFirst(VIEWS)?.text(),
            likes = document.selectFirst(LIKES)?.text()?.replace(" ", ""),
            dislikes = document.selectFirst(DISLIKES)?.text()?.replace(" ", ""),
            uploadDate = ld?.uploadDate,
            description = ld?.description,
        )
    }

    private fun headingTitle(document: Document): String? {
        val h2 = document.selectFirst(PAGE_TITLE) ?: return null
        val translated = h2.selectFirst(TITLE_AUTO)?.text()
        val raw: String = translated ?: h2.ownText()
        return raw.trim().takeIf { it.isNotBlank() }
    }

    fun extractJsonLd(document: Document): VideoJsonLd? {
        for (script in document.select(JSON_LD)) {
            val text = script.data().trim()
            if (!text.contains("VideoObject")) continue
            runCatching { json.decodeFromString<VideoJsonLd>(text) }.getOrNull()?.let { return it }
        }
        return null
    }

    fun parsePlayerSources(html: String): PlayerSources = PlayerSources(
        hls = playerHlsRegex.find(html)?.groupValues?.get(1),
        high = playerHighRegex.find(html)?.groupValues?.get(1),
        low = playerLowRegex.find(html)?.groupValues?.get(1),
    )

    /**
     * The JSON array that follows `video_related=` in a script, or null when the marker is absent
     * or the brackets do not close. Decode failures are an empty list so a broken recommendation
     * block does not hide the video the user opened.
     */
    fun parseRelatedVideos(html: String): List<RelatedVideo> {
        val array = extractJsArray(html, "video_related=") ?: return emptyList()
        return runCatching { json.decodeFromString<List<RelatedVideo>>(array) }.getOrDefault(emptyList())
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

    fun parseListingItem(element: Element, baseUrl: String): ListingItem {
        val link = element.selectFirst(THUMB_LINK) ?: element.selectFirst("a[href]")!!
        val href = link.absUrl("href").ifBlank { baseUrl + link.attr("href") }
        val canonical = canonicalVideoPath(href)
        val (title, source) = listingTitle(element, link, canonical ?: href)
        val img = element.selectFirst("img")
        val thumb = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
        val author = element.selectFirst(AUTHOR_SELECTOR)?.text()?.trim()?.takeIf { it.isNotBlank() }
        return ListingItem(
            href = canonical ?: urlWithoutDomain(href),
            title = title,
            titleSource = source,
            thumb = thumb,
            author = author,
        )
    }

    /**
     * Home, /new and search still use `p.title a[title]` plus `p.metadata span.name`.
     * /best was redesigned to `div.title[title] > a` plus `.video-metadata a.name`; the thumb
     * link has no title attribute (and `img alt` is the generic "Video"), so the old
     * `p.title a` lookup stored a blank title until the details page filled it in.
     * Anchor text includes child nodes: a translated title can sit in a nested span, so
     * ownText() is not enough. Never returns a blank string.
     */
    private fun listingTitle(element: Element, link: Element, href: String): Pair<String, TitleSource> {
        val anchorTitle = listOf("p.title a", "div.title a").firstNotNullOfOrNull { selector ->
            element.selectFirst(selector)?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
        }
        if (anchorTitle != null) return finishTitle(anchorTitle, TitleSource.PRIMARY, href)
        val blockTitle = listOf("div.title", "p.title").firstNotNullOfOrNull { selector ->
            element.selectFirst(selector)?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
        }
        if (blockTitle != null) return finishTitle(blockTitle, TitleSource.BLOCK_TITLE, href)
        val anchorText = element.selectFirst(TITLE_ANCHOR)?.text()?.trim()?.takeIf { it.isNotBlank() }
        if (anchorText != null) return finishTitle(anchorText, TitleSource.ANCHOR_TEXT, href)
        val alt = element.select("img[alt]").asSequence()
            .map { it.attr("alt").trim() }
            .firstOrNull { it.isNotBlank() && !it.equals("Video", ignoreCase = true) }
        if (alt != null) return finishTitle(alt, TitleSource.IMG_ALT, href)
        val linkTitle = link.attr("title").trim().takeIf { it.isNotBlank() }
        if (linkTitle != null) return finishTitle(linkTitle, TitleSource.LINK_TITLE, href)
        return finishTitle(titleFromSlug(href), TitleSource.SLUG, href)
    }

    private fun finishTitle(raw: String, source: TitleSource, href: String): Pair<String, TitleSource> {
        val text = Parser.unescapeEntities(raw, false).trim()
        if (text.isNotBlank()) return text to source
        val slug = titleFromSlug(href)
        return slug to TitleSource.SLUG
    }

    /** Only keep tiles that point at a video (home page also carries channel/ad tiles). */
    private fun Element.isVideoBlock(): Boolean {
        val href = selectFirst("a[href]")?.attr("href") ?: return false
        return href.contains("/video") || href.contains("/prof-video-click")
    }

    /** Same path/query/fragment split as [eu.kanade.tachiyomi.animesource.online.AnimeHttpSource.setUrlWithoutDomain]. */
    private fun urlWithoutDomain(orig: String): String = try {
        val uri = URI(orig)
        var out = uri.path
        if (uri.query != null) {
            out += "?" + uri.query
        }
        if (uri.fragment != null) {
            out += "#" + uri.fragment
        }
        out ?: orig
    } catch (_: URISyntaxException) {
        orig
    }
}
