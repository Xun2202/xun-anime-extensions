package eu.kanade.tachiyomi.animeextension.all.xnxxxun

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

internal data class XnxxItem(val path: String, val title: String, val thumbnail: String?, val author: String?)
internal data class XnxxSources(val hls: String?, val high: String?, val low: String?)
internal data class XnxxDetails(val title: String, val author: String?, val tags: String?, val description: String?, val thumbnail: String?)

internal object XnxxXunParser {
    // Both the current Best/Hit pages and the older search layout use thumb-block,
    // but their id and class names differ. The video link is the stable discriminator.
    const val LIST_SELECTOR = "div.thumb-block"
    const val NEXT_SELECTOR = "a.next[href]"

    fun item(element: Element): XnxxItem? {
        val link = element.selectFirst(".thumb-under a[href^=/video-]")
            ?: element.selectFirst("a.title[href^=/video-]")
            ?: element.selectFirst("a[href^=/video-]")
            ?: return null
        val path = canonicalPath(link.attr("href")) ?: return null
        val title = link.attr("title").ifBlank { link.text() }.trim()
        if (title.isBlank()) return null
        val image = element.selectFirst("img")
        return XnxxItem(
            path,
            title,
            image?.attr("data-src")?.takeIf(String::isNotBlank) ?: image?.attr("src")?.takeIf(String::isNotBlank),
            element.selectFirst(".uploader a, a.name")?.text()?.takeIf(String::isNotBlank),
        )
    }

    fun listing(document: Document): List<XnxxItem> = document.select(LIST_SELECTOR).mapNotNull(::item).distinctBy { it.path }

    fun details(document: Document): XnxxDetails {
        val title = document.selectFirst("#video-content-metadata .clear-infobar strong")?.text()?.takeIf(String::isNotBlank)
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf(String::isNotBlank)
            ?: error("网站详情格式已变化，请更新插件")
        val author = document.selectFirst("#video-content-metadata .clear-infobar span a")?.text()?.takeIf(String::isNotBlank)
        val tags = document.select("#video-content-metadata .video-tags a[href^=/search/]").map { it.text().trim() }.filter(String::isNotBlank)
            .takeIf { it.isNotEmpty() }?.joinToString()
        return XnxxDetails(
            title,
            author,
            tags,
            document.selectFirst("meta[name=description]")?.attr("content")?.takeIf(String::isNotBlank),
            document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank),
        )
    }

    fun canonicalPath(url: String): String? {
        val path = runCatching { java.net.URI(url).path }.getOrNull() ?: url.substringBefore('?').substringBefore('#')
        if (!path.startsWith("/video-")) return null
        return path
    }

    fun related(html: String): List<XnxxItem> {
        val raw = Regex("(?:var\\s+)?video_related\\s*=\\s*(\\[[\\s\\S]*?]);").find(html)?.groupValues?.get(1)
            ?: if (relatedMarkerPresent(html)) throw IllegalStateException("网站相关视频数据格式已变化，请更新插件") else return emptyList()
        val array = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrElse { throw IllegalStateException("网站相关视频数据格式已变化，请更新插件") }
        return array.mapNotNull { value ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val path = canonicalPath(obj.string("u") ?: return@mapNotNull null) ?: return@mapNotNull null
            val title = obj.string("tf") ?: obj.string("t") ?: return@mapNotNull null
            XnxxItem(path, Parser.unescapeEntities(title, false), obj.string("i"), obj.string("pn"))
        }.distinctBy { it.path }
    }

    fun relatedMarkerPresent(html: String): Boolean = Regex("(?:var\\s+)?video_related\\s*=").containsMatchIn(html)

    fun sources(html: String): XnxxSources = XnxxSources(
        extractPlayerUrl(html, "setVideoHLS"),
        extractPlayerUrl(html, "setVideoUrlHigh"),
        extractPlayerUrl(html, "setVideoUrlLow"),
    )

    private fun extractPlayerUrl(html: String, method: String): String? =
        Regex("html5player\\.$method\\(['\"](https?://[^'\"]+)['\"]\\)").find(html)?.groupValues?.get(1)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
}
