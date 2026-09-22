package koharia.smanga

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import koharia.smanga.ui.mergeSmangaShelfManga
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class SmangaShelfMangaTest {
    @Test
    fun `remote shelf updates local display fields and keeps local state`() {
        val local = Manga.create().copy(
            id = 42,
            source = 99,
            favorite = true,
            lastUpdate = 11,
            nextUpdate = 12,
            fetchInterval = 13,
            dateAdded = 14,
            viewerFlags = 15,
            chapterFlags = 16,
            coverLastModified = 17,
            url = "local-url",
            title = "Old title",
            author = "Old author",
            description = "Old description",
            genre = listOf("old genre"),
            status = 18,
            thumbnailUrl = "old-thumbnail",
            updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE,
            initialized = true,
            lastModifiedAt = 19,
            favoriteModifiedAt = 20,
            version = 21,
            notes = "local notes",
            memo = buildJsonObject { put("local", true) },
        )
        val remote = Manga.create().copy(
            id = -8,
            source = 7,
            url = "remote-url",
            title = "New title",
            author = "New author",
            description = "New description",
            genre = listOf("new genre"),
            thumbnailUrl = "new-thumbnail",
        )

        val merged = mergeSmangaShelfManga(remote, local)

        assertEquals(
            local.copy(
                title = remote.title,
                author = remote.author,
                description = remote.description,
                genre = remote.genre,
                thumbnailUrl = remote.thumbnailUrl,
            ),
            merged,
        )
        assertNotSame(local, merged)
        assertEquals("New title", merged.title)
        assertEquals("New author", merged.author)
        assertEquals("New description", merged.description)
        assertEquals(listOf("new genre"), merged.genre)
        assertEquals("new-thumbnail", merged.thumbnailUrl)
        assertEquals(42, merged.id)
        assertEquals(99, merged.source)
        assertEquals("local-url", merged.url)
        assertEquals(true, merged.favorite)
        assertEquals(16, merged.chapterFlags)
        assertEquals(15, merged.viewerFlags)
        assertEquals(17, merged.coverLastModified)
        assertEquals(true, merged.initialized)
        assertEquals("local notes", merged.notes)
        assertEquals(buildJsonObject { put("local", true) }, merged.memo)
        assertEquals("Old title", local.title)
    }

    @Test
    fun `missing local manga keeps remote manga`() {
        val remote = Manga.create().copy(id = -8, title = "Remote title", url = "remote-url")

        assertEquals(remote, mergeSmangaShelfManga(remote, null))
    }
}
