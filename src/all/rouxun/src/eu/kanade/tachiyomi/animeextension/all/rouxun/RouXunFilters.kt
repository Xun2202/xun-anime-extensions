package eu.kanade.tachiyomi.animeextension.all.rouxun

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

/**
 * A select filter whose entries carry a request value next to their display name.
 */
internal open class SelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selectedValue: String
        get() = options.getOrNull(state)?.second ?: options.first().second

    val isDefault: Boolean
        get() = state == 0
}

internal class SortFilter :
    SelectFilter(
        "排序",
        listOf(
            "默认（浏览 = 最新发布，搜索 = 相关度）" to "",
            "最新发布" to "createdAt",
            "最多观看" to "viewCount",
            "最多喜欢" to "likeCount",
        ),
    )

internal class BrowseFilter :
    SelectFilter(
        "浏览方式（无搜索词时）",
        listOf(
            "全部视频（可配合分类 / 标签）" to BROWSE_VIDEOS,
            "首页精选（今日 / 本周热门）" to BROWSE_HOME,
            "大家正在看" to BROWSE_WATCHING,
            "剧集库（每部剧集一个条目，多集）" to BROWSE_SERIES,
        ),
    ) {
    companion object {
        const val BROWSE_VIDEOS = "videos"
        const val BROWSE_HOME = "home"
        const val BROWSE_WATCHING = "watching"
        const val BROWSE_SERIES = "series"
    }
}

internal class CategoryFilter(categories: List<String>) :
    SelectFilter(
        "分类",
        listOf("不限" to "") + categories.map { it to it },
    )

internal class TagFilter(tags: List<String>) :
    SelectFilter(
        "标签（优先于分类；搜索时也生效）",
        listOf("不限" to "") + tags.map { it to it },
    )

internal class HotSearchFilter(keywords: List<String>) :
    SelectFilter(
        "热门搜索（无搜索词时作为关键词）",
        if (keywords.isEmpty()) {
            listOf("<先搜索一次再重置筛选以加载>" to "")
        } else {
            listOf("不限" to "") + keywords.map { it to it }
        },
    )

internal class SeriesStatusFilter :
    SelectFilter(
        "剧集状态（仅剧集库）",
        listOf(
            "全部状态" to "all",
            "连载中" to "ongoing",
            "已完结" to "completed",
            "本周更新" to "fresh",
        ),
    )

internal class SeriesSortFilter :
    SelectFilter(
        "剧集排序（仅剧集库）",
        listOf(
            "最新更新" to "updated",
            "最多观看" to "hot",
            "最多喜欢" to "likes",
            "集数最多" to "most",
        ),
    )
