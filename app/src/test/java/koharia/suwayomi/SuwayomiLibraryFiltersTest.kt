package koharia.suwayomi

import koharia.connection.ConnectionShelfFilterPersistence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuwayomiLibraryFiltersTest {
    private fun facts(
        title: String = "Series",
        author: String? = null,
        artist: String? = null,
        genres: List<String> = emptyList(),
        status: String = "ONGOING",
        unread: Int = 10,
        total: Int = 10,
        downloaded: Int = 0,
        bookmarked: Int = 0,
        hasProgress: Boolean = false,
    ) = SuwayomiLibraryFacts(
        title = title,
        author = author,
        artist = artist,
        genres = genres,
        status = status,
        unreadCount = unread,
        totalChapters = total,
        downloadedCount = downloaded,
        bookmarkedCount = bookmarked,
        hasProgress = hasProgress,
    )

    private fun matches(filters: SuwayomiLibraryFilters, facts: SuwayomiLibraryFacts) =
        suwayomiLibraryFiltersMatch(filters, facts)

    @Test
    fun `no filters keeps everything`() {
        assertTrue(matches(SuwayomiLibraryFilters.NONE, facts()))
        assertEquals(0, SuwayomiLibraryFilters.NONE.activeCount)
        assertFalse(SuwayomiLibraryFilters.NONE.active)
    }

    @Test
    fun `a condition includes exactly the series that satisfy it`() {
        val downloaded = facts(downloaded = 3)
        val remote = facts(downloaded = 0)

        val include = SuwayomiLibraryFilters(downloaded = SuwayomiTriState.INCLUDE)
        assertTrue(matches(include, downloaded))
        assertFalse(matches(include, remote))

        val exclude = SuwayomiLibraryFilters(downloaded = SuwayomiTriState.EXCLUDE)
        assertFalse(matches(exclude, downloaded))
        assertTrue(matches(exclude, remote))
    }

    @Test
    fun `unread, bookmarked, started and completed read their own signal`() {
        val unread = facts(unread = 4, total = 10)
        val finished = facts(unread = 0, total = 10, hasProgress = true)
        val bookmarked = facts(bookmarked = 2)
        val completed = facts(status = "COMPLETED")

        assertTrue(matches(SuwayomiLibraryFilters(unread = SuwayomiTriState.INCLUDE), unread))
        assertFalse(matches(SuwayomiLibraryFilters(unread = SuwayomiTriState.INCLUDE), finished))
        assertTrue(matches(SuwayomiLibraryFilters(started = SuwayomiTriState.INCLUDE), finished))
        assertFalse(matches(SuwayomiLibraryFilters(started = SuwayomiTriState.INCLUDE), unread))
        assertTrue(matches(SuwayomiLibraryFilters(bookmarked = SuwayomiTriState.INCLUDE), bookmarked))
        assertTrue(matches(SuwayomiLibraryFilters(completed = SuwayomiTriState.INCLUDE), completed))
        assertFalse(matches(SuwayomiLibraryFilters(completed = SuwayomiTriState.INCLUDE), unread))
    }

    @Test
    fun `conditions combine so one rejection removes the series`() {
        val filters = SuwayomiLibraryFilters(
            downloaded = SuwayomiTriState.INCLUDE,
            unread = SuwayomiTriState.EXCLUDE,
        )

        assertTrue(matches(filters, facts(downloaded = 1, unread = 0, hasProgress = true)))
        // Unread but downloaded still fails the unread condition.
        assertFalse(matches(filters, facts(downloaded = 1, unread = 5)))
        // Read but not downloaded fails the downloaded condition.
        assertFalse(matches(filters, facts(downloaded = 0, unread = 0, hasProgress = true)))
    }

    @Test
    fun `text narrowing is case insensitive and trims the query`() {
        val target = facts(title = "Blue Lock", author = "Muneyuki Kaneshiro", artist = "Yusuke Nomura")

        assertTrue(matches(SuwayomiLibraryFilters(title = "blue"), target))
        assertTrue(matches(SuwayomiLibraryFilters(title = "  LOCK "), target))
        assertTrue(matches(SuwayomiLibraryFilters(author = "kaneshiro"), target))
        assertTrue(matches(SuwayomiLibraryFilters(artist = "nomura"), target))
        assertFalse(matches(SuwayomiLibraryFilters(author = "someone else"), target))
        // A missing field never matches a constrained one.
        assertFalse(matches(SuwayomiLibraryFilters(author = "x"), facts(author = null)))
    }

    @Test
    fun `the chapter minimum keeps only long enough series`() {
        val filters = SuwayomiLibraryFilters(minimumChapters = 50)

        assertTrue(matches(filters, facts(total = 50)))
        assertTrue(matches(filters, facts(total = 120)))
        assertFalse(matches(filters, facts(total = 49)))
    }

    @Test
    fun `genres must all be present and are matched case insensitively`() {
        val target = facts(genres = listOf("Action", "Romance"))

        assertTrue(matches(SuwayomiLibraryFilters(genres = setOf("action")), target))
        assertTrue(matches(SuwayomiLibraryFilters(genres = setOf("action", "romance")), target))
        assertFalse(matches(SuwayomiLibraryFilters(genres = setOf("action", "horror")), target))
    }

    @Test
    fun `every narrowing setting is counted so the chip can report them`() {
        val filters = SuwayomiLibraryFilters(
            downloaded = SuwayomiTriState.INCLUDE,
            unread = SuwayomiTriState.EXCLUDE,
            completed = SuwayomiTriState.IGNORE,
            minimumChapters = 100,
            title = "a",
            author = "b",
            genres = setOf("action"),
        )

        // downloaded, unread, minChapters, title, author, genres
        assertEquals(6, filters.activeCount)
        assertTrue(filters.active)
    }

    @Test
    fun `tri-state accessors round trip through the condition keys`() {
        var filters = SuwayomiLibraryFilters.NONE
        SuwayomiLibraryFilter.entries.forEach { filters = filters.with(it, SuwayomiTriState.INCLUDE) }
        SuwayomiLibraryFilter.entries.forEach { key ->
            assertEquals(SuwayomiTriState.INCLUDE, filters.triStateOf(key), key.name)
        }

        filters = filters.with(SuwayomiLibraryFilter.UNREAD, SuwayomiTriState.EXCLUDE)
        assertEquals(SuwayomiTriState.EXCLUDE, filters.triStateOf(SuwayomiLibraryFilter.UNREAD))
        assertEquals(SuwayomiTriState.INCLUDE, filters.triStateOf(SuwayomiLibraryFilter.STARTED))
    }

    @Test
    fun `the codec round trips every field`() {
        val filters = SuwayomiLibraryFilters(
            downloaded = SuwayomiTriState.INCLUDE,
            unread = SuwayomiTriState.EXCLUDE,
            started = SuwayomiTriState.INCLUDE,
            bookmarked = SuwayomiTriState.EXCLUDE,
            completed = SuwayomiTriState.INCLUDE,
            minimumChapters = 50,
            title = "Blue",
            author = "Kane",
            artist = "Nomura",
            genres = setOf("action", "romance"),
        )

        assertEquals(filters, SuwayomiLibraryFiltersCodec.decode(SuwayomiLibraryFiltersCodec.encode(filters)))
    }

    @Test
    fun `the codec keeps separators inside user text and genres`() {
        val filters = SuwayomiLibraryFilters(
            title = "Foo; Bar",
            author = "A=B; C",
            artist = "50%25",
            genres = setOf("action", "sci-fi, fantasy"),
        )

        assertEquals(filters, SuwayomiLibraryFiltersCodec.decode(SuwayomiLibraryFiltersCodec.encode(filters)))
    }

    @Test
    fun `the codec tolerates missing, unknown and corrupt values`() {
        assertEquals(SuwayomiLibraryFilters.NONE, SuwayomiLibraryFiltersCodec.decode(""))
        // Unparseable entries and unknown keys are dropped rather than failing the shelf.
        assertEquals(SuwayomiLibraryFilters.NONE, SuwayomiLibraryFiltersCodec.decode("garbage;=;nope=x"))
        assertEquals(
            SuwayomiLibraryFilters(unread = SuwayomiTriState.INCLUDE),
            SuwayomiLibraryFiltersCodec.decode("unread=INCLUDE;futureKey=whatever"),
        )
        // A non-positive chapter minimum is meaningless and is dropped.
        assertEquals(
            SuwayomiLibraryFilters.NONE,
            SuwayomiLibraryFiltersCodec.decode("minChapters=0;minChapters=x"),
        )
    }

    @Test
    fun `shelf genres are normalised for the option list`() {
        assertEquals(
            listOf("action", "horror", "romance"),
            suwayomiShelfGenres(listOf("Romance", " action ", "", "ACTION", "Horror")),
        )
    }

    @Test
    fun `the persistence rule is shared with the other shelves`() {
        // The rule itself lives in ConnectionShelfFilterPersistence and is covered there; this
        // asserts the shelf wiring still routes through it.
        val stored = SuwayomiLibraryFilters(unread = SuwayomiTriState.INCLUDE, title = "Blue")
        assertTrue(ConnectionShelfFilterPersistence.shouldStore(true))
        assertFalse(ConnectionShelfFilterPersistence.shouldStore(false))
        assertTrue(ConnectionShelfFilterPersistence.shouldClear(false))
        assertFalse(ConnectionShelfFilterPersistence.shouldClear(true))
        assertEquals("author desc", ConnectionShelfFilterPersistence.initialOrder(true, "author desc", "title asc"))
        assertEquals("title asc", ConnectionShelfFilterPersistence.initialOrder(false, "author desc", "title asc"))
        assertEquals(stored, SuwayomiLibraryFiltersCodec.decode(SuwayomiLibraryFiltersCodec.encode(stored)))
    }

    @Test
    fun `the shelf payload exposes the facts the filters read`() {
        val manga = SuwayomiManga(
            id = 1,
            title = "Series",
            author = "Author",
            genre = listOf("Action"),
            status = "COMPLETED",
            unreadCount = 3,
            downloadCount = 2,
            bookmarkCount = 1,
            chapters = SuwayomiNodes(totalCount = 12),
            lastReadChapter = SuwayomiChapter(id = 9, mangaId = 1),
        )

        assertEquals(
            SuwayomiLibraryFacts(
                title = "Series",
                author = "Author",
                artist = null,
                genres = listOf("Action"),
                status = "COMPLETED",
                unreadCount = 3,
                totalChapters = 12,
                downloadedCount = 2,
                bookmarkedCount = 1,
                hasProgress = true,
            ),
            manga.libraryFacts,
        )
    }

    @Test
    fun `a series with no chapters fetched is not treated as started`() {
        val manga = SuwayomiManga(id = 1, title = "Series", chapters = SuwayomiNodes(totalCount = null))

        assertFalse(manga.libraryFacts.hasProgress)
        assertFalse(manga.libraryFacts.completed)
    }
}
