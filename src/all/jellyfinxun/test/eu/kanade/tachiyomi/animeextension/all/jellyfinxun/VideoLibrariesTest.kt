package eu.kanade.tachiyomi.animeextension.all.jellyfinxun

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoLibrariesTest {
    @Test
    fun keepsTheServerNamesAndHidesVirtualPlaylistsByType() {
        val response = JellyfinJson.parse(
            """
            {"Items":[
              {"Id":"movies", "Name":"My movies", "Type":"CollectionFolder", "CollectionType":"movies"},
              {"Id":"actual-library", "Name":"Playlists", "Type":"CollectionFolder", "CollectionType":"homevideos"},
              {"Id":"virtual-folder", "Name":"Playlists", "Type":"ManualPlaylistsFolder", "CollectionType":"playlists"},
              {"Id":"renamed-system-view", "Name":"My queue", "Type":"CollectionFolder", "CollectionType":"PLAYLISTS"},
              {"Id":"album", "Name":"Music", "CollectionType":"music"}
            ]}
            """.trimIndent(),
        )

        val libraries = JellyfinJson.videoLibraries(response)
        assertEquals(listOf("movies", "actual-library"), libraries.map { it.text("Id") })
        assertEquals(listOf("My movies", "Playlists"), libraries.map { it.text("Name") })
    }

    @Test
    fun filtersPreviouslyCachedPlaylistFoldersWithMissingCollectionType() {
        val cache = JellyfinJson.parse(
            """
            {"Items":[
              {"Id":"library", "Name":"Mixed content"},
              {"Id":"single-playlist", "Name":"A list", "Type":"Playlist"},
              {"Id":"playlist-folder", "Name":"Lists", "Type":"PlaylistsFolder"},
              {"Id":"legacy-playlist", "Name":"Queue", "Type":"ManualPlaylistsFolder"}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("library"), JellyfinJson.videoLibraries(cache).map { it.text("Id") })
    }
}
