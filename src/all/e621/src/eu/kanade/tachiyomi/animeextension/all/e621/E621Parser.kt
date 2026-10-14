package eu.kanade.tachiyomi.animeextension.all.e621

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/**
 * e621.net posts via the public JSON API. No Android imports, so unit tests can call it directly.
 *
 * Video listings always append [VIDEO_TYPE_TAGS]. Checked against the live API on 2026-10-14:
 * `type:webm` is webm only (9 of the 12 `vicineko` posts), `type:mp4` is the other 3, and
 * `~type:webm ~type:mp4` is the OR of those metatags (all 12, and a query with only the OR
 * returned webm/mp4 rather than images). `sample.alternates` on that response is
 * `original` / `variants` / `samples` (`480p`, `720p`), not the older `urls` array.
 *
 * `sound_warning` and `third-party_edit` are category 1 on e621 but they are warnings, not
 * creators. [creators] drops them so tapping the author searches the person.
 */
object E621Parser {
    const val USER_AGENT = "Anikku-e621-extension/1.0 (by Xun2202 on github)"

    const val SOURCE_NAME = "e621"
    const val SOURCE_LANG = "all"

    /** Library default. Do not override versionId, or the source id changes. */
    const val SOURCE_VERSION_ID = 1

    const val LISTING_PAGE_SIZE = 50
    const val COLLECTION_PAGE_SIZE = 320
    const val MAX_COLLECTION_PAGES = 10
    const val GENRE_LIMIT = 10
    const val TITLE_LIMIT = 100
    const val ARTIST_CATEGORY = 1

    const val VIDEO_TYPE_TAGS = "~type:webm ~type:mp4"

    const val QUALITY_BEST = "最高"
    const val QUALITY_720 = "720p"
    const val QUALITY_480 = "480p"
    val QUALITY_OPTIONS = arrayOf(QUALITY_BEST, QUALITY_720, QUALITY_480)

    private const val COLLECTION_PREFIX = "/collection/artist/"

    /** Artist-category tags that are site warnings, not people. */
    private val utilityArtistTags = setOf("sound_warning", "third-party_edit")

    private val artistToken = Regex("""[a-z0-9][a-z0-9_().-]{0,80}""")
    private val explicitArtist = Regex("""^artist:(\S+)$""", RegexOption.IGNORE_CASE)
    private val sectionTitle = Regex("""\[section(?:\s*,[^\]]*)?=([^\]]+)]""", RegexOption.IGNORE_CASE)
    private val bbcode = Regex("""\[/?[a-zA-Z][^\]]*\]""")
    private val headerPrefix = Regex("""^h[1-6]\.\s*""")
    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp")

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun popularTags(): String = searchTags("", "score", "")

    fun latestTags(): String = searchTags("", "", "")

    /** `query` + optional extra tags + the video-type OR + at most one order metatag. */
    fun searchTags(query: String, orderKey: String, extra: String): String {
        val parts = mutableListOf<String>()
        query.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
        extra.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
        parts += VIDEO_TYPE_TAGS
        orderTag(orderKey).takeIf { it.isNotEmpty() }?.let(parts::add)
        return parts.joinToString(" ")
    }

    fun collectionTags(artist: String): String = searchTags(artist, "", "")

    fun orderTag(orderKey: String): String = when (orderKey) {
        "score" -> "order:score"
        "favcount" -> "order:favcount"
        "id", "latest" -> "order:id"
        else -> ""
    }

