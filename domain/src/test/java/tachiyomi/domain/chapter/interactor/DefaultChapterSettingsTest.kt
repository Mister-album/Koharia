package tachiyomi.domain.chapter.interactor

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.ChapterDisplayOption
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

class DefaultChapterSettingsTest {
    @Test
    fun `existing list and previously copied defaults follow the current default`() {
        val preferences = LibraryPreferences(InMemoryPreferenceStore())
        val existingList = Manga.create()
        val copiedDefault = existingList.copy(chapterFlags = Manga.CHAPTER_COVER_DISPLAY_COMFORTABLE)
        preferences.chapterCoverDisplayMode.set(Manga.CHAPTER_COVER_DISPLAY_COVER_AND_TITLE)
        for (manga in listOf(existingList, copiedDefault)) {
            assertEquals(
                Manga.CHAPTER_COVER_DISPLAY_COVER_AND_TITLE,
                manga.withChapterCoverDisplayMode(preferences.chapterCoverDisplayMode.get()).chapterCoverDisplayMode,
            )
        }
        preferences.chapterCoverDisplayMode.set(Manga.CHAPTER_COVER_DISPLAY_COVER)
        assertEquals(
            Manga.CHAPTER_COVER_DISPLAY_COVER,
            existingList.withChapterCoverDisplayMode(
                preferences.chapterCoverDisplayMode.get(),
            ).chapterCoverDisplayMode,
        )
        preferences.setChapterSettingsDefault(existingList)
        assertEquals(Manga.CHAPTER_COVER_DISPLAY_COVER, preferences.chapterCoverDisplayMode.get())
    }

    @Test
    fun `stored series modes and former override bits never override shared mode`() {
        val preferences = LibraryPreferences(InMemoryPreferenceStore())
        preferences.chapterCoverDisplayMode.set(Manga.CHAPTER_COVER_DISPLAY_COVER_AND_TITLE)
        for (storedMode in listOf(Manga.CHAPTER_COVER_DISPLAY_TEXT, Manga.CHAPTER_COVER_DISPLAY_COMFORTABLE)) {
            val manga = Manga.create().copy(chapterFlags = storedMode or 0x40000000L or Manga.CHAPTER_SORT_ASC)
            val displayed = manga.withChapterCoverDisplayMode(preferences.chapterCoverDisplayMode.get())
            assertEquals(Manga.CHAPTER_COVER_DISPLAY_COVER_AND_TITLE, displayed.chapterCoverDisplayMode)
            assertEquals(Manga.CHAPTER_SORT_ASC, displayed.chapterFlags and Manga.CHAPTER_SORT_DIR_MASK)
            preferences.setChapterSettingsDefault(manga)
            assertEquals(Manga.CHAPTER_COVER_DISPLAY_COVER_AND_TITLE, preferences.chapterCoverDisplayMode.get())
        }
        assertEquals(0L, preferences.defaultChapterFlags() and Manga.CHAPTER_COVER_DISPLAY_MASK)
    }

    @Test
    fun `bulk defaults include managed unfavorited entries once and clear overrides`() = runTest {
        val repository = mockk<MangaRepository>()
        val preferences = LibraryPreferences(InMemoryPreferenceStore())
        preferences.chapterCoverDisplayMode.set(Manga.CHAPTER_COVER_DISPLAY_COMFORTABLE)
        preferences.sortChapterByAscendingOrDescending.set(Manga.CHAPTER_SORT_ASC)
        val favorite = Manga.create().copy(id = 1, favorite = true)
        val local = Manga.create().copy(id = 2, chapterFlags = ChapterDisplayOption.READ_PROGRESS.set(0, false))
        coEvery { repository.getFavorites() } returns listOf(favorite)
        coEvery { repository.update(any()) } returns true
        val defaults =
            SetMangaDefaultChapterFlags(preferences, SetMangaChapterFlags(repository), GetFavorites(repository))

        defaults.awaitAll(listOf(favorite, local))

        for (id in listOf(1L, 2L)) {
            coVerify(exactly = 1) {
                repository.update(match { it.id == id && it.chapterFlags == preferences.defaultChapterFlags() })
            }
        }
        coVerify(exactly = 2) { repository.update(any()) }
    }

    @Test
    fun `series overrides preserve flags and survive the backup Int round trip`() {
        val base = Manga.CHAPTER_COVER_DISPLAY_COMFORTABLE or Manga.CHAPTER_SORT_ASC or Manga.CHAPTER_DISPLAY_FILE_NAME
        var flags = base
        flags = ChapterDisplayOption.READ_PROGRESS.set(flags, false)
        flags = ChapterDisplayOption.FILE_SIZE.set(flags, true)
        flags = ChapterDisplayOption.HIDE_MISSING.set(flags, false)
        flags = flags.toInt().toLong()
        assertEquals(base, flags and 0x00ffffffL)
        assertFalse(ChapterDisplayOption.READ_PROGRESS.get(flags, true))
        assertTrue(ChapterDisplayOption.FILE_SIZE.get(flags, false))
        assertFalse(ChapterDisplayOption.HIDE_MISSING.get(flags, true))
        flags = ChapterDisplayOption.READ_PROGRESS.set(flags, null)
        assertTrue(ChapterDisplayOption.READ_PROGRESS.get(flags, true))
        assertFalse(ChapterDisplayOption.READ_PROGRESS.get(flags, false))
        assertTrue(ChapterDisplayOption.FILE_SIZE.get(flags, false))
    }
}
