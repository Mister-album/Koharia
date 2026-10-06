package koharia.connection

import eu.kanade.tachiyomi.data.download.DownloadManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

class ConnectionShelfEntryStateTest {
    @Test
    fun `a live observer follows downloads and switches accounts without old chapter emissions`() = runBlocking {
        val old = Manga.create().copy(id = 10, source = 42, url = "old/series", title = "Same title")
        val current = old.copy(id = 20, url = "current/series")
        val visible = MutableStateFlow(setOf(old.url))
        val oldUnits =
            MutableStateFlow(listOf(Chapter.create().copy(id = 11, mangaId = 10, url = "old/chapter", name = "One")))
        val currentUnits =
            MutableStateFlow(
                listOf(Chapter.create().copy(id = 21, mangaId = 20, url = "current/chapter", name = "One")),
            )
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val files = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val mangas = mockk<MangaRepository> {
            every { getMangaBySourceIdAsFlow(42) } returns MutableStateFlow(listOf(old, current))
        }
        val chapters = mockk<ChapterRepository> {
            coEvery { getChapterByMangaIdAsFlow(10, false) } returns oldUnits
            coEvery { getChapterByMangaIdAsFlow(20, false) } returns currentUnits
        }
        val downloads = mockk<DownloadManager> {
            every { cacheChanges } returns events
            every { isChapterDownloaded(any(), any(), any(), any(), any()) } answers { thirdArg<String>() in files }
        }
        val results = Channel<Map<String, ConnectionShelfEntryState>>(Channel.UNLIMITED)
        val job = launch {
            ConnectionShelfEntryObserver(42, mangas, chapters, downloads).observe(visible).collect { results.send(it) }
        }
        try {
            withTimeout(5_000) {
                assertEquals(MangaDownloadStatus.NONE, results.receive().getValue(old.url).downloads().status)
                files.add("old/chapter")
                events.emit(Unit)
                assertEquals(MangaDownloadStatus.COMPLETE, results.receive().getValue(old.url).downloads().status)
                files.clear()
                events.emit(Unit)
                assertEquals(MangaDownloadStatus.NONE, results.receive().getValue(old.url).downloads().status)
                visible.value = setOf(current.url)
                assertEquals(setOf(current.url), results.receive().keys)
                oldUnits.value = oldUnits.value.map { it.copy(read = true) }
                currentUnits.value = currentUnits.value.map { it.copy(read = true) }
                val updated = results.receive()
                assertEquals(setOf(current.url), updated.keys)
                assertEquals(1L, updated.getValue(current.url).read)
            }
        } finally {
            job.cancel()
            results.close()
        }
    }

    @Test
    fun `download state distinguishes none partial complete and unknown without a reading toggle`() {
        assertEquals(MangaDownloadStatus.NONE, MangaDownloadState(0, 4).status)
        assertEquals(MangaDownloadStatus.PARTIAL, MangaDownloadState(2, 4).status)
        assertEquals(MangaDownloadStatus.COMPLETE, MangaDownloadState(4, 4).status)
        assertEquals(MangaDownloadStatus.UNKNOWN_TOTAL, MangaDownloadState(4, null).status)
        assertEquals(MangaDownloadStatus.UNKNOWN_TOTAL, MangaDownloadState(4, 0).status)
        assertEquals(MangaDownloadStatus.PARTIAL, ConnectionShelfEntryState(4, 4, 0).downloads(5).status)
        assertEquals(MangaDownloadStatus.UNKNOWN_TOTAL, ConnectionShelfEntryState(4, 4, 0).downloads(3).status)
    }

    @Test
    fun `visible saved entries observe completion deletion reading and account isolation`() = runTest {
        val manga = Manga.create().copy(id = 2, source = 42, url = "current-account/manga/2", title = "Fixture")
        val other = manga.copy(id = 3, url = "old-account/manga/2")
        val visible = MutableStateFlow(setOf(manga.url))
        val units = MutableStateFlow(
            listOf(
                Chapter.create().copy(id = 5, mangaId = 2, url = "current/5", name = "First"),
                Chapter.create().copy(id = 6, mangaId = 2, url = "current/6", name = "Second"),
            ),
        )
        val events = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        val files = mutableSetOf("current/5")
        val mangas = mockk<MangaRepository> {
            every { getMangaBySourceIdAsFlow(42) } returns MutableStateFlow(listOf(manga, other))
        }
        val chapters = mockk<ChapterRepository> {
            coEvery { getChapterByMangaIdAsFlow(2, false) } returns units
        }
        val downloads = mockk<DownloadManager> {
            every { cacheChanges } returns events
            every { isChapterDownloaded(any(), any(), any(), any(), any()) } answers { thirdArg<String>() in files }
        }
        val flow = ConnectionShelfEntryObserver(42, mangas, chapters, downloads).observe(visible)
        var state = flow.first()[manga.url]!!
        assertEquals(MangaDownloadStatus.PARTIAL, state.downloads().status)
        assertNull(flow.first()[other.url])
        files += "current/6"
        events.tryEmit(Unit)
        state = flow.first()[manga.url]!!
        assertEquals(MangaDownloadStatus.COMPLETE, state.downloads().status)
        units.value = units.value.map { it.copy(read = true) }
        assertEquals(2L, flow.first()[manga.url]!!.progress!!.readCount)
        files.clear()
        events.tryEmit(Unit)
        assertEquals(MangaDownloadStatus.NONE, flow.first()[manga.url]!!.downloads().status)
        visible.value = emptySet()
        assertEquals(emptyMap<String, ConnectionShelfEntryState>(), flow.first())
    }
}
