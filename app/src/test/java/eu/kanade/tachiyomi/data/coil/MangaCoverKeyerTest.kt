package eu.kanade.tachiyomi.data.coil

import android.content.Context
import coil3.request.Options
import io.mockk.every
import io.mockk.mockk
import koharia.cover.CustomCoverStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover

class MangaCoverKeyerTest {
    @Test
    fun `manga and manga cover use the same cache identity`() {
        val customCovers = mockk<CustomCoverStore>()
        every { customCovers.cacheKey } returns "covers;0"
        val manga = Manga.create().copy(
            id = 7,
            source = 42,
            thumbnailUrl = "https://example.test/cover/7",
            coverLastModified = 123L,
        )
        val options = Options(mockk<Context>())

        assertEquals(
            MangaKeyer(customCovers).key(manga, options),
            MangaCoverKeyer(customCovers).key(manga.asMangaCover(), options),
        )
    }

    @Test
    fun `custom cover choice is part of the cache identity`() {
        val customCovers = mockk<CustomCoverStore>()
        every { customCovers.cacheKey } returns "covers;0"
        val options = Options(mockk<Context>())
        val keyer = MangaCoverKeyer(customCovers)
        val customCover = MangaCover(7, 42, false, "cover", 123L, useCustomCover = true)
        val sourceCover = customCover.copy(useCustomCover = false)

        assertNotEquals(keyer.key(customCover, options), keyer.key(sourceCover, options))
    }
}
