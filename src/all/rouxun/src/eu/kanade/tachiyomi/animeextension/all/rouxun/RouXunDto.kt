package eu.kanade.tachiyomi.animeextension.all.rouxun

import kotlinx.serialization.Serializable

@Serializable
internal data class VideoListResult(
    val videos: List<VideoDto> = emptyList(),
    val seriesList: List<SeriesDto> = emptyList(),
    val pageNum: Int = 1,
    val totalPage: Int = 1,
    val hotSearches: List<String>? = null,
) {
    val hasNextPage get() = pageNum < totalPage
}

@Serializable
internal data class VideoDetailResult(
    val video: VideoDto,
    val relatedVideos: List<VideoDto> = emptyList(),
    val series: SeriesDto? = null,
    val episodes: List<VideoDto>? = null,
    val ev: EncodedVideoUrl? = null,
)

/** `d` is base64; every char of the decoded text is shifted up by `k`. The result is JSON. */
@Serializable
internal data class EncodedVideoUrl(
    val d: String,
    val k: Int,
)

@Serializable
internal data class VideoUrls(
    val videoUrl: String,
)

@Serializable
internal data class VideoDto(
    val id: String,
    val vid: String? = null,
    val name: String,
    val nameZh: String? = null,
    val description: String? = null,
    val ref: String? = null,
    val tags: List<String> = emptyList(),
    val tagsZh: List<String>? = null,
    val publisher: String? = null,
    val createdAt: String? = null,
    val viewCount: Long = 0,
    val likeCount: Long? = null,
    val duration: Double? = null,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seriesNameZh: String? = null,
    val episode: Float? = null,
    val sources: List<SourceDto>? = null,
    val coverImageUrl: String? = null,
) {
    val maxResolution: Int? get() = sources?.mapNotNull { it.resolution }?.maxOrNull()
}

@Serializable
internal data class SourceDto(
    val resolution: Int? = null,
)

@Serializable
internal data class SeriesDto(
    val id: String,
    val name: String,
    val nameZh: String? = null,
    val status: String? = null,
    val episodeCount: Int? = null,
    val totalEpisodes: Int? = null,
    val lastEpisodeAt: String? = null,
    val viewCount: Long? = null,
    val likeCount: Long? = null,
    val tags: List<String>? = null,
    val createdAt: String? = null,
    val description: String? = null,
    val coverImageUrl: String? = null,
    val epLabel: String? = null,
)

@Serializable
internal data class SeriesListResult(
    val list: List<SeriesDto> = emptyList(),
    val pageNum: Int = 1,
    val totalPage: Int = 1,
) {
    val hasNextPage get() = pageNum < totalPage
}

@Serializable
internal data class SeriesDetailResult(
    val series: SeriesDto,
    val episodes: List<VideoDto> = emptyList(),
    val more: List<SeriesDto> = emptyList(),
)

@Serializable
internal data class HomeResult(
    val latestVideos: List<VideoDto> = emptyList(),
    val dailyHotCNAV: List<VideoDto> = emptyList(),
    val dailyHotSelfie: List<VideoDto> = emptyList(),
    val dailyHot91: List<VideoDto> = emptyList(),
    val dailyOnlyFans: List<VideoDto> = emptyList(),
    val dailyJV: List<VideoDto> = emptyList(),
    val hotCNAV: List<VideoDto> = emptyList(),
    val hotSelfie: List<VideoDto> = emptyList(),
    val hot91: List<VideoDto> = emptyList(),
) {
    fun allVideos(): List<VideoDto> = listOf(
        latestVideos, dailyHotCNAV, dailyHotSelfie, dailyHot91, dailyOnlyFans, dailyJV, hotCNAV, hotSelfie, hot91,
    ).flatten().distinctBy { it.id }
}

@Serializable
internal data class CatResult(
    val taxonomy: Taxonomy? = null,
)

@Serializable
internal data class Taxonomy(
    val cats: List<TagItem> = emptyList(),
    val genre: List<TagItem> = emptyList(),
    val byParent: Map<String, List<TagItem>> = emptyMap(),
)

@Serializable
internal data class TagItem(
    val id: String,
    val count: Int? = null,
)