    /**
     * A single token that might be an artist, or the whole query `artist:<tag>`.
     * Anything with spaces is a normal tag search and does not ask tags.json.
     */
    fun artistCandidate(query: String): String? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null
        explicitArtist.matchEntire(trimmed)?.let { match ->
            val tag = match.groupValues[1].trim().lowercase()
            if (tag.matches(artistToken)) return tag
            return null
        }
        val lower = trimmed.lowercase()
        if (lower.matches(artistToken)) return lower
        return null
    }

    fun exactArtist(body: String, wanted: String): String? {
        val needle = wanted.trim().lowercase()
        if (needle.isEmpty()) return null
        return parseTags(body).firstOrNull { tag ->
            tag.category == ARTIST_CATEGORY && tag.name.equals(needle, ignoreCase = true)
        }?.name
    }

    fun parseTags(body: String): List<TagRecord> {
        val element = json.parseToJsonElement(body)
        if (element !is JsonArray) return emptyList()
        return json.decodeFromJsonElement(ListSerializer(TagRecord.serializer()), element)
    }

    fun parsePosts(body: String): List<Post> {
        if (body.isBlank()) return emptyList()
        val element = json.parseToJsonElement(body)
        if (element is JsonObject && "posts" in element) {
            return json.decodeFromJsonElement(PostsBody.serializer(), element).posts
        }
        if (element is JsonObject && "id" in element) {
            return listOf(json.decodeFromJsonElement(Post.serializer(), element))
        }
        if (element is JsonArray) {
            return json.decodeFromJsonElement(ListSerializer(Post.serializer()), element)
        }
        if (element is JsonObject) {
            val success = element["success"]?.jsonPrimitive?.content
            if (success == "false") {
                val message = element["message"]?.jsonPrimitive?.content ?: "e621 请求失败"
                throw IllegalStateException(message)
            }
        }
        return emptyList()
    }

    fun parsePostOrNull(body: String): Post? = parsePosts(body).firstOrNull()

    fun listing(body: String, limit: Int): Listing {
        val raw = parsePosts(body)
        return Listing(
            posts = raw.filter { isPlayable(it) },
            hasNextPage = raw.size >= limit,
        )
    }

    fun isPlayable(post: Post): Boolean = !post.file?.url.isNullOrBlank()

    fun creators(post: Post): List<String> {
        val artists = post.tags?.artist.orEmpty()
        val people = artists.filter { it !in utilityArtistTags }
        return people.ifEmpty { artists }
    }

    fun titleOf(post: Post): String {
        val description = post.description.orEmpty()
        val section = sectionTitle.find(description)?.groupValues?.get(1)?.let(::cleanLine)
        val readable = section?.takeIf { it.isNotEmpty() } ?: firstReadableLine(description)
        if (!readable.isNullOrEmpty()) return truncate(readable)
        val who = creators(post).joinToString(" ").ifBlank { "e621" }
        return "$who #${post.id}"
    }

    fun durationSeconds(post: Post): Int? {
        val value = post.duration ?: return null
        if (value <= 0.0) return null
        return value.roundToInt()
    }

    fun formatDuration(seconds: Double?): String {
        if (seconds == null || seconds <= 0.0) return ""
        val total = seconds.roundToInt()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
    }

    fun episodeName(post: Post): String {
        val title = titleOf(post)
        val duration = formatDuration(post.duration)
        return if (duration.isEmpty()) title else "$title · $duration"
    }

    fun thumbnailOf(post: Post): String? {
        val preview = post.preview?.url
        if (!preview.isNullOrBlank()) return preview
        val sample = post.sample?.url
        if (!sample.isNullOrBlank() && extensionOf(sample) in imageExt) return sample
        return null
    }

    fun descriptionOf(post: Post): String = buildString {
        val bits = mutableListOf<String>()
        formatDuration(post.duration).takeIf { it.isNotEmpty() }?.let { bits += "时长 $it" }
        post.score?.total?.let { bits += "评分 $it" }
        post.favCount?.let { bits += "收藏 $it" }
        if (bits.isNotEmpty()) append(bits.joinToString(" · "))
        val body = post.description?.trim().orEmpty()
        if (body.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append(body)
        }
    }

    fun genresOf(post: Post): String = post.tags?.general.orEmpty().take(GENRE_LIMIT).joinToString(", ")

    fun entryFromPost(post: Post): Entry? {
        if (!isPlayable(post)) return null
        return Entry(
            path = "/posts/${post.id}.json",
            title = titleOf(post),
            author = creators(post).joinToString(", "),
            description = descriptionOf(post),
            genres = genresOf(post),
            thumbnail = thumbnailOf(post),
            collection = false,
        )
    }

    fun collectionPath(tag: String): String {
        val encoded = URLEncoder.encode(tag, Charsets.UTF_8.name()).replace("+", "%20")
        return "$COLLECTION_PREFIX$encoded"
    }

    fun artistFromCollection(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#')
        val marker = path.indexOf(COLLECTION_PREFIX)
        if (marker < 0) return null
        val raw = path.substring(marker + COLLECTION_PREFIX.length).trim('/')
        if (raw.isEmpty() || raw.contains('/')) return null
        return URLDecoder.decode(raw, Charsets.UTF_8.name()).takeIf { it.isNotBlank() }
    }

    fun isCollection(url: String): Boolean = artistFromCollection(url) != null

    fun collectionPlaceholder(tag: String, thumbnail: String?): Entry = Entry(
        path = collectionPath(tag),
        title = "作者合集：$tag",
        author = tag,
        description = "作者合集",
        genres = "",
        thumbnail = thumbnail,
        collection = true,
    )

    fun collectionEntry(tag: String, posts: List<Post>): Entry {
        val playable = posts.filter { isPlayable(it) }
        val thumbnail = playable.maxByOrNull { createdMillis(it) }?.let(::thumbnailOf)
        return collectionPlaceholder(tag, thumbnail).copy(
            description = "作者合集：${playable.size} 个视频",
        )
    }

    /**
     * Follow pages until a short page, an empty page, or [maxPages].
     * A page that repeats ids already seen also stops the walk.
     */
    suspend fun collectPages(
        pageSize: Int = COLLECTION_PAGE_SIZE,
        maxPages: Int = MAX_COLLECTION_PAGES,
        fetch: suspend (page: Int) -> List<Post>,
    ): List<Post> {
        val all = mutableListOf<Post>()
        val seen = HashSet<Long>()
        for (page in 1..maxPages) {
            val batch = fetch(page)
            var fresh = 0
            for (post in batch) {
                if (seen.add(post.id)) {
                    all += post
                    fresh++
                }
            }
            if (batch.size < pageSize || fresh == 0) break
        }
        return all
    }

    /** Oldest is episode 1 so a newly uploaded post becomes the next episode. Newest is listed first. */
    fun collectionEpisodes(posts: List<Post>): List<EpisodeDraft> {
        val playable = posts.filter { isPlayable(it) }.distinctBy { it.id }.sortedBy { createdMillis(it) }
        return playable.mapIndexed { index, post ->
            EpisodeDraft(
                path = "/posts/${post.id}.json",
                name = episodeName(post),
                episodeNumber = (index + 1).toFloat(),
                dateUpload = createdMillis(post),
                preview = thumbnailOf(post),
            )
        }.asReversed()
    }

    fun singleEpisode(post: Post): EpisodeDraft = EpisodeDraft(
        path = "/posts/${post.id}.json",
        name = episodeName(post),
        episodeNumber = 1f,
        dateUpload = createdMillis(post),
        preview = thumbnailOf(post),
    )

    fun createdMillis(post: Post): Long {
        val text = post.createdAt ?: return 0L
        return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrDefault(0L)
    }

    fun videoLinks(post: Post): List<VideoLink> {
        val originalUrl = post.file?.url
        if (originalUrl.isNullOrBlank()) return emptyList()
        val links = mutableListOf<VideoLink>()
        val seen = HashSet<String>()
        fun add(url: String?, resolution: Int, label: String) {
            if (url.isNullOrBlank() || resolution <= 0) return
            if (!seen.add(url)) return
            links += VideoLink(url = url, label = label, resolution = resolution)
        }
        val height = post.file.height
        add(originalUrl, height, "原始 ${height}p")
        val alternates = post.sample?.alternates
        alternates?.original?.let { add(it.url, it.height, "原始 ${it.height}p") }
        alternates?.variants.orEmpty().forEach { (name, variant) ->
            add(variant.url, variant.height, "${name.uppercase()} ${variant.height}p")
        }
        alternates?.samples.orEmpty().forEach { (name, variant) ->
            val tier = name.substringBefore('p').toIntOrNull() ?: variant.height
            val label = if (name.endsWith("p", ignoreCase = true)) name else "${variant.height}p"
            add(variant.url, tier, label)
        }
        return links
    }

    /** Highest resolution first. "720p" / "480" moves that tier to the front when it exists. */
    fun sortQualities(links: List<VideoLink>, preferred: String): List<VideoLink> {
        val ranked = links.sortedByDescending { it.resolution }
        val want = preferred.substringBefore('p').toIntOrNull() ?: return ranked
        return ranked.sortedByDescending { it.resolution == want }
    }

    private fun firstReadableLine(description: String): String? {
        for (raw in description.lineSequence()) {
            val stripped = cleanLine(raw)
            if (stripped.isNotEmpty()) return stripped
        }
        return null
    }

    private fun cleanLine(raw: String): String = raw
        .replace(bbcode, " ")
        .replace(headerPrefix, "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun truncate(text: String): String {
        if (text.length <= TITLE_LIMIT) return text
        return text.take(TITLE_LIMIT - 1).trimEnd() + "…"
    }

    private fun extensionOf(url: String): String = url.substringBefore('?').substringAfterLast('.').lowercase()

    data class Listing(val posts: List<Post>, val hasNextPage: Boolean)

    data class Entry(
        val path: String,
        val title: String,
        val author: String,
        val description: String,
        val genres: String,
        val thumbnail: String?,
        val collection: Boolean,
    )

    data class EpisodeDraft(
        val path: String,
        val name: String,
        val episodeNumber: Float,
        val dateUpload: Long,
        val preview: String?,
    )

    data class VideoLink(val url: String, val label: String, val resolution: Int)

    @Serializable
    data class Post(
        val id: Long,
        @SerialName("created_at") val createdAt: String? = null,
        val description: String? = null,
        val duration: Double? = null,
        @SerialName("fav_count") val favCount: Int? = null,
        val file: FileInfo? = null,
        val preview: ImageInfo? = null,
        val sample: SampleInfo? = null,
        val tags: TagGroups? = null,
        val score: ScoreInfo? = null,
    )

    @Serializable
    data class FileInfo(
        val width: Int = 0,
        val height: Int = 0,
        val ext: String? = null,
        val url: String? = null,
    )

    @Serializable
    data class ImageInfo(
        val url: String? = null,
        val width: Int = 0,
        val height: Int = 0,
    )

    @Serializable
    data class SampleInfo(
        val has: Boolean = false,
        val url: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val alternates: Alternates? = null,
    )

    @Serializable
    data class Alternates(
        val original: MediaVariant? = null,
        val variants: Map<String, MediaVariant> = emptyMap(),
        val samples: Map<String, MediaVariant> = emptyMap(),
    )

    @Serializable
    data class MediaVariant(
        val width: Int = 0,
        val height: Int = 0,
        val url: String? = null,
    )

    @Serializable
    data class TagGroups(
        val general: List<String> = emptyList(),
        val artist: List<String> = emptyList(),
    )

    @Serializable
    data class ScoreInfo(val total: Int = 0)

    @Serializable
    data class TagRecord(
        val id: Long = 0,
        val name: String = "",
        val category: Int = -1,
        @SerialName("post_count") val postCount: Int = 0,
    )

    @Serializable
    private data class PostsBody(val posts: List<Post> = emptyList())
}
