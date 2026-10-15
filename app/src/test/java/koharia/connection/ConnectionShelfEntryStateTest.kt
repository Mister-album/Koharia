package koharia.connection

import eu.kanade.tachiyomi.data.download.DownloadCacheChange
import eu.kanade.tachiyomi.data.download.DownloadManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
    fun `catalogue changes update totals without reopening SQL or recounting downloads`() = runBlocking {
        val manga = Manga.create().copy(id = 1, source = 42, url = "owned", title = "Owned")
        val rows = MutableStateFlow(listOf(manga))
        val units = MutableStateFlow(
            (1..2).map {
                Chapter.create().copy(id = it.toLong(), mangaId = 1, url = "unit/$it", name = "Unit $it")
            },
        )
        val totals = MutableStateFlow<Map<String, Long>>(emptyMap())
        val counts = object : ConnectionShelfUnitCountAdapter {
            override fun observeShelfUnitCounts(entryUrls: Set<String>) = totals.also {
                assertEquals(setOf(manga.url), entryUrls)
            }
        }
        val queries = java.util.concurrent.atomic.AtomicInteger()
        val checks = java.util.concurrent.atomic.AtomicInteger()
        val mangas = mockk<MangaRepository> {
            every { getMangaByUrlsAndSourceIdAsFlow(any(), 42) } answers {
                queries.incrementAndGet()
                rows
            }
        }
        val chapters = mockk<ChapterRepository> {
            every { getChaptersByMangaIdsAsFlow(any()) } returns units
        }
        val downloads = mockk<DownloadManager> {
            every { cacheUpdates } returns MutableSharedFlow<DownloadCacheChange>()
            every { isChapterDownloaded(any(), any(), any(), any(), any()) } answers {
                checks.incrementAndGet()
                true
            }
        }
        val results = Channel<Map<String, ConnectionShelfEntryState>>(Channel.UNLIMITED)
        val visible = MutableStateFlow(setOf(manga.url))
        val job = launch {
            ConnectionShelfEntryObserver(42, mangas, chapters, downloads, counts).observe(visible).collect {
                results.send(it)
            }
        }
        try {
            withTimeout(10_000) {
                assertEquals(
                    MangaDownloadStatus.UNKNOWN_TOTAL,
                    results.receive().getValue(manga.url).downloads().status,
                )
                for ((total, expected) in listOf(
                    2L to MangaDownloadStatus.COMPLETE,
                    3L to MangaDownloadStatus.PARTIAL,
                    1L to MangaDownloadStatus.UNKNOWN_TOTAL,
                    0L to MangaDownloadStatus.UNKNOWN_TOTAL,
                )) {
                    totals.value = mapOf(manga.url to total)
                    val value = results.receive().getValue(manga.url)
                    assertEquals(expected, value.downloads().status)
                    assertEquals(expected, value.downloads(2).status)
                }
                assertEquals(1, queries.get())
                assertEquals(2, checks.get())
                assertEquals(1, totals.subscriptionCount.value)
                visible.value = emptySet()
                assertEquals(emptyMap<String, ConnectionShelfEntryState>(), results.receive())
            }
        } finally {
            job.cancelAndJoin()
            results.close()
        }
        assertEquals(0, totals.subscriptionCount.value)
    }

    @Test
    fun `single download changes stay bounded for 100 1000 and 10000 loaded shelf rows`() = runBlocking {
        for (size in listOf(100, 1_000, 10_000)) {
            val library = (0 until size).map { index ->
                Manga.create().copy(id = index.toLong() + 1, source = 42, url = "book/$index", title = "Book $index")
            }
            val window = shelfObservationWindow((40..59).toList(), 0, library.size)
            val selected = window.map { library[it] }
            assertEquals(60, selected.size)
            val visible = MutableStateFlow<Set<String>>(selected.mapTo(linkedSetOf()) { it.url })
            val mangaQueries = java.util.concurrent.atomic.AtomicInteger()
            val chapterQueries = java.util.concurrent.atomic.AtomicInteger()
            val chapterChecks = java.util.concurrent.atomic.AtomicInteger()
            val affectedChecks = java.util.concurrent.atomic.AtomicInteger()
            val outputs = java.util.concurrent.atomic.AtomicInteger()
            val requestedWidths = java.util.concurrent.ConcurrentLinkedQueue<Int>()
            val localRows = MutableStateFlow(selected)
            val units = MutableStateFlow(
                selected.map { Chapter.create().copy(id = it.id, mangaId = it.id, url = it.url, name = "Unit") },
            )
            val mangas = mockk<MangaRepository> {
                every { getMangaByUrlsAndSourceIdAsFlow(any(), 42) } answers {
                    mangaQueries.incrementAndGet()
                    val urls = firstArg<Collection<String>>()
                    requestedWidths.add(urls.size)
                    assertEquals(selected.map { it.url }.toSet(), urls.toSet())
                    localRows
                }
            }
            val chapters = mockk<ChapterRepository> {
                every { getChaptersByMangaIdsAsFlow(any()) } answers {
                    chapterQueries.incrementAndGet()
                    assertEquals(selected.map { it.id }.toSet(), firstArg<Collection<Long>>().toSet())
                    units
                }
            }
            val files = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val events = MutableSharedFlow<DownloadCacheChange>()
            val downloads = mockk<DownloadManager> {
                every { cacheUpdates } returns events
                every { isAffectedByCacheUpdate(any(), any()) } answers {
                    affectedChecks.incrementAndGet()
                    val manga = firstArg<Manga>()
                    secondArg<DownloadCacheChange>().affects(manga.source, manga.title)
                }
                every { isChapterDownloaded(any(), any(), any(), any(), any()) } answers {
                    chapterChecks.incrementAndGet()
                    thirdArg<String>() in files
                }
            }
            val results = Channel<Map<String, ConnectionShelfEntryState>>(Channel.UNLIMITED)
            val job = launch {
                ConnectionShelfEntryObserver(42, mangas, chapters, downloads).observe(visible).collect {
                    outputs.incrementAndGet()
                    results.send(it)
                }
            }
            try {
                withTimeout(10_000) {
                    val initial = results.receive()
                    assertEquals(60, initial.size)
                    assertEquals(0L, initial.values.sumOf { it.downloaded })
                    events.subscriptionCount.first { it == 1 }
                    assertEquals(60, chapterChecks.get())
                    val target = selected[30]
                    files.add(target.url)
                    // Unrelated loaded rows and accounts must not recount or publish visible state.
                    events.emit(DownloadCacheChange(42, setOf(library.last().title)))
                    events.emit(DownloadCacheChange(43, setOf(target.title)))
                    events.emit(DownloadCacheChange(42, setOf(target.title)))
                    val downloaded = results.receive()
                    assertEquals(1L, downloaded.getValue(target.url).downloaded)
                    assertEquals(setOf(target.url), downloaded.getValue(target.url).downloadedChapterUrls)
                    assertEquals(MangaDownloadStatus.COMPLETE, downloaded.getValue(target.url).downloads().status)
                    assertEquals(1, selected.count { initial.getValue(it.url) != downloaded.getValue(it.url) })
                    assertEquals(61, chapterChecks.get())
                    assertEquals(180, affectedChecks.get())
                    assertEquals(2, outputs.get())
                    files.remove(target.url)
                    events.emit(DownloadCacheChange(42, setOf(target.title)))
                    val removed = results.receive()
                    assertEquals(initial, removed)
                    assertEquals(62, chapterChecks.get())
                    units.value = units.value.map { if (it.mangaId == target.id) it.copy(read = true) else it }
                    val read = results.receive()
                    assertEquals(1L, read.getValue(target.url).read)
                    assertEquals(62, chapterChecks.get())
                    assertEquals(1, mangaQueries.get())
                    assertEquals(1, chapterQueries.get())
                    assertEquals(listOf(60), requestedWidths.toList())
                    assertEquals(1, localRows.subscriptionCount.value)
                    assertEquals(1, units.subscriptionCount.value)
                    assertEquals(1, events.subscriptionCount.value)
                    assertEquals(4, outputs.get())
                    println(
                        "SHELF_SCALE loaded=$size;window=60;manga_observations=1;chapter_observations=1;" +
                            "download_subscriptions=1;bootstrap_checks=60;single_change_checks=1;" +
                            "unrelated_recounts=0;deletion_checks=1;read_change_checks=0;outputs=4",
                    )
                    visible.value = emptySet()
                    assertEquals(emptyMap<String, ConnectionShelfEntryState>(), results.receive())
                }
            } finally {
                job.cancelAndJoin()
                results.close()
            }
            assertEquals(0, localRows.subscriptionCount.value)
            assertEquals(0, units.subscriptionCount.value)
            assertEquals(0, events.subscriptionCount.value)
        }
    }

    @Test
    fun `download changes while a new chapter batch is pending remain visible after it arrives`() = runBlocking {
        val first = Manga.create().copy(id = 1, source = 42, url = "a", title = "A")
        val second = first.copy(id = 2, url = "b", title = "B")
        val rows = MutableStateFlow(listOf(first))
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val invalidated = kotlinx.coroutines.CompletableDeferred<Unit>()
        val files = java.util.concurrent.atomic.AtomicBoolean(false)
        val mangas = mockk<MangaRepository> {
            every { getMangaByUrlsAndSourceIdAsFlow(any(), 42) } returns rows
        }
        val chapters = mockk<ChapterRepository> {
            every { getChaptersByMangaIdsAsFlow(any()) } answers {
                val ids = firstArg<Collection<Long>>()
                flow {
                    if (ids.size == 2) {
                        started.complete(Unit)
                        ready.await()
                    }
                    emit(ids.map { Chapter.create().copy(id = it, mangaId = it, url = "$it", name = "Unit") })
                }
            }
        }
        val events = MutableSharedFlow<DownloadCacheChange>()
        val downloads = mockk<DownloadManager> {
            every { cacheUpdates } returns events
            every { isAffectedByCacheUpdate(any(), any()) } answers {
                if (firstArg<Manga>().id == 2L && files.get()) invalidated.complete(Unit)
                firstArg<Manga>().id == 1L
            }
            every { isChapterDownloaded(any(), any(), any(), any(), any()) } answers {
                thirdArg<String>() == "1" && files.get()
            }
        }
        val results = Channel<Map<String, ConnectionShelfEntryState>>(Channel.UNLIMITED)
        val job = launch {
            ConnectionShelfEntryObserver(42, mangas, chapters, downloads)
                .observe(MutableStateFlow(setOf("a", "b"))).collect { results.send(it) }
        }
        try {
            withTimeout(5_000) {
                assertEquals(0L, results.receive().getValue("a").downloaded)
                events.subscriptionCount.first { it == 1 }
                rows.value = listOf(first, second)
                started.await()
                files.set(true)
                while (!invalidated.isCompleted) {
                    events.emit(DownloadCacheChange(42, setOf("A")))
                    kotlinx.coroutines.yield()
                }
                ready.complete(Unit)
                var current = results.receive()
                while (current.size != 2) current = results.receive()
                assertEquals(1L, current.getValue("a").downloaded)
                assertEquals(0L, current.getValue("b").downloaded)
            }
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `download invalidation respects connection and shared directory aliases`() {
        val change = DownloadCacheChange(42, setOf("shared-directory", "renamed-directory"))
        org.junit.jupiter.api.Assertions.assertTrue(change.affects(42, "shared-directory"))
        org.junit.jupiter.api.Assertions.assertTrue(change.affects(42, "renamed-directory"))
        org.junit.jupiter.api.Assertions.assertFalse(change.affects(43, "shared-directory"))
        org.junit.jupiter.api.Assertions.assertFalse(change.affects(42, "unrelated"))
        org.junit.jupiter.api.Assertions.assertTrue(DownloadCacheChange(42).affects(42, "any"))
        org.junit.jupiter.api.Assertions.assertTrue(DownloadCacheChange().affects(43, "any"))
    }

    @Test
    fun `viewport window excludes headers and retains only one screen on each side`() {
        assertEquals(78..137, shelfObservationWindow((100..119).toList(), 2, 10_000))
        assertEquals(0..39, shelfObservationWindow((2..21).toList(), 2, 10_000))
        assertEquals(960..999, shelfObservationWindow((982..1003).toList(), 2, 1_000))
        assertEquals(IntRange.EMPTY, shelfObservationWindow(listOf(0, 1), 2, 100))
        assertEquals(IntRange.EMPTY, shelfObservationWindow(listOf(0), 0, 0))
        for (start in 100..9_000 step 100) {
            assertEquals(60, shelfObservationWindow((start until start + 20).toList(), 0, 10_000).count())
        }
    }

    @Test
    fun `resource changes are lossless and unchanged chapters do not recount downloads`() = runBlocking {
        val local = listOf(
            Manga.create().copy(id = 1, source = 42, url = "a", title = "A"),
            Manga.create().copy(id = 2, source = 42, url = "b", title = "B"),
        )
        val units =
            MutableStateFlow(
                local.map {
                    Chapter.create().copy(id = it.id, mangaId = it.id, url = it.url, name = it.title)
                },
            )
        val events = MutableSharedFlow<DownloadCacheChange>()
        val files = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val checks = java.util.concurrent.atomic.AtomicInteger()
        val rows = MutableStateFlow(local)
        val mangas = mockk<MangaRepository> {
            every { getMangaByUrlsAndSourceIdAsFlow(setOf("a", "b"), 42) } returns rows
        }
        val chapters = mockk<ChapterRepository> {
            every { getChaptersByMangaIdsAsFlow(listOf(1L, 2L)) } returns units
        }
        val downloads = mockk<DownloadManager> {
            every { cacheUpdates } returns events
            every { isAffectedByCacheUpdate(any(), any()) } answers {
                val manga = firstArg<Manga>()
                secondArg<DownloadCacheChange>().affects(manga.source, manga.title)
            }
            every { isChapterDownloaded(any(), any(), any(), any(), any()) } answers {
                checks.incrementAndGet()
                thirdArg<String>() in files
            }
        }
        val results = Channel<Map<String, ConnectionShelfEntryState>>(Channel.UNLIMITED)
        val job = launch {
            ConnectionShelfEntryObserver(42, mangas, chapters, downloads)
                .observe(MutableStateFlow(setOf("a", "b"))).collect { results.send(it) }
        }
        try {
            withTimeout(5_000) {
                assertEquals(0L, results.receive().values.sumOf { it.downloaded })
                events.subscriptionCount.first { it == 1 }
                assertEquals(2, checks.get())
                files.addAll(listOf("a", "b"))
                events.emit(DownloadCacheChange(42, setOf("A")))
                events.emit(DownloadCacheChange(42, setOf("B")))
                assertEquals(1L, results.receive().values.sumOf { it.downloaded })
                assertEquals(2L, results.receive().values.sumOf { it.downloaded })
                assertEquals(4, checks.get())
                units.value = units.value.map { it.copy(read = true) }
                assertEquals(2L, results.receive().values.sumOf { it.read })
                assertEquals(4, checks.get())
                rows.value = listOf(local[0].copy(favorite = true, notes = "Latest local note"), local[1])
                val updated = results.receive()
                assertEquals(rows.value[0], updated.getValue("a").manga)
                assertEquals(local[1], updated.getValue("b").manga)
                assertEquals(4, checks.get())
                assertEquals(1, rows.subscriptionCount.value)
                assertEquals(1, units.subscriptionCount.value)
                rows.value = listOf(rows.value[0].copy(title = "Renamed"), local[1])
                assertEquals("Renamed", results.receive().getValue("a").manga?.title)
                assertEquals(5, checks.get())
                io.mockk.verify(exactly = 1) { chapters.getChaptersByMangaIdsAsFlow(any()) }
            }
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `long scrolling keeps one batch subscription and releases obsolete windows`() = runBlocking {
        val active = java.util.concurrent.atomic.AtomicInteger()
        val maximum = java.util.concurrent.atomic.AtomicInteger()
        val widths = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val visible = MutableStateFlow((0 until 60).map(Int::toString).toSet())
        val mangas = mockk<MangaRepository> {
            every { getMangaByUrlsAndSourceIdAsFlow(any(), 42) } answers {
                MutableStateFlow(
                    firstArg<Collection<String>>().map {
                        Manga.create().copy(id = it.toLong(), source = 42, url = it)
                    },
                )
            }
        }
        val chapters = mockk<ChapterRepository> {
            every { getChaptersByMangaIdsAsFlow(any()) } answers {
                val ids = firstArg<Collection<Long>>().toList()
                flow {
                    widths.add(ids.size)
                    maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
                    try {
                        emit(ids.map { Chapter.create().copy(id = it, mangaId = it, name = "Unit", url = "$it") })
                        awaitCancellation()
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        }
        val events = MutableSharedFlow<DownloadCacheChange>()
        val downloads = mockk<DownloadManager> {
            every { cacheUpdates } returns events
            every { isChapterDownloaded(any(), any(), any(), any(), any()) } returns false
        }
        val results = Channel<Map<String, ConnectionShelfEntryState>>(Channel.UNLIMITED)
        val job =
            launch {
                ConnectionShelfEntryObserver(42, mangas, chapters, downloads).observe(visible).collect {
                    results.send(it)
                }
            }
        try {
            withTimeout(10_000) {
                assertEquals(60, results.receive().size)
                for (start in 100..2_000 step 100) {
                    visible.value = (start until start + 60).map(Int::toString).toSet()
                    assertEquals(visible.value, results.receive().keys)
                }
                assertEquals(1, maximum.get())
                assertEquals(setOf(60), widths.toSet())
                assertEquals(1, events.subscriptionCount.value)
                visible.value = emptySet()
                assertEquals(emptyMap<String, ConnectionShelfEntryState>(), results.receive())
                assertEquals(0, active.get())
            }
        } finally {
            job.cancelAndJoin()
        }
        assertEquals(0, events.subscriptionCount.value)
    }

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
        val events = MutableSharedFlow<DownloadCacheChange>(extraBufferCapacity = 1)
        val files = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val mangas = mockk<MangaRepository> {
            every { getMangaByUrlsAndSourceIdAsFlow(any(), 42) } answers {
                val urls = firstArg<Collection<String>>()
                MutableStateFlow(listOf(old, current).filter { it.url in urls })
            }
        }
        val chapters = mockk<ChapterRepository> {
            coEvery { getChaptersByMangaIdsAsFlow(listOf(10L)) } returns oldUnits
            coEvery { getChaptersByMangaIdsAsFlow(listOf(20L)) } returns currentUnits
        }
        val downloads = mockk<DownloadManager> {
            every { cacheUpdates } returns events
            every { isAffectedByCacheUpdate(any(), any()) } answers {
                val manga = firstArg<Manga>()
                secondArg<DownloadCacheChange>().affects(manga.source, manga.title)
            }
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
                events.emit(DownloadCacheChange())
                assertEquals(MangaDownloadStatus.COMPLETE, results.receive().getValue(old.url).downloads().status)
                files.clear()
                events.emit(DownloadCacheChange())
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
        val events = MutableSharedFlow<DownloadCacheChange>(replay = 1).apply { tryEmit(DownloadCacheChange()) }
        val files = mutableSetOf("current/5")
        val mangas = mockk<MangaRepository> {
            every { getMangaByUrlsAndSourceIdAsFlow(any(), 42) } answers {
                val urls = firstArg<Collection<String>>()
                MutableStateFlow(listOf(manga, other).filter { it.url in urls })
            }
        }
        val chapters = mockk<ChapterRepository> {
            coEvery { getChaptersByMangaIdsAsFlow(listOf(2L)) } returns units
        }
        val downloads = mockk<DownloadManager> {
            every { cacheUpdates } returns events
            every { isAffectedByCacheUpdate(any(), any()) } answers {
                val manga = firstArg<Manga>()
                secondArg<DownloadCacheChange>().affects(manga.source, manga.title)
            }
            every { isChapterDownloaded(any(), any(), any(), any(), any()) } answers { thirdArg<String>() in files }
        }
        val flow = ConnectionShelfEntryObserver(42, mangas, chapters, downloads).observe(visible)
        var state = flow.first()[manga.url]!!
        assertEquals(MangaDownloadStatus.PARTIAL, state.downloads().status)
        assertNull(flow.first()[other.url])
        files += "current/6"
        events.tryEmit(DownloadCacheChange())
        state = flow.first()[manga.url]!!
        assertEquals(MangaDownloadStatus.COMPLETE, state.downloads().status)
        units.value = units.value.map { it.copy(read = true) }
        assertEquals(2L, flow.first()[manga.url]!!.progress!!.readCount)
        files.clear()
        events.tryEmit(DownloadCacheChange())
        assertEquals(MangaDownloadStatus.NONE, flow.first()[manga.url]!!.downloads().status)
        visible.value = emptySet()
        assertEquals(emptyMap<String, ConnectionShelfEntryState>(), flow.first())
    }
}
