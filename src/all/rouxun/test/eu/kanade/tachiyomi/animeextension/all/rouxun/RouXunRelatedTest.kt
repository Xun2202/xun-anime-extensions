package eu.kanade.tachiyomi.animeextension.all.rouxun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouXunRelatedTest {
    @Test
    fun videoRelatedListKeepsTheShowAndDropsTheSharedRail() {
        val detail = VideoDetailResult(
            video = video("current", "current"),
            series = series("parent", "the show"),
            moreSeries = listOf(series("promo", "ai rail")),
            relatedVideos = listOf(video("a", "one"), video("b", "two")),
        )
        val ids = detail.relatedEntries().map { entry ->
            when (entry) {
                is RelatedEntry.Series -> entry.series.id
                is RelatedEntry.Video -> entry.video.id
            }
        }
        assertEquals(listOf("parent", "a", "b"), ids)
    }

    @Test
    fun seriesRelatedListDropsTheSharedRail() {
        val detail = SeriesDetailResult(
            series = series("self", "show"),
            episodes = listOf(video("ep", "episode")),
            more = listOf(series("promo", "ai rail")),
        )
        assertTrue(detail.relatedEntries().isEmpty())
    }

    private fun video(id: String, name: String) = VideoDto(id = id, name = name)

    private fun series(id: String, name: String) = SeriesDto(id = id, name = name)
}
