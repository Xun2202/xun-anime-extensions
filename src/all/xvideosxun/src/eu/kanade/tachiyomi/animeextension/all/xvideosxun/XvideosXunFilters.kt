package eu.kanade.tachiyomi.animeextension.all.xvideosxun

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * A select filter whose entries carry a URL/query value next to their display name.
 */
open class SelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selectedValue: String
        get() = options[state].second
}

class SortFilter :
    SelectFilter(
        "排序",
        listOf(
            "相关度" to "relevance",
            "上传日期" to "uploaddate",
            "评分" to "rating",
            "时长" to "length",
            "观看数" to "views",
            "随机" to "random",
        ),
    )

class DateFilter :
    SelectFilter(
        "上传时间",
        listOf(
            "全部" to "all",
            "近 3 天" to "today",
            "本周" to "week",
            "本月" to "month",
            "近 3 个月" to "3month",
            "近 6 个月" to "6month",
        ),
    )

class DurationFilter :
    SelectFilter(
        "时长",
        listOf(
            "全部" to "allduration",
            "1-3 分钟" to "1-3min",
            "3-10 分钟" to "3-10min",
            "10 分钟以上" to "10min_more",
            "10-20 分钟" to "10-20min",
            "20 分钟以上" to "20min_more",
        ),
    )

class QualityFilter :
    SelectFilter(
        "画质",
        listOf(
            "全部" to "all",
            "720p+" to "hd",
            "1080p+" to "1080P",
        ),
    )

class AccountFilter :
    SelectFilter(
        "我的账户（需先在 WebView 登录）",
        listOf(
            "不使用" to "",
            "我的收藏夹（列出全部列表）" to ACCOUNT_PLAYLISTS,
            "我喜欢的视频" to "videos-i-like",
            "稍后观看" to "watch-later",
            "观看历史" to "history",
        ),
    )

const val ACCOUNT_PLAYLISTS = "account/playlists"

/**
 * One of the logged-in user's favorites lists, expanded so that every video is its own entry
 * (long-press → bulk select → add to library gives one library entry per video).
 * [lists] is null until the background fetch finishes.
 * [statusMessage] is a finished failure ("未登录" or the error text), not the loading placeholder.
 */
class MyListVideosFilter(
    lists: List<Pair<String, Long>>?,
    loggedIn: Boolean,
    statusMessage: String? = null,
    listsPending: Boolean = false,
) : SelectFilter(
    "我的某个收藏夹 → 每个视频一个条目",
    buildList {
        add("不使用" to "")
        when {
            lists == null && !statusMessage.isNullOrBlank() -> add("（$statusMessage）" to "")
            lists == null && (listsPending || loggedIn) -> add("（正在加载收藏夹列表，稍等几秒后点「重置」）" to "")
            lists == null -> add("（需先在 WebView 登录）" to "")
            lists.isEmpty() -> add("（这个账户还没有收藏夹）" to "")
            else -> lists.forEach { (name, id) -> add(name to id.toString()) }
        }
    },
)

class FavoriteListFilter : AnimeFilter.Text("收藏夹 / 播放列表 URL 或 ID（填了再输搜索词 = 在该列表内搜索）")

class UploaderFilter : AnimeFilter.Text("频道 / 模特 用户名或 URL")

class TagFilter : AnimeFilter.Text("标签 (Tag)")

/**
 * "Best of" month picker; entries are generated for the last [BEST_MONTHS] months.
 */
class BestMonthFilter : SelectFilter("Best of 月份", bestMonthOptions()) {
    companion object {
        private const val BEST_MONTHS = 48

        private fun bestMonthOptions(): List<Pair<String, String>> {
            val fmt = SimpleDateFormat("yyyy-MM", Locale.US)
            val cal = Calendar.getInstance()
            return buildList {
                add("不使用" to "")
                repeat(BEST_MONTHS) {
                    val value = fmt.format(cal.time)
                    add(value to value)
                    cal.add(Calendar.MONTH, -1)
                }
            }
        }
    }
}

class CategoryFilter :
    SelectFilter(
        "分类",
        listOf(
            "不使用" to "",
            "AI" to "/c/AI-239",
            "Amateur" to "/c/Amateur-65",
            "Anal" to "/c/Anal-12",
            "Arab" to "/c/Arab-159",
            "Asian" to "/c/Asian_Woman-32",
            "ASMR" to "/c/ASMR-229",
            "Ass" to "/c/Ass-14",
            "BBW" to "/c/bbw-51",
            "Bi" to "/c/Bi_Sexual-62",
            "Big Ass" to "/c/Big_Ass-24",
            "Big Cock" to "/c/Big_Cock-34",
            "Big Tits" to "/c/Big_Tits-23",
            "Black" to "/c/Black_Woman-30",
            "Blonde" to "/c/Blonde-20",
            "Blowjob" to "/c/Blowjob-15",
            "Brunette" to "/c/Brunette-25",
            "Cam Porn" to "/c/Cam_Porn-58",
            "Creampie" to "/c/Creampie-40",
            "Cuckold/Hotwife" to "/c/Cuckold-237",
            "Cumshot" to "/c/Cumshot-18",
            "Femdom" to "/c/Femdom-235",
            "Fisting" to "/c/Fisting-165",
            "Fucked Up Family" to "/c/Fucked_Up_Family-81",
            "Gangbang" to "/c/Gangbang-69",
            "Gapes" to "/c/Gapes-167",
            "Indian" to "/c/Indian-89",
            "Interracial" to "/c/Interracial-27",
            "Latina" to "/c/Latina-16",
            "Lesbian" to "/c/Lesbian-26",
            "Lingerie" to "/c/Lingerie-83",
            "Mature" to "/c/Mature-38",
            "Milf" to "/c/Milf-19",
            "Oiled" to "/c/Oiled-22",
            "Redhead" to "/c/Redhead-31",
            "Solo" to "/c/Solo_and_Masturbation-33",
            "Squirting" to "/c/Squirting-56",
            "Stockings" to "/c/Stockings-28",
            "Teen" to "/c/Teen-13",
        ),
    )

fun buildFilterList(
    myLists: List<Pair<String, Long>>?,
    loggedIn: Boolean,
    myListsMessage: String? = null,
    listsPending: Boolean = false,
): AnimeFilterList = AnimeFilterList(
    AnimeFilter.Header("有搜索词时：下面四项作为搜索条件（选了 / 填了收藏夹则改为在该收藏夹内搜索）"),
    SortFilter(),
    DateFilter(),
    DurationFilter(),
    QualityFilter(),
    AnimeFilter.Separator(),
    AnimeFilter.Header("无搜索词时：按下面其中一项浏览（从上到下取第一个有值的）"),
    AccountFilter(),
    MyListVideosFilter(myLists, loggedIn, myListsMessage, listsPending),
    FavoriteListFilter(),
    UploaderFilter(),
    BestMonthFilter(),
    CategoryFilter(),
    TagFilter(),
)

inline fun <reified T> AnimeFilterList.firstOfType(): T? = firstOrNull { it is T } as? T
