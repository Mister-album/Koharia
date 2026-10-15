package koharia.connection

import android.os.Bundle
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ShelfObserverSqlAllocationDeviceTest {
    @Test
    fun singleDownloadChangesKeepActualSqlFlowsBounded(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "app.koharia.dev.devicefixture")
        val profiles = Injekt.get<ConnectionProfileManager>()
        val preferences = Injekt.get<ConnectionPreferences>()
        val previous = preferences.activeConnectionId.get()
        val hadPrevious = preferences.activeConnectionId.isSet()
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val downloads = Injekt.get<DownloadManager>()
        val provider = Injekt.get<DownloadProvider>()
        val cache = Injekt.get<DownloadCache>()
        val profile = profiles.add("komga", "Shelf SQL ${UUID.randomUUID()}")
        val foreign = profiles.add("komga", "Shelf neighbour ${UUID.randomUUID()}")
        val loaded = mutableListOf<Manga>()
        var source: KomgaSource? = null
        var target: Manga? = null
        try {
            val ownPreferences = sourcePreferences("source_${profile.id}")
            check(ownPreferences.all.isEmpty())
            check(ownPreferences.edit().putString("Address", "http://127.0.0.1:9").commit())
            val active = withTimeout(10000) {
                while (Injekt.get<SourceManager>().get(profile.id) !is KomgaSource) delay(25)
                Injekt.get<SourceManager>().get(profile.id) as KomgaSource
            }
            source = active
            check(!active.usesSharedDownloadStorage && provider.findOwnedSourceDirs(active).isEmpty())
            val neighbour = mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = foreign.id, url = "/series/40", title = "Foreign")),
            ).single()
            val neighbourChapter = chapters.addAll(
                listOf(Chapter.create().copy(mangaId = neighbour.id, name = "Foreign", url = "/book/40", read = true)),
            ).single()
            for (size in listOf(100, 1000, 10000)) {
                for (batch in (loaded.size until size).toList().chunked(500)) {
                    val inserted = mangas.insertNetworkManga(
                        batch.map {
                            Manga.create().copy(source = profile.id, url = "/series/$it", title = "Shelf item $it")
                        },
                    )
                    chapters.addAll(
                        inserted.map { Chapter.create().copy(mangaId = it.id, url = "/book/${it.id}", name = "Unit") },
                    )
                    loaded += inserted
                }
                val window = shelfObservationWindow((40..59).toList(), 0, loaded.size)
                val selected = window.map { loaded[it] }
                check(selected.size == 60)
                val urls = selected.mapTo(linkedSetOf(), Manga::url)
                val ids = selected.mapTo(hashSetOf(), Manga::id)
                val mangaCounts = Counts()
                val chapterCounts = Counts()
                val observedMangas = object : MangaRepository by mangas {
                    override fun getMangaByUrlsAndSourceIdAsFlow(
                        urls: Collection<String>,
                        sourceId: Long,
                    ): Flow<List<Manga>> {
                        check(sourceId == profile.id && urls.toSet() == selected.map { it.url }.toSet())
                        return mangaCounts.observe(mangas.getMangaByUrlsAndSourceIdAsFlow(urls, sourceId)) { rows ->
                            check(rows.size == 60 && rows.all { it.source == profile.id && it.id in ids })
                        }
                    }
                    override fun getMangaBySourceIdAsFlow(
                        sourceId: Long,
                    ): Flow<List<Manga>> = error("Whole source flow")
                    override fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?> =
                        error("Per-card flow")
                }
                val observedChapters = object : ChapterRepository by chapters {
                    override fun getChaptersByMangaIdsAsFlow(mangaIds: Collection<Long>): Flow<List<Chapter>> {
                        check(mangaIds.toSet() == ids)
                        return chapterCounts.observe(chapters.getChaptersByMangaIdsAsFlow(mangaIds)) { rows ->
                            check(rows.size == 60 && rows.all { it.mangaId in ids })
                        }
                    }
                    override suspend fun getChapterByMangaId(
                        mangaId: Long,
                        applyScanlatorFilter: Boolean,
                    ): List<Chapter> =
                        error("Per-card chapter lookup")
                    override suspend fun getChapterByMangaIdAsFlow(
                        mangaId: Long,
                        applyScanlatorFilter: Boolean,
                    ): Flow<List<Chapter>> =
                        error("Per-card chapter flow")
                }
                val visible = MutableStateFlow<Set<String>>(urls)
                val output = Channel<Map<String, ConnectionShelfEntryState>>(Channel.UNLIMITED)
                val job = launch {
                    ConnectionShelfEntryObserver(profile.id, observedMangas, observedChapters, downloads)
                        .observe(visible).collect { output.send(it) }
                }
                try {
                    val current = loaded[40]
                    target = current
                    val unit = chapters.getChapterByMangaId(current.id).single()
                    suspend fun awaitCount(count: Long) = withTimeout(10000) {
                        while (true) {
                            val value = output.receive()
                            check(
                                value.size == 60 &&
                                    value.values.all { it.total == 1L && it.manga?.source == profile.id },
                            )
                            if (value.getValue(current.url).downloaded == count) {
                                check(value.filterKeys { it != current.url }.values.all { it.downloaded == 0L })
                                break
                            }
                        }
                    }
                    awaitCount(0)
                    val directory = provider.getMangaDir(current.title, active).getOrThrow()
                    val filename = provider.getChapterDirName(unit.name, unit.scanlator, unit.url) + ".cbz"
                    suspend fun toggle() {
                        check(directory.findFile(filename) == null)
                        val file = checkNotNull(directory.createFile(filename))
                        file.openOutputStream().use { it.write(byteArrayOf(1, 2, 3, 4)) }
                        cache.addChapter(filename, directory, current)
                        awaitCount(1)
                        check(file.delete())
                        cache.removeChapter(unit, current)
                        awaitCount(0)
                    }
                    repeat(2) { toggle() }
                    val mangaEmissions = mangaCounts.emissions.get()
                    val chapterEmissions = chapterCounts.emissions.get()
                    repeat(5) { sample ->
                        val before = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
                        val started = System.nanoTime()
                        repeat(10) { toggle() }
                        val elapsed = System.nanoTime() - started
                        val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - before
                        check(mangaCounts.active.get() == 1 && chapterCounts.active.get() == 1)
                        check(mangaCounts.starts.get() == 1 && chapterCounts.starts.get() == 1)
                        check(
                            mangaCounts.emissions.get() == mangaEmissions &&
                                chapterCounts.emissions.get() == chapterEmissions,
                        )
                        instrumentation.sendStatus(
                            0,
                            Bundle().apply {
                                putString(
                                    "shelf_allocation",
                                    "loaded=$size;sample=$sample;events=20;window=60;" +
                                        "manga_starts=${mangaCounts.starts};chapter_starts=${chapterCounts.starts};" +
                                        "manga_rows=${mangaCounts.rows};chapter_rows=${chapterCounts.rows};" +
                                        "allocated_bytes=$allocated;elapsed_ns=$elapsed",
                                )
                            },
                        )
                    }
                    visible.value = emptySet()
                    withTimeout(10000) { while (output.receive().isNotEmpty()) Unit }
                    withTimeout(10000) {
                        while (mangaCounts.active.get() != 0 || chapterCounts.active.get() != 0) delay(10)
                    }
                } finally {
                    job.cancelAndJoin()
                    output.close()
                }
                check(mangaCounts.active.get() == 0 && chapterCounts.active.get() == 0)
                check(mangas.getMangaById(neighbour.id) == neighbour)
                check(chapters.getChapterById(neighbourChapter.id) == neighbourChapter)
                check(mangas.getMangaBySourceId(profile.id).size == size)
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    source?.let { active ->
                        target?.let { downloads.deleteManga(it, active, removeQueued = false) }
                        withTimeout(10000) { while (provider.findOwnedSourceDirs(active).isNotEmpty()) delay(25) }
                    }
                    for (id in listOf(profile.id, foreign.id)) {
                        profiles.remove(id).getOrThrow()
                        mangas.deleteMangaBySourceId(id)
                        check(mangas.getMangaBySourceId(id).isEmpty())
                    }
                } finally {
                    if (hadPrevious) {
                        preferences.activeConnectionId.set(
                            previous,
                        )
                    } else {
                        preferences.activeConnectionId.delete()
                    }
                }
                instrumentation.sendStatus(0, Bundle().apply { putString("shelf_allocation_cleanup", "owned-only") })
            }
        }
    }

    private class Counts {
        val starts = AtomicInteger()
        val active = AtomicInteger()
        val emissions = AtomicInteger()
        val rows = AtomicInteger()
        fun <T> observe(actual: Flow<List<T>>, verify: (List<T>) -> Unit): Flow<List<T>> = flow {
            starts.incrementAndGet()
            check(active.incrementAndGet() == 1)
            try {
                actual.collect {
                    verify(it)
                    emissions.incrementAndGet()
                    rows.addAndGet(it.size)
                    emit(it)
                }
            } finally {
                active.decrementAndGet()
            }
        }
    }
}
