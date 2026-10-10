package koharia.source.local

import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class LocalLibraryEntryStateTest {
    private val manga = Manga.create()
    private val unread = LocalLibraryEntryState(true, false, false, true, emptySet(), MangaReadProgress(0, 1))

    @Test
    fun `rounded display progress cannot hide an unread book`() {
        val chapters = listOf(Chapter.create().copy(lastPageRead = 99))
        assertTrue(localLibraryEntryIsUnread(1, chapters, true, 0.999))
        assertTrue(localLibraryEntryIsUnread(1, chapters, true, null))
        assertFalse(localLibraryEntryIsUnread(1, chapters.map { it.copy(read = true) }, true, null))
        assertFalse(localLibraryEntryIsUnread(1, chapters, true, 1.0))
    }

    @Test
    fun `each condition supports include exclude and ignore`() {
        val conditions = listOf(
            LocalLibraryFilters(downloaded = TriState.ENABLED_IS) to
                LocalLibraryFilters(downloaded = TriState.ENABLED_NOT),
            LocalLibraryFilters(unread = TriState.ENABLED_IS) to LocalLibraryFilters(unread = TriState.ENABLED_NOT),
            LocalLibraryFilters(started = TriState.ENABLED_IS) to LocalLibraryFilters(started = TriState.ENABLED_NOT),
            LocalLibraryFilters(bookmarked = TriState.ENABLED_IS) to
                LocalLibraryFilters(bookmarked = TriState.ENABLED_NOT),
        )
        val matching = unread.copy(started = true, bookmarked = true)
        val nonmatching = unread.copy(unread = false, started = false, bookmarked = false, available = false)
        conditions.forEach { (include, exclude) ->
            assertTrue(include.matches(manga, matching))
            assertFalse(include.matches(manga, nonmatching))
            assertFalse(exclude.matches(manga, matching))
            assertTrue(exclude.matches(manga, nonmatching))
        }
        assertTrue(LocalLibraryFilters().matches(manga, null))
    }

    @Test
    fun `completed means publication status rather than reading completion`() {
        val filters = LocalLibraryFilters(completed = TriState.ENABLED_IS)
        assertTrue(filters.matches(manga.copy(status = SManga.COMPLETED.toLong()), unread))
        assertFalse(filters.matches(manga.copy(status = SManga.ONGOING.toLong()), unread.copy(unread = false)))
    }

    @Test
    fun `scanlator exclusion keeps untagged and partially matching entries`() {
        val filters = LocalLibraryFilters(excludedScanlators = setOf("A"))
        assertFalse(filters.matches(manga, unread.copy(scanlators = setOf("A"))))
        assertTrue(filters.matches(manga, unread.copy(scanlators = setOf("A", "B"))))
        assertTrue(filters.matches(manga, unread))
    }

    @Test
    fun `folder conditions reflect all descendants including partial progress and bookmarks`() {
        val descendants = listOf(unread, unread.copy(unread = false, started = true, bookmarked = true))
        val folder = aggregateLocalFolderEntryState(2, descendants, MangaReadProgress(1, 2))
        assertTrue(folder.unread)
        assertTrue(folder.started)
        assertTrue(folder.bookmarked)
        assertTrue(folder.available)
        assertFalse(aggregateLocalFolderEntryState(2, descendants.map { it.copy(unread = false) }, null).unread)
        assertTrue(aggregateLocalFolderEntryState(3, descendants.map { it.copy(unread = false) }, null).unread)
    }

    @Test
    fun `empty folder has no readable or downloaded descendants`() {
        val empty = aggregateLocalFolderEntryState(0, emptyList(), null)
        assertFalse(empty.unread)
        assertFalse(empty.available)
        assertFalse(empty.started)
        assertFalse(empty.bookmarked)
    }
}
