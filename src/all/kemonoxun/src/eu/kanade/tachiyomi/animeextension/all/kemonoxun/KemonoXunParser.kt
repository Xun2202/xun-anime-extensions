package eu.kanade.tachiyomi.animeextension.all.kemonoxun

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Kemono / Coomer public JSON. No Android imports, so unit tests call this directly.
 *
 * Measured against https://kemono.cr on 2026-10-18:
 * - Every `/api/v1/` route answers 200 only when `Accept` is `text/css`. `application/json` is 403
 *   with a plain-text note. The body is still JSON (often gzip; OkHttp decompresses it).
 * - `/api/v1/creators` is one array (~108k objects, ~12.7MB). `updated` is unix seconds.
 *   A profile payload uses an ISO string for the same idea, so [FlexibleEpochSerializer] takes both.
 * - `/api/v1/{service}/user/{id}/posts?o=` is a bare array, 50 per page. `o` past the end,
 *   or not a multiple of 50, is HTTP 400 `{"error":"Offset ..."}` and means "stop", not a crash.
 * - `/api/v1/posts?q=&o=` is `{count, true_count, posts}`. An empty `q` is the global recent feed
 *   (about 2 videos in the newest 50, then 0 in the next sampled pages). `q=mp4` was 39/50 real
 *   video files. There is no cheap "creators who have posted video" flag.
 * - Post detail adds `videos[]` and `attachments[]` with `server` (`https://n4.kemono.cr`), `name`, `path`,
 *   `extension`. Playback is `{server}/data{path}?f={name}` when `server` is a valid https origin,
 *   otherwise `{base}/data{path}?f={name}`. `n1`–`n4` (and `n*.coomer.st`) have been unreachable on
 *   port 443 since about 2026-04; the source probes those hosts before play. This parser only classifies
 *   the probe results.
 * - Discord creators are dropped from browse. Favorites need a login cookie (401) and are not used.
 */
object KemonoXunParser {
    const val USER_AGENT = "Anikku-kemonoxun-extension/1.0 (by Xun2202 on github)"

    const val SOURCE_NAME = "Kemono Xun"
    const val SOURCE_LANG = "all"

    const val DEFAULT_BASE = "https://kemono.cr"
    const val COOMER_BASE = "https://coomer.st"

    const val PAGE_SIZE = 50
    const val DEFAULT_POST_PAGES = 10
    const val MAX_POST_PAGES = 100

    /** Keyword used when "近期视频帖" is opened with an empty search box. */
    const val DEFAULT_RECENT_QUERY = "mp4"

    const val MAX_RETRIES = 3
    const val RETRY_BASE_MILLIS = 2_000L
    const val CREATOR_CACHE_TTL_MILLIS = 30L * 60L * 1000L

    const val TITLE_LIMIT = 120

    const val NO_VIDEO_NOTE = "已加载的帖子里没有视频。No video posts in the loaded pages."
    const val HTML_MESSAGE =
        "返回的是网页而不是 JSON，多半是 DDoS-Guard 验证页。请用 WebView 打开站点后再试。 / " +
            "Got HTML instead of JSON, likely a DDoS-Guard challenge. Open the site in WebView and retry."
    const val FORBIDDEN_MESSAGE =
        "Kemono 拒绝了接口请求（403）。请确认带 Accept: text/css，或先用 WebView 打开站点通过 DDoS-Guard。 / " +
            "Kemono rejected the API call (403). The request needs Accept: text/css, or open the site in WebView to pass DDoS-Guard."
    const val RATE_LIMIT_MESSAGE =
        "请求过于频繁（429），已退避重试仍失败。请稍后再试。 / Rate limited (429) after retries. Try again shortly."
    const val TIMEOUT_MESSAGE =
        "连接超时。接口请稍后重试；视频文件在 n*.kemono.cr 上，这个服务器经常连不上。 / " +
            "Timed out. Retry the API; video files live on n*.kemono.cr, which often fails to connect."
    const val HOST_MESSAGE =
        "找不到服务器。请检查扩展设置里的站点地址。 / Could not resolve the host. Check the base URL in extension settings."
    const val BAD_JSON_MESSAGE = "站点返回的不是预期的 JSON。 / The site did not return the expected JSON."
    const val NO_PLAYABLE_MESSAGE = "这条帖子没有可播放的视频。 / This post has no playable video."
    const val FILE_HOST_NOTE = "视频文件放在 n* 服务器上，这些机器经常整站宕机，章节能列出来也不代表能播放。"
    const val UNREACHABLE_LABEL = "（服务器不可达）"

