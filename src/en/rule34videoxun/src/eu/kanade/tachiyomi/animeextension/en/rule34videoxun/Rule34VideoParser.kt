/*
 * Copyright the original authors of yuzono/anime-extensions.
 * Licensed under the Apache License, Version 2.0.
 * 改自 yuzono/anime-extensions src/en/rule34video v10，已修改。
 */
package eu.kanade.tachiyomi.animeextension.en.rule34videoxun

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * HTML / JSON parsing for rule34video.com. No Android imports, so unit tests can run it directly.
 *
 * Author search is `GET /models_json.php?q=&page=` (the filter panel's artist endpoint).
 * An author page is `/models/<slug>/` and paginates with `?from=`. Download links are
 * `/get_file/...mp4/?v-acctoken=...`; `download` and `download_filename` are stripped before
 * the player follows the 302, because those flags make the CDN answer with an attachment.
 */
object Rule34VideoParser {
    const val SOURCE_NAME = "Rule34Video"
    const val SOURCE_LANG = "en"

    /** Library default. Do not override [eu.kanade.tachiyomi.animesource.online.AnimeHttpSource.versionId]. */
    const val SOURCE_VERSION_ID = 1

    /** MD5("rule34video/en/1") first 8 bytes, sign bit cleared. Same id as yuzono rule34video v10. */
    const val SOURCE_ID = 9212790518101043860L

    const val QUALITY_BEST = "最高可用"
    val QUALITY_OPTIONS = arrayOf(QUALITY_BEST, "2160p", "1440p", "1080p", "720p", "480p", "360p")

    const val MAX_MODEL_PAGES = 40
    const val MODELS_JSON_PAGE_SIZE = 30
    const val SORT_NEWEST = "post_date"

    private val json = Json { ignoreUnknownKeys = true }
    private val videoHref = Regex("""/video/(\d+)/""")
    private val resolutionLabel = Regex("""(\d{3,4})\s*p""", RegexOption.IGNORE_CASE)
    private val isoDate = Regex(""""uploadDate"\s*:\s*"(\d{4}-\d{2}-\d{2})"""")
    private val videoModels = Regex("""video_models:\s*'([^']*)'""")

    fun generateSourceId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    fun exactModelSlug(query: String): String? {
        val slug = query.trim()
            .lowercase(Locale.US)
            .replace(Regex("[\\s_]+"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
        if (slug.isEmpty() || !slug.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) return null
        return slug
    }

    fun sortByParam(orderKey: String): String = when (orderKey) {
        "latest-updates", SORT_NEWEST -> SORT_NEWEST
        "most-popular", "video_viewed" -> "video_viewed"
        "top-rated", "rating" -> "rating"
        "longest", "duration" -> "duration"
        "random", "pseudo_rand" -> "pseudo_rand"
        else -> ""
    }

    /** "60-300" -> 60 to 300, "1200-" -> 1200 with no upper bound, "" -> none. */
    fun durationBounds(token: String): Pair<String?, String?> {
        if (token.isBlank() || token == "-") return null to null
        val parts = token.split('-', limit = 2)
        val from = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
        val to = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        return from to to
    }

    fun videoSearchUrl(
        baseUrl: String,
        query: String,
        page: Int,
        sortBy: String,
        category: String,
        tagId: String,
        durationFrom: String?,
        durationTo: String?,
    ): String {
        val builder = baseUrl.toHttpUrl().newBuilder()
        builder.addPathSegment("search")
        val slug = query.trim().replace(Regex("\\s+"), "-")
        if (slug.isNotEmpty()) builder.addPathSegment(slug)
        builder.addPathSegment("")
        if (category.isNotBlank()) builder.setQueryParameter("flag1", category)
        if (sortBy.isNotBlank()) builder.setQueryParameter("sort_by", sortBy)
        builder.setQueryParameter("from_videos", page.toString())
        if (tagId.isNotBlank()) builder.setQueryParameter("tag_ids", "all,$tagId")
        if (!durationFrom.isNullOrBlank()) builder.setQueryParameter("duration_from", durationFrom)
        if (!durationTo.isNullOrBlank()) builder.setQueryParameter("duration_to", durationTo)
        return builder.build().toString()
    }

    fun modelBrowseUrl(baseUrl: String, page: Int): String {
        val builder = "$baseUrl/models/".toHttpUrl().newBuilder()
        if (page > 1) builder.setQueryParameter("from", page.toString())
        return builder.build().toString()
    }

    fun modelsJsonUrl(baseUrl: String, query: String, page: Int): String = "$baseUrl/models_json.php".toHttpUrl().newBuilder()
        .addQueryParameter("q", query)
        .addQueryParameter("page", page.toString())
        .build()
        .toString()

    /**
     * Shelf URL for an author. Newest order and no duration filter omit the query, so the
     * saved entry stays `/models/<slug>/` and a later upload shows up as a new episode.
     */
    fun modelCollectionUrl(
        baseUrl: String,
        dir: String,
        sortBy: String,
        durationFrom: String?,
        durationTo: String?,
    ): String {
        val builder = "$baseUrl/models/${dir.trim('/')}/".toHttpUrl().newBuilder()
        if (sortBy.isNotBlank() && sortBy != SORT_NEWEST) builder.setQueryParameter("sort_by", sortBy)
        if (!durationFrom.isNullOrBlank()) builder.setQueryParameter("duration_from", durationFrom)
        if (!durationTo.isNullOrBlank()) builder.setQueryParameter("duration_to", durationTo)
        return builder.build().toString()
    }

    fun modelPageUrl(collectionUrl: String, page: Int): String {
        val builder = collectionUrl.toHttpUrl().newBuilder().removeAllQueryParameters("from")
        if (page > 1) builder.setQueryParameter("from", page.toString())
        return builder.build().toString()
    }

    fun pathAndQuery(url: String): String {
        val http = url.toHttpUrl()
        val path = http.encodedPath.ifEmpty { "/" }
        val query = http.query
        return if (query.isNullOrEmpty()) path else "$path?$query"
    }

    fun isModelEntry(url: String): Boolean {
        val path = url.substringBefore('?').trimEnd('/')
        return path.contains("/models/") && !path.endsWith("/models")
    }

    sealed class Pasted {
        data class Model(val dir: String) : Pasted()
        data class Video(val path: String) : Pasted()
    }

    fun pasted(raw: String): Pasted? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        val host = url.host.removePrefix("www.")
        if (host != "rule34video.com") return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.size >= 2 && segments[0] == "models") {
            val dir = segments[1]
            if (dir.isNotBlank()) return Pasted.Model(dir)
        }
        if (segments.size >= 2 && segments[0] == "video" && segments[1].all { it.isDigit() }) {
            val path = url.encodedPath.let { if (it.endsWith("/")) it else "$it/" }
            return Pasted.Video(path)
        }
        return null
    }

    data class VideoCard(
        val videoId: Long,
        val path: String,
        val title: String,
        val thumbnail: String?,
        val submitted: String?,
    )

    data class ModelCard(
        val dir: String,
        val name: String,
        val path: String,
        val thumbnail: String?,
    )

    data class EpisodeDraft(
        val url: String,
        val name: String,
        val episodeNumber: Float,
        val dateUpload: Long,
        val preview: String?,
    )

    data class QualityLink(
        val label: String,
        val resolution: Int,
        val fileUrl: String,
    )

    data class VideoDetails(
        val title: String,
        val author: String,
        val description: String,
        val genres: String,
        val uploadDate: Long,
    )

    data class ModelDetails(
        val name: String,
        val avatar: String?,
        val videoCount: Int?,
        val pageCount: Int,
        val description: String,
    )

    fun parseVideoCards(root: Element): List<VideoCard> {
        val items = buildList {
            if (root.hasClass("item") && root.hasClass("thumb")) add(root)
            addAll(root.select("div.item.thumb"))
        }
        return items.mapNotNull { element ->
            val anchor = element.selectFirst("a.th[href*=/video/]") ?: return@mapNotNull null
            val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
            val id = videoHref.find(href)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
            val title = element.selectFirst(".thumb_title")?.text()?.trim().orEmpty()
                .ifBlank { anchor.attr("title").trim() }
            if (title.isEmpty()) return@mapNotNull null
            val submitted = element.selectFirst(".video-card-meta__date, .added")
                ?.let { it.attr("title").ifBlank { it.text() } }
                ?.trim()
                ?.ifBlank { null }
            VideoCard(
                videoId = id,
                path = pathAndQuery(href.substringBefore('?')),
                title = title,
                thumbnail = imageUrl(element.selectFirst("img")),
                submitted = submitted,
            )
        }
    }

    fun hasNextPage(document: Document): Boolean = document.selectFirst("div.item.pager.next a") != null

    fun parseModelCards(document: Document): List<ModelCard> {
        return document.select("a.wrap_item[href*=/models/]").mapNotNull { anchor ->
            val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
            val dir = href.toHttpUrlOrNull()?.pathSegments?.firstOrNull { it.isNotEmpty() && it != "models" }
                ?: href.substringAfter("/models/").substringBefore('/').substringBefore('?')
            if (dir.isBlank() || dir == "models") return@mapNotNull null
            val name = anchor.selectFirst(".name")?.text()?.trim().orEmpty()
                .ifBlank { anchor.selectFirst("img")?.attr("alt").orEmpty().trim() }
            if (name.isEmpty()) return@mapNotNull null
            ModelCard(
                dir = dir,
                name = name,
                path = "/models/$dir/",
                thumbnail = imageUrl(anchor.selectFirst("img")),
            )
        }.distinctBy { it.dir }
    }

    fun parseModelsJson(body: String): List<ModelCard> {
        val parsed = json.decodeFromString<ModelsResponse>(body)
        return parsed.items.mapNotNull { item ->
            val dir = item.dir.trim()
            val name = item.title.trim()
            if (dir.isEmpty() || name.isEmpty()) return@mapNotNull null
            ModelCard(dir = dir, name = name, path = "/models/$dir/", thumbnail = null)
        }
    }

    fun isModelDocument(document: Document): Boolean = document.selectFirst("div.brand_inform div.title") != null ||
        document.selectFirst("[data-list-type=models]") != null

    fun parseModelDetails(document: Document): ModelDetails {
        val name = document.selectFirst("div.brand_inform div.title")?.text()?.trim().orEmpty()
            .ifBlank { document.selectFirst("h1.title span")?.text()?.trim().orEmpty() }
            .ifBlank { "Model" }
        val count = videoTotal(document)
        return ModelDetails(
            name = name,
            avatar = avatarSrc(document.outerHtml()) ?: imageUrl(document.selectFirst("div.brand_image img")),
            videoCount = count,
            pageCount = pageCount(document),
            description = if (count != null) "作者合集：$count 个视频" else "作者合集",
        )
    }

    fun pageCount(document: Document): Int {
        val raw = document.selectFirst("[data-list-type=models][data-pages], .fs-ui[data-pages]")
            ?.attr("data-pages")
        return raw?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    }

    fun videoTotal(document: Document): Int? {
        val attr = document.selectFirst("[data-list-type=models][data-total], .fs-ui[data-total]")
            ?.attr("data-total")
            ?.toIntOrNull()
        if (attr != null) return attr
        val text = document.selectFirst("span.total_results")?.text().orEmpty()
        return text.filter { it.isDigit() }.toIntOrNull()
    }

    /**
     * Newest first, as the site lists them. Episode numbers count down from the size of this
     * list so a new upload at the front becomes the next episode without renumbering the rest,
     * as long as the fetch still covers every page (capped at [MAX_MODEL_PAGES]).
     */
    fun episodesFrom(cards: List<VideoCard>, nowMillis: Long): List<EpisodeDraft> {
        val unique = LinkedHashMap<Long, VideoCard>()
        for (card in cards) unique.putIfAbsent(card.videoId, card)
        val list = unique.values.toList()
        val total = list.size
        return list.mapIndexed { index, card ->
            EpisodeDraft(
                url = card.path,
                name = card.title,
                episodeNumber = (total - index).toFloat(),
                dateUpload = parseSubmitted(card.submitted, nowMillis),
                preview = card.thumbnail,
            )
        }
    }

    fun parseSubmitted(text: String?, nowMillis: Long): Long {
        if (text.isNullOrBlank()) return 0L
        val cleaned = text.substringAfter("Submitted:", text).trim().lowercase(Locale.US)
        when (cleaned) {
            "today", "just now" -> return nowMillis
            "yesterday" -> return nowMillis - DAY_MS
        }
        val amount = Regex("(\\d+)").find(cleaned)?.groupValues?.get(1)?.toLongOrNull() ?: return 0L
        val unit = when {
            "second" in cleaned -> 1000L
            "minute" in cleaned -> 60_000L
            "hour" in cleaned -> 3_600_000L
            "day" in cleaned -> DAY_MS
            "week" in cleaned -> 7 * DAY_MS
            "month" in cleaned -> 30 * DAY_MS
            "year" in cleaned -> 365 * DAY_MS
            else -> return 0L
        }
        return nowMillis - amount * unit
    }

    fun parseIsoDate(text: String): Long = LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun parseVideoDetails(document: Document): VideoDetails {
        val title = document.selectFirst("h1.title_video")?.text()?.trim().orEmpty()
            .ifBlank { document.title().substringBefore(" - ").trim() }
        val artists = document.select("[data-suggest-type=model] a[href*=/models/] span.name")
            .eachText()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty {
                videoModels.find(document.html())?.groupValues?.get(1).orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
        val uploader = document.selectFirst("div.col:has(div.label:contains(Uploaded by)) a")
            ?.text()
            ?.trim()
            .orEmpty()
        val categories = document.select("[data-suggest-type=category] a[href*=/categories/]")
            .eachText()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val tags = document.select("[data-suggest-type=tag] a.tag_item")
            .eachText()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("suggest", ignoreCase = true) }
        val about = document.selectFirst("div.vp-desc em")?.html()
            ?.replace(Regex("(?i)<br\\s*/?>"), "\n")
            ?.replace(Regex("<[^>]+>"), "")
            ?.trim()
            .orEmpty()
        val uploadDate = isoDate.find(document.html())?.groupValues?.get(1)?.let(::parseIsoDate) ?: 0L
        val qualities = parseDownloadLinks(document).joinToString(", ") { it.label }
        val info = document.select("span.item_info")
        var views = ""
        var duration = ""
        var uploaded = ""
        info.forEach { span ->
            val kind = span.selectFirst("svg")?.className().orEmpty()
            val value = span.selectFirst("span")?.text()?.trim().orEmpty().ifBlank { span.text().trim() }
            when {
                "calendar" in kind -> uploaded = value
                "eye" in kind -> views = value.substringBefore(" ").trim()
                "time" in kind -> duration = value
            }
        }
        val description = buildString {
            if (about.isNotEmpty()) {
                append(about)
                append("\n\n")
            }
            if (artists.isNotEmpty()) append("Artists: ${artists.joinToString()}\n")
            if (categories.isNotEmpty()) append("Categories: ${categories.joinToString()}\n")
            if (uploader.isNotEmpty()) append("Uploader: $uploader\n")
            if (uploaded.isNotEmpty()) append("Uploaded: $uploaded\n")
            if (views.isNotEmpty()) append("Views: $views\n")
            if (duration.isNotEmpty()) append("Duration: $duration\n")
            if (qualities.isNotEmpty()) append("Quality: $qualities")
        }.trim()
        return VideoDetails(
            title = title,
            author = artists.firstOrNull() ?: uploader,
            description = description,
            genres = tags.joinToString(),
            uploadDate = uploadDate,
        )
    }

    fun parseDownloadLinks(document: Document): List<QualityLink> {
        val seen = LinkedHashSet<Int>()
        val links = mutableListOf<QualityLink>()
        document.select("a.tag_item_download[href*=get_file], a.tag_item[href*=get_file]").forEach { anchor ->
            val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
            if (href.contains("preview")) return@forEach
            if (!href.contains(".mp4")) return@forEach
            val labelText = anchor.text()
            val fromText = resolutionLabel.find(labelText)?.groupValues?.get(1)?.toIntOrNull()
            val fromFile = Regex("""_(\d{3,4})p?\.mp4""").find(href.substringBefore('?'))
                ?.groupValues?.get(1)?.toIntOrNull()
            val resolution = fromText ?: fromFile ?: return@forEach
            if (!seen.add(resolution)) return@forEach
            links += QualityLink(
                label = "${resolution}p",
                resolution = resolution,
                fileUrl = directFileUrl(href),
            )
        }
        return links
    }

    /** Drop attachment flags. The acctoken stays; the CDN redirect is what the player uses. */
    fun directFileUrl(href: String): String {
        val http = href.toHttpUrlOrNull() ?: return href
        return http.newBuilder()
            .removeAllQueryParameters("download")
            .removeAllQueryParameters("download_filename")
            .build()
            .toString()
    }

    fun sortQualities(links: List<QualityLink>, preferred: String): List<QualityLink> {
        val ranked = links.sortedByDescending { it.resolution }
        val want = preferred.substringBefore('p').toIntOrNull() ?: return ranked
        return ranked.sortedByDescending { it.resolution == want }
    }

    fun avatarSrc(html: String): String? {
        val marker = html.indexOf("brand_image")
        if (marker < 0) return null
        val slice = html.substring(marker, minOf(html.length, marker + 2500))
        val match = Regex("""<img[^>]*\ssrc="(https?://[^"]+)"""").find(slice)
            ?: Regex("""<img[^>]*\ssrc='(https?://[^']+)'""").find(slice)
        return match?.groupValues?.get(1)
    }

    private fun imageUrl(img: Element?): String? {
        if (img == null) return null
        val candidates = listOf(
            img.attr("abs:data-rd-src"),
            img.attr("data-rd-src"),
            img.attr("abs:src"),
            img.attr("src"),
            img.attr("abs:data-rd-jpg"),
            img.attr("data-rd-jpg"),
            img.attr("abs:data-original"),
            img.attr("data-original"),
        )
        return candidates.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
    }

    @Serializable
    private data class ModelsResponse(
        val items: List<ModelItem> = emptyList(),
    )

    @Serializable
    private data class ModelItem(
        val title: String = "",
        val dir: String = "",
    )

    private const val DAY_MS = 86_400_000L
}