    const val PROBE_CONNECT_SECONDS = 4L
    const val PROBE_CALL_SECONDS = 6L
    const val HOST_PROBE_TTL_MILLIS = 5L * 60L * 1000L

    val BASE_CHOICES = arrayOf(DEFAULT_BASE, COOMER_BASE)

    /** Shown in the service filter. Discord is intentionally absent. afdian had 0 creators on 2026-10-18. */
    val FILTER_SERVICES = listOf(
        "patreon" to "Patreon",
        "fanbox" to "Pixiv Fanbox",
        "gumroad" to "Gumroad",
        "fantia" to "Fantia",
        "boosty" to "Boosty",
        "subscribestar" to "SubscribeStar",
        "dlsite" to "DLsite",
        "afdian" to "Afdian",
        "onlyfans" to "OnlyFans",
        "fansly" to "Fansly",
    )

    private val serviceNames = FILTER_SERVICES.toMap()

    val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv", "avi")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp")

    private val postLink = Regex(
        """^(?:https?://[^/\s]+)?/([A-Za-z0-9_-]+)/user/([^/\s?#]+)/post/([^/\s?#]+)/?$""",
        RegexOption.IGNORE_CASE,
    )
    private val userLink = Regex(
        """^(?:https?://[^/\s]+)?/([A-Za-z0-9_-]+)/user/([^/\s?#]+)/?$""",
        RegexOption.IGNORE_CASE,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun normalizeBaseUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().trimEnd('/')
        if (!trimmed.startsWith("https://")) return null
        val host = trimmed.removePrefix("https://")
        if (host.isEmpty() || host.contains('/') || host.contains('@') || host.contains(' ') || '.' !in host) return null
        return trimmed
    }

    fun effectiveBase(listValue: String?, custom: String?): String {
        normalizeBaseUrl(custom)?.let { return it }
        return normalizeBaseUrl(listValue) ?: DEFAULT_BASE
    }

    fun maxPages(raw: String?): Int = raw?.toIntOrNull()?.coerceIn(1, MAX_POST_PAGES) ?: DEFAULT_POST_PAGES

    fun shouldRetryStatus(code: Int, attempt: Int): Boolean = code == 429 && attempt < MAX_RETRIES

    fun retryDelayMillis(attempt: Int): Long = RETRY_BASE_MILLIS shl attempt.coerceIn(0, MAX_RETRIES - 1)

    fun transportMessage(error: Throwable): String? {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is SocketTimeoutException -> return TIMEOUT_MESSAGE
                is UnknownHostException -> return HOST_MESSAGE
                is ConnectException -> return TIMEOUT_MESSAGE
            }
            val text = current.message?.lowercase().orEmpty()
            if ("timeout" in text || "timed out" in text) return TIMEOUT_MESSAGE
            current = current.cause
        }
        return null
    }

    fun looksLikeHtml(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("<!") ||
            trimmed.startsWith("<html", ignoreCase = true) ||
            trimmed.startsWith("<HTML") ||
            trimmed.startsWith("<head", ignoreCase = true) ||
            trimmed.startsWith("<body", ignoreCase = true)
    }

    fun isOffsetError(code: Int, body: String): Boolean {
        if (code != 400) return false
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return false
        val error = runCatching { json.decodeFromString(ApiErrorBody.serializer(), trimmed).error }.getOrNull() ?: return false
        return error.startsWith("Offset", ignoreCase = true)
    }

    fun ensureOk(code: Int, body: String) {
        val trimmed = body.trim()
        if (looksLikeHtml(trimmed)) throw IllegalStateException(HTML_MESSAGE)
        if (code == 403 || trimmed.contains("Accept: text/css")) throw IllegalStateException(FORBIDDEN_MESSAGE)
        if (code == 429) throw IllegalStateException(RATE_LIMIT_MESSAGE)
        if (code !in 200..299) {
            val api = runCatching { json.decodeFromString(ApiErrorBody.serializer(), trimmed).error }.getOrNull()
            val detail = api?.take(180) ?: "HTTP $code"
            throw IllegalStateException("请求失败（$detail）。 / Request failed ($detail).")
        }
        if (trimmed.isEmpty() || (trimmed[0] != '{' && trimmed[0] != '[')) throw IllegalStateException(BAD_JSON_MESSAGE)
    }

    fun serviceName(service: String): String {
        val key = service.lowercase()
        return serviceNames[key] ?: service.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase() else char.toString()
        }
    }

    fun parseDirectLink(raw: String): DirectLink? {
        val text = raw.trim().substringBefore('#').substringBefore('?')
        if (text.isEmpty()) return null
        postLink.matchEntire(text)?.let { match ->
            val service = match.groupValues[1].lowercase()
            val id = decode(match.groupValues[2])
            val postId = decode(match.groupValues[3])
            if (id.isEmpty() || postId.isEmpty()) return null
            return DirectLink.Post(service, id, postId)
        }
        userLink.matchEntire(text)?.let { match ->
            val service = match.groupValues[1].lowercase()
            val id = decode(match.groupValues[2])
            if (id.isEmpty()) return null
            return DirectLink.User(service, id)
        }
        return null
    }

    fun creatorPath(service: String, id: String): String = "/$service/user/$id"

    fun postPath(service: String, id: String, postId: String): String = "/$service/user/$id/post/$postId"

    fun imageOrigin(base: String): String {
        val root = normalizeBaseUrl(base) ?: DEFAULT_BASE
        return root.replace("://", "://img.")
    }

    fun iconUrl(base: String, service: String, id: String): String = "${imageOrigin(base)}/icons/$service/$id"

    fun bannerUrl(base: String, service: String, id: String): String = "${imageOrigin(base)}/banners/$service/$id"

    fun thumbnailUrl(base: String, path: String): String {
        val normalized = if (path.startsWith("/")) path else "/$path"
        return "${imageOrigin(base)}/thumbnail/data$normalized"
    }

    /**
     * `{server}/data{path}?f={name}` when [server] is https with no path, else `{base}/data{path}?f={name}`.
     * Skips the API host's 302 onto `n*`. The player still sends Referer for the catalogue host.
     */
    fun fileUrl(base: String, path: String, name: String?, server: String? = null): String {
        val root = normalizeBaseUrl(server) ?: normalizeBaseUrl(base) ?: DEFAULT_BASE
        val normalized = if (path.startsWith("/")) path else "/$path"
        val url = "$root/data$normalized"
        if (name.isNullOrBlank()) return url
        val encoded = URLEncoder.encode(name, Charsets.UTF_8.name()).replace("+", "%20")
        return "$url?f=$encoded"
    }

    fun fileHost(url: String): String = runCatching {
        java.net.URI(url).host?.lowercase().orEmpty()
    }.getOrDefault("")

    /** Shown when every file host for one episode failed the reachability probe. */
    fun fileServerMessage(hosts: Collection<String>): String {
        val shown = hosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val who = if (shown.isEmpty()) "n*" else shown.joinToString("、")
        return "文件服务器 $who 当前连不上（站点故障，不是插件问题），稍后再试 / file server unreachable"
    }

    /**
     * [reachable] is host to probe result. A missing host is kept (the probe did not run).
     * Reachable links stay in their existing order, then unreachable ones with [UNREACHABLE_LABEL].
     * When every link is explicitly unreachable, returns [PlaybackChoice.Blocked] instead of a list.
     */
    fun classifyPlayback(links: List<VideoLink>, reachable: Map<String, Boolean>): PlaybackChoice {
        if (links.isEmpty()) return PlaybackChoice.Blocked(NO_PLAYABLE_MESSAGE)
        val up = ArrayList<VideoLink>(links.size)
        val down = ArrayList<VideoLink>()
        val downHosts = LinkedHashSet<String>()
        for (link in links) {
            val host = fileHost(link.url)
            if (host.isNotEmpty() && reachable[host] == false) {
                downHosts += host
                val label = if (UNREACHABLE_LABEL in link.label) link.label else link.label + UNREACHABLE_LABEL
                down += link.copy(label = label)
            } else {
                up += link
            }
        }
        if (up.isEmpty()) return PlaybackChoice.Blocked(fileServerMessage(downHosts))
        return PlaybackChoice.Ready(up + down)
    }

    fun recentQuery(raw: String): String = raw.trim().ifEmpty { DEFAULT_RECENT_QUERY }

    fun parseCreators(body: String): List<Creator> {
        val element = jsonElement(body)
        if (element !is JsonArray) throw IllegalStateException(BAD_JSON_MESSAGE)
        val raw = decodeList(element, Creator.serializer())
        val usable = raw.filter { it.id.isNotBlank() && it.service.isNotBlank() }
        if (usable.isEmpty() && element.isNotEmpty()) throw IllegalStateException(BAD_JSON_MESSAGE)
        return usable
    }

    fun parsePosts(body: String): List<Post> = when (val element = jsonElement(body)) {
        is JsonArray -> decodeList(element, Post.serializer())
        is JsonObject -> when {
            "posts" in element -> {
                val decoded = runCatching { json.decodeFromJsonElement(PostSearchBody.serializer(), element) }.getOrNull()
                if (decoded != null) {
                    decoded.posts
                } else {
                    val posts = element["posts"]
                    if (posts is JsonArray) decodeList(posts, Post.serializer()) else throw IllegalStateException(BAD_JSON_MESSAGE)
                }
            }
            "post" in element -> listOfNotNull(parseDetail(body).post)
            "error" in element -> {
                val message = (element["error"] as? JsonPrimitive)?.content?.take(180) ?: BAD_JSON_MESSAGE
                throw IllegalStateException("请求失败（$message）。 / Request failed ($message).")
            }
            else -> throw IllegalStateException(BAD_JSON_MESSAGE)
        }
        else -> throw IllegalStateException(BAD_JSON_MESSAGE)
    }

    fun parseDetail(body: String): PostDetail {
        val element = jsonElement(body)
        if (element !is JsonObject || "post" !in element) throw IllegalStateException(BAD_JSON_MESSAGE)
        return json.decodeFromJsonElement(PostDetail.serializer(), element)
    }

    fun parseProfile(body: String): Profile {
        val element = jsonElement(body)
        if (element !is JsonObject) throw IllegalStateException(BAD_JSON_MESSAGE)
        return json.decodeFromJsonElement(Profile.serializer(), element)
    }

    fun browseCreators(
        creators: List<Creator>,
        page: Int,
        query: String = "",
        includeServices: Set<String> = emptySet(),
        excludeServices: Set<String> = emptySet(),
        sort: CreatorSort = CreatorSort.FAVORITED,
        pageSize: Int = PAGE_SIZE,
    ): CreatorPage {
        val filtered = filterCreators(creators, query, includeServices, excludeServices, sort)
        if (page < 1) return CreatorPage(emptyList(), false)
        val from = (page - 1) * pageSize
        if (from >= filtered.size) return CreatorPage(emptyList(), false)
        val to = minOf(filtered.size, from + pageSize)
        return CreatorPage(filtered.subList(from, to).toList(), to < filtered.size)
    }

    fun filterCreators(
        creators: List<Creator>,
        query: String,
        includeServices: Set<String>,
        excludeServices: Set<String>,
        sort: CreatorSort,
    ): List<Creator> {
        val needle = query.trim()
        val include = includeServices.map { it.lowercase() }.toSet()
        val exclude = excludeServices.map { it.lowercase() }.toSet()
        val matched = creators.filter { creator ->
            val service = creator.service.lowercase()
            if (service == "discord" || service.isBlank() || creator.id.isBlank()) return@filter false
            if (include.isNotEmpty() && service !in include) return@filter false
            if (service in exclude) return@filter false
            needle.isEmpty() || creator.name.contains(needle, ignoreCase = true)
        }
        return when (sort) {
            CreatorSort.FAVORITED -> matched.sortedWith(
                compareByDescending<Creator> { it.favorited }.thenBy { it.name.lowercase() },
            )
            CreatorSort.UPDATED -> matched.sortedWith(
                compareByDescending<Creator> { it.updated }.thenByDescending { it.favorited },
            )
            CreatorSort.NAME -> matched.sortedBy { it.name.lowercase() }
        }
    }

    fun entryFromCreator(creator: Creator, base: String): Entry? {
        if (creator.service.equals("discord", ignoreCase = true)) return null
        if (creator.service.isBlank() || creator.id.isBlank()) return null
        val name = creator.name.ifBlank { creator.id }
        return Entry(
            path = creatorPath(creator.service, creator.id),
            title = name,
            author = serviceName(creator.service),
            description = "${serviceName(creator.service)} · 收藏 ${creator.favorited}",
            genres = serviceName(creator.service),
            thumbnail = iconUrl(base, creator.service, creator.id),
            background = bannerUrl(base, creator.service, creator.id),
            singlePost = false,
        )
    }

    /**
     * One card per video post. Opening it plays that post only.
     * Collapsing to creators would hide the post (the search payload has no display name)
     * and then force a multi-page walk before anything plays.
     */
    fun entryFromPost(post: Post, base: String, requireVideo: Boolean): Entry? {
        if (post.service.isBlank() || post.user.isBlank() || post.id.isBlank()) return null
        if (requireVideo && !hasVideo(post)) return null
        val tags = post.tags.orEmpty().take(8).joinToString(", ")
        return Entry(
            path = postPath(post.service, post.user, post.id),
            title = episodeName(post),
            author = serviceName(post.service),
            description = postCardDescription(post),
            genres = tags,
            thumbnail = postThumbnail(base, post, null),
            background = null,
            singlePost = true,
        )
    }

    fun recentHasNext(rawCount: Int): Boolean = rawCount >= PAGE_SIZE

    suspend fun walkPosts(maxPages: Int, fetch: suspend (offset: Int) -> PostBatch): PostWalk {
        val capped = maxPages.coerceIn(1, MAX_POST_PAGES)
        val all = mutableListOf<Post>()
        val seen = HashSet<String>()
        var pages = 0
        for (index in 0 until capped) {
            when (val batch = fetch(index * PAGE_SIZE)) {
                PostBatch.End -> break
                is PostBatch.Page -> {
                    pages++
                    var fresh = 0
                    for (post in batch.posts) {
                        if (post.id.isEmpty()) continue
                        if (seen.add(post.service + ":" + post.id)) {
                            all += post
                            fresh++
                        }
                    }
                    if (batch.posts.size < PAGE_SIZE || fresh == 0) break
                }
            }
        }
        return PostWalk(all, pages)
    }

    fun hasVideo(post: Post): Boolean {
        if (post.file?.let(::isVideo) == true) return true
        return post.attachments.any(::isVideo)
    }

    fun detailHasVideo(detail: PostDetail): Boolean {
        if (detail.videos.orEmpty().any(::isVideo)) return true
        if (detail.attachments.orEmpty().any(::isVideo)) return true
        val post = detail.post ?: return false
        return hasVideo(post)
    }

    /** Oldest video post is episode 1. The list is newest-first, so the top row has the highest number. */
    fun videoEpisodes(posts: List<Post>, base: String): List<EpisodeDraft> {
        val playable = posts.filter(::hasVideo).distinctBy { it.service + ":" + it.id }
        val ordered = playable.sortedBy { publishedMillis(it.published) }
        return ordered.mapIndexed { index, post ->
            EpisodeDraft(
                path = postPath(post.service, post.user, post.id),
                name = episodeName(post),
                episodeNumber = (index + 1).toFloat(),
                dateUpload = publishedMillis(post.published),
                preview = postThumbnail(base, post, null),
            )
        }.asReversed()
    }

    fun episodeFromDetail(detail: PostDetail, base: String): EpisodeDraft? {
        if (!detailHasVideo(detail)) return null
        val post = detail.post ?: return null
        if (post.service.isBlank() || post.user.isBlank() || post.id.isBlank()) return null
        return EpisodeDraft(
            path = postPath(post.service, post.user, post.id),
            name = episodeName(post),
            episodeNumber = 1f,
            dateUpload = publishedMillis(post.published),
            preview = postThumbnail(base, post, detail),
        )
    }

    fun videosFromBody(body: String, base: String): List<VideoLink> = videosFromDetail(parseDetail(body), base)

    fun videosFromDetail(detail: PostDetail, base: String): List<VideoLink> {
        val files = LinkedHashMap<String, MediaFile>()
        fun add(file: MediaFile?) {
            if (file == null || !isVideo(file)) return
            val key = file.path?.takeIf { it.isNotBlank() } ?: file.name?.takeIf { it.isNotBlank() } ?: return
            val existing = files[key]
            if (existing == null || (existing.server.isNullOrBlank() && !file.server.isNullOrBlank())) {
                files[key] = file
            }
        }
        detail.videos.orEmpty().forEach(::add)
        add(detail.post?.file)
        detail.post?.attachments.orEmpty().forEach(::add)
        detail.attachments.orEmpty().forEach(::add)
        val links = files.values.mapNotNull { file ->
            val path = file.path?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            VideoLink(
                url = fileUrl(base, path, file.name, file.server),
                label = labelOf(file),
                resolution = resolutionHint(file),
            )
        }
        val ranked = links.sortedByDescending { it.resolution }
        val seen = HashMap<String, Int>()
        return ranked.map { link ->
            val count = seen.merge(link.label, 1, Int::plus) ?: 1
            if (count == 1) link else link.copy(label = "${link.label} ($count)")
        }
    }

    fun creatorDescription(
        base: String,
        service: String,
        id: String,
        name: String,
        postCount: Int?,
        videoPosts: Int?,
        pagesLoaded: Int?,
        maxPages: Int,
    ): String = buildString {
        append(serviceName(service))
        append(" · ")
        append(name.ifBlank { id })
        append('\n')
        append(normalizeBaseUrl(base) ?: DEFAULT_BASE)
        append(creatorPath(service, id))
        append('\n')
        if (postCount != null) append("站点帖子约 ").append(postCount).append(" 条。\n")
        append("只收录视频附件（mp4、m4v、mov、webm、mkv、avi）。压缩包和图片不算。\n")
        if (videoPosts != null && pagesLoaded != null) {
            append("已加载 ").append(pagesLoaded).append(" 页（上限 ").append(maxPages).append("），视频帖 ")
            append(videoPosts).append(" 条。")
            if (videoPosts == 0) {
                append('\n')
                append(NO_VIDEO_NOTE)
            }
        }
        append('\n')
        append(FILE_HOST_NOTE)
    }

    fun publishedMillis(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val trimmed = text.trim()
        trimmed.toLongOrNull()?.let { return epochMillis(it) }
        trimmed.toDoubleOrNull()?.let { return epochMillis(it.toLong()) }
        return runCatching { LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrDefault(0L)
    }

    fun episodeName(post: Post): String {
        val title = post.title?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (title.isNotEmpty()) return truncate(title)
        val date = post.published?.take(10).orEmpty()
        return date.ifEmpty { post.id }
    }

    fun plainText(html: String?): String {
        if (html.isNullOrBlank()) return ""
        return html
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("[ \\t\\u00A0]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun isVideo(file: MediaFile): Boolean = mediaExtension(file) in VIDEO_EXTENSIONS

    fun mediaExtension(file: MediaFile): String {
        val explicit = file.extension?.trim()?.removePrefix(".")?.lowercase().orEmpty()
        if (explicit.isNotEmpty() && explicit.none { it == '/' || it.isWhitespace() }) return explicit
        val pathExt = extensionOf(file.path)
        if (pathExt.isNotEmpty()) return pathExt
        return extensionOf(file.name)
    }

    private fun jsonElement(body: String): JsonElement {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) throw IllegalStateException(BAD_JSON_MESSAGE)
        if (looksLikeHtml(trimmed)) throw IllegalStateException(HTML_MESSAGE)
        return try {
            json.parseToJsonElement(trimmed)
        } catch (error: Exception) {
            throw IllegalStateException(BAD_JSON_MESSAGE, error)
        }
    }

    private fun <T> decodeList(element: JsonArray, serializer: KSerializer<T>): List<T> {
        val fast = runCatching { json.decodeFromJsonElement(ListSerializer(serializer), element) }.getOrNull()
        if (fast != null) return fast
        return element.mapNotNull { item ->
            if (item !is JsonObject) {
                null
            } else {
                runCatching { json.decodeFromJsonElement(serializer, item) }.getOrNull()
            }
        }
    }

    private fun postCardDescription(post: Post): String = buildString {
        append("创作者 ")
        append(creatorPath(post.service, post.user))
        append("\n这一条是视频帖，打开后只有这一集。要追更，把创作者链接贴进搜索框。\n")
        append(FILE_HOST_NOTE)
        val extra = plainText(post.content).ifBlank { post.substring?.trim().orEmpty() }
        if (extra.isNotEmpty()) {
            append("\n\n")
            append(truncate(extra, 800))
        }
    }

    private fun postThumbnail(base: String, post: Post, detail: PostDetail?): String? {
        val images = mutableListOf<MediaFile>()
        post.file?.let { images += it }
        images += post.attachments
        detail?.post?.file?.let { images += it }
        detail?.post?.attachments?.let { images += it }
        detail?.attachments?.let { images += it }
        detail?.previews.orEmpty().forEach { preview ->
            images += MediaFile(name = preview.name, path = preview.path)
        }
        val image = images.firstOrNull { isImage(it) && !it.path.isNullOrBlank() }
        if (image != null) return thumbnailUrl(base, image.path!!)
        if (post.service.isNotBlank() && post.user.isNotBlank()) return iconUrl(base, post.service, post.user)
        return null
    }

    private fun isImage(file: MediaFile): Boolean = mediaExtension(file) in imageExtensions

    private fun labelOf(file: MediaFile): String {
        val name = file.name?.trim().orEmpty()
        if (name.isNotEmpty()) return name
        val ext = mediaExtension(file)
        return ext.ifEmpty { "video" }
    }

    private fun resolutionHint(file: MediaFile): Int {
        val lower = (file.name.orEmpty() + " " + mediaExtension(file)).lowercase()
        return when {
            "2160" in lower || "4k" in lower -> 2160
            "1440" in lower -> 1440
            "1080" in lower -> 1080
            "720" in lower -> 720
            "480" in lower -> 480
            else -> 0
        }
    }

    private fun extensionOf(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val base = value.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        val dot = base.lastIndexOf('.')
        if (dot <= 0 || dot == base.lastIndex) return ""
        return base.substring(dot + 1).lowercase()
    }

    private fun truncate(text: String, limit: Int = TITLE_LIMIT): String {
        if (text.length <= limit) return text
        return text.take(limit - 1).trimEnd() + "…"
    }

    private fun epochMillis(raw: Long): Long = if (raw > 10_000_000_000L) raw else raw * 1000

    private fun decode(raw: String): String = runCatching {
        URLDecoder.decode(raw, Charsets.UTF_8.name())
    }.getOrDefault(raw)

    enum class CreatorSort { FAVORITED, UPDATED, NAME }

    sealed class DirectLink {
        abstract val service: String
        abstract val id: String

        data class User(override val service: String, override val id: String) : DirectLink()
        data class Post(override val service: String, override val id: String, val postId: String) : DirectLink()
    }

    sealed class PostBatch {
        class Page(val posts: List<Post>) : PostBatch()
        object End : PostBatch()
    }

    data class PostWalk(val posts: List<Post>, val pagesFetched: Int)

    data class CreatorPage(val creators: List<Creator>, val hasNextPage: Boolean)

    data class Entry(
        val path: String,
        val title: String,
        val author: String,
        val description: String,
        val genres: String,
        val thumbnail: String?,
        val background: String?,
        val singlePost: Boolean,
    )

    data class EpisodeDraft(
        val path: String,
        val name: String,
        val episodeNumber: Float,
        val dateUpload: Long,
        val preview: String?,
    )

    data class VideoLink(val url: String, val label: String, val resolution: Int)

    sealed class PlaybackChoice {
        data class Ready(val links: List<VideoLink>) : PlaybackChoice()
        data class Blocked(val message: String) : PlaybackChoice()
    }

    /**
     * Memory plus a JSON file in the app cache dir. [CREATOR_CACHE_TTL_MILLIS] is 30 minutes,
     * matching the stale window keiyoushi's Kemono source puts on `/api/v1/creators`.
     */
    class CreatorCache(
        private val directory: File,
        private val ttlMillis: Long = CREATOR_CACHE_TTL_MILLIS,
    ) {
        private val lock = Any()
        private var memoryHost: String? = null
        private var memoryAt: Long = 0L
        private var memory: List<Creator>? = null

        fun read(host: String, now: Long = System.currentTimeMillis()): List<Creator>? = synchronized(lock) {
            if (memoryHost == host && memory != null && now - memoryAt <= ttlMillis) return memory
            val stamp = runCatching { stampFile(host).readText().trim().toLong() }.getOrNull() ?: return null
            if (now - stamp > ttlMillis) return null
            val body = runCatching { jsonFile(host).readText() }.getOrNull() ?: return null
            val parsed = runCatching { parseCreators(body) }.getOrElse {
                jsonFile(host).delete()
                stampFile(host).delete()
                null
            } ?: return null
            memoryHost = host
            memoryAt = stamp
            memory = parsed
            parsed
        }

        fun write(host: String, body: String, parsed: List<Creator>, now: Long = System.currentTimeMillis()) {
            synchronized(lock) {
                runCatching {
                    directory.mkdirs()
                    val target = jsonFile(host)
                    val tmp = File(directory, target.name + ".tmp")
                    tmp.writeText(body)
                    if (!tmp.renameTo(target)) {
                        target.writeText(body)
                        tmp.delete()
                    }
                    stampFile(host).writeText(now.toString())
                }
                memoryHost = host
                memoryAt = now
                memory = parsed
            }
        }

        private fun jsonFile(host: String): File = File(directory, fileKey(host) + ".json")

        private fun stampFile(host: String): File = File(directory, fileKey(host) + ".stamp")

        private fun fileKey(host: String): String = host.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    @Serializable
    data class Creator(
        @Serializable(with = FlexibleStringSerializer::class)
        val id: String = "",
        val name: String = "",
        val service: String = "",
        val favorited: Int = 0,
        @Serializable(with = FlexibleEpochSerializer::class)
        val updated: Long = 0,
    )

    @Serializable
    data class Post(
        @Serializable(with = FlexibleStringSerializer::class)
        val id: String = "",
        @Serializable(with = FlexibleStringSerializer::class)
        val user: String = "",
        val service: String = "",
        val title: String? = null,
        val substring: String? = null,
        val published: String? = null,
        val added: String? = null,
        val edited: String? = null,
        val content: String? = null,
        val file: MediaFile? = null,
        val attachments: List<MediaFile> = emptyList(),
        val tags: List<String>? = null,
    )

    @Serializable
    data class MediaFile(
        val name: String? = null,
        val path: String? = null,
        val server: String? = null,
        val extension: String? = null,
        @SerialName("name_extension") val nameExtension: String? = null,
        val index: Int? = null,
    )

    @Serializable
    data class PreviewFile(
        val type: String? = null,
        val server: String? = null,
        val name: String? = null,
        val path: String? = null,
    )

    @Serializable
    data class PostDetail(
        val post: Post? = null,
        val attachments: List<MediaFile>? = null,
        val previews: List<PreviewFile>? = null,
        val videos: List<MediaFile>? = null,
    )

    @Serializable
    data class Profile(
        @Serializable(with = FlexibleStringSerializer::class)
        val id: String = "",
        val name: String = "",
        val service: String = "",
        @SerialName("public_id") val publicId: String? = null,
        @SerialName("post_count") val postCount: Int? = null,
    )

    @Serializable
    data class PostSearchBody(
        val count: Int = 0,
        @SerialName("true_count") val trueCount: Long = 0,
        val posts: List<Post> = emptyList(),
    )

    @Serializable
    data class ApiErrorBody(val error: String? = null)

    object FlexibleStringSerializer : KSerializer<String> {
        override val descriptor = PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

        override fun deserialize(decoder: Decoder): String {
            val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
            return when (val element = jsonDecoder.decodeJsonElement()) {
                is JsonNull -> ""
                is JsonPrimitive -> element.content
                else -> ""
            }
        }
    }

    object FlexibleEpochSerializer : KSerializer<Long> {
        override val descriptor = PrimitiveSerialDescriptor("FlexibleEpoch", PrimitiveKind.LONG)

        override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)

        override fun deserialize(decoder: Decoder): Long {
            val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeLong()
            return when (val element = jsonDecoder.decodeJsonElement()) {
                is JsonNull -> 0L
                is JsonPrimitive -> epochSeconds(element.content)
                else -> 0L
            }
        }

        private fun epochSeconds(text: String): Long {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return 0L
            trimmed.toLongOrNull()?.let { return normalize(it) }
            trimmed.toDoubleOrNull()?.let { return normalize(it.toLong()) }
            return runCatching { LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC).epochSecond }.getOrDefault(0L)
        }

        private fun normalize(raw: Long): Long = if (raw > 10_000_000_000L) raw / 1000 else raw
    }
}
