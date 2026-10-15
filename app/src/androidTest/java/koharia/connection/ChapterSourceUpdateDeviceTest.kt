package koharia.connection

import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterSourceUpdate
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicInteger

class ChapterSourceUpdateDeviceTest {
    @Test
    fun failedInsertPreservesRemovedChaptersAndPropagatesThroughSync(): Unit = runBlocking(Dispatchers.IO) {
        fixture { manga, repository ->
            repository.addAll(listOf(chapter(manga, "old").copy(read = true, bookmark = true, lastPageRead = 7)))
            val before = repository.getChapterByMangaId(manga.id)
            val driver = Injekt.get<SqlDriver>()
            val trigger = "chapter_source_failure_${manga.id}"
            val url = "fixture-insert-reject-${manga.source}"
            driver.execute(
                null,
                """
                CREATE TEMP TRIGGER $trigger BEFORE INSERT ON chapters
                WHEN NEW.manga_id = ${manga.id} AND NEW.url = '$url'
                BEGIN SELECT RAISE(ABORT, 'fixture insertion failure'); END
                """.trimIndent(),
                0,
            ).await()
            try {
                val remote = SChapter.create().apply {
                    this.url = url
                    name = "New chapter"
                }
                val failure = runCatching { sync(repository).await(fetched(manga, listOf(remote)), manga) }
                assertTrue(failure.isFailure)
                assertEquals(before, repository.getChapterByMangaId(manga.id))
            } finally {
                driver.execute(null, "DROP TRIGGER IF EXISTS $trigger", 0).await()
            }
        }
    }

    @Test
    fun cancellationAfterAllWritesRollsBackInsertDeleteAndMetadata(): Unit = runBlocking(Dispatchers.IO) {
        fixture { manga, repository ->
            val before = repository.addAll(listOf(chapter(manga, "remove"), chapter(manga, "keep")))
            val snapshot = repository.getChapterByMangaId(manga.id)
            val change = change(manga, snapshot).copy(
                added = listOf(chapter(manga, "new")),
                removed = listOf(before.first().id),
                updated = listOf(ChapterUpdate(before.last().id, name = "Changed")),
            )
            var checks = 0
            assertTrue(
                runCatching {
                    repository.applySourceUpdate(change) {
                        if (++checks == 3) throw CancellationException("Fixture owner changed")
                    }
                }.exceptionOrNull() is CancellationException,
            )
            assertEquals(3, checks)
            assertEquals(snapshot, repository.getChapterByMangaId(manga.id))
            assertEquals(1, repository.applySourceUpdate(change) {}!!.size)
            assertEquals(setOf("keep", "new"), repository.getChapterByMangaId(manga.id).map { it.url }.toSet())
        }
    }

    @Test
    fun fetchedOwnerChangeAfterSqlWritesRollsBackThroughProductionSync(): Unit = runBlocking(Dispatchers.IO) {
        fixture { manga, repository ->
            repository.addAll(listOf(chapter(manga, "remove"), chapter(manga, "keep")))
            val before = repository.getChapterByMangaId(manga.id)
            var current = true
            val source = object : Source, ConnectionChapterSessionAdapter {
                override val id = manga.source
                override val name = "Owner fixture"
                override fun captureChapterOwner() = ConnectionChapterOwner("fixture") {
                    if (!current) throw CancellationException("Fixture owner changed")
                }
            }
            val fetched = ChapterFetchSession.capture(source, ChapterFetchOrigin.EXPLICIT_REFRESH).fetch {
                listOf(chapter(manga, "new"), chapter(manga, "keep").copy(name = "Changed")).map { it.toSChapter() }
            }
            var checks = 0
            val interleaved = object : ChapterRepository by repository {
                override suspend fun applySourceUpdate(
                    update: ChapterSourceUpdate,
                    checkCurrent: suspend () -> Unit,
                ): List<Chapter>? = repository.applySourceUpdate(update) {
                    if (++checks == 3) current = false
                    checkCurrent()
                }
            }
            assertTrue(
                runCatching { sync(interleaved).await(fetched, manga) }.exceptionOrNull() is CancellationException,
            )
            assertEquals(3, checks)
            assertEquals(before, repository.getChapterByMangaId(manga.id))
        }
    }

    @Test
    fun staleReadingSnapshotAndChangedMembershipHaveNoPartialEffects(): Unit = runBlocking(Dispatchers.IO) {
        fixture { manga, repository ->
            val old = repository.addAll(listOf(chapter(manga, "old"))).single()
            val snapshot = repository.getChapterByMangaId(manga.id)
            val change = change(manga, snapshot).copy(removed = listOf(old.id), added = listOf(chapter(manga, "new")))
            repository.update(ChapterUpdate(old.id, read = true, bookmark = true, lastPageRead = 5))
            assertNull(repository.applySourceUpdate(change) {})
            assertTrue(repository.getChapterById(old.id)!!.read)
            assertEquals(5L, repository.getChapterById(old.id)!!.lastPageRead)
            val refreshed = repository.getChapterByMangaId(manga.id)
            repository.addAll(listOf(chapter(manga, "concurrent")))
            assertNull(repository.applySourceUpdate(change.copy(expected = refreshed)) {})
            assertEquals(setOf("old", "concurrent"), repository.getChapterByMangaId(manga.id).map { it.url }.toSet())
        }
    }

    @Test
    fun concurrentRefreshesInsertAndReportEachNewChapterOnlyOnce(): Unit = runBlocking(Dispatchers.IO) {
        fixture { manga, repository ->
            val captured = AtomicInteger()
            val bothCaptured = CompletableDeferred<Unit>()
            val interleaved = object : ChapterRepository by repository {
                override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
                    val rows = repository.getChapterByMangaId(mangaId, applyScanlatorFilter)
                    if (captured.incrementAndGet() <= 2) {
                        if (captured.get() == 2) bothCaptured.complete(Unit)
                        bothCaptured.await()
                    }
                    return rows
                }
            }
            val remote = chapter(manga, "new").toSChapter()
            val results = withTimeout(10_000) {
                coroutineScope {
                    List(2) {
                        async { sync(repository, interleaved).await(fetched(manga, listOf(remote)), manga) }
                    }.awaitAll()
                }
            }
            assertEquals(1, results.flatten().size)
            assertEquals(1, repository.getChapterByMangaId(manga.id).size)
            assertTrue(captured.get() >= 3)
        }
    }

    @Test
    fun retryRecomputesReplacementReadBookmarkAndFetchDateFromFreshRows(): Unit = runBlocking(Dispatchers.IO) {
        fixture { manga, repository ->
            val old = repository.addAll(
                listOf(chapter(manga, "old").copy(chapterNumber = 1.0, dateFetch = 123)),
            ).single()
            var changed = false
            val interleaved = object : ChapterRepository by repository {
                override suspend fun applySourceUpdate(
                    update: ChapterSourceUpdate,
                    checkCurrent: suspend () -> Unit,
                ): List<Chapter>? {
                    if (!changed) {
                        changed = true
                        repository.update(ChapterUpdate(old.id, read = true, bookmark = true, lastPageRead = 5))
                    }
                    return repository.applySourceUpdate(update, checkCurrent)
                }
            }
            val remote = chapter(manga, "renamed").copy(chapterNumber = 1.0).toSChapter()
            assertTrue(sync(interleaved).await(fetched(manga, listOf(remote)), manga).isEmpty())
            val saved = repository.getChapterByMangaId(manga.id).single()
            assertEquals("renamed", saved.url)
            assertTrue(saved.read)
            assertTrue(saved.bookmark)
            assertEquals(123L, saved.dateFetch)
        }
    }

    @Test
    fun wrongOwnerOrReadingWritesThroughMetadataContractAreRejected(): Unit = runBlocking(Dispatchers.IO) {
        fixture { manga, repository ->
            val old = repository.addAll(listOf(chapter(manga, "old"))).single()
            val snapshot = repository.getChapterByMangaId(manga.id)
            val change = change(manga, snapshot)
            for (invalid in listOf(
                change.copy(sourceId = -1),
                change.copy(mangaUrl = "other"),
                change.copy(updated = listOf(ChapterUpdate(old.id, read = true))),
                change.copy(removed = listOf(-1)),
                change.copy(added = listOf(chapter(manga, "old"))),
            )) {
                assertTrue(runCatching { repository.applySourceUpdate(invalid) {} }.isFailure)
                assertEquals(snapshot, repository.getChapterByMangaId(manga.id))
            }
            assertFalse(repository.getChapterById(old.id)!!.read)
        }
    }

    @Test
    fun continuouslyChangingSnapshotFailsExplicitlyInsteadOfPretendingSuccess(): Unit = runBlocking(Dispatchers.IO) {
        fixture { manga, repository ->
            var attempts = 0
            val contested = object : ChapterRepository by repository {
                override suspend fun applySourceUpdate(
                    update: ChapterSourceUpdate,
                    checkCurrent: suspend () -> Unit,
                ): List<Chapter>? {
                    attempts++
                    return null
                }
            }
            assertTrue(
                runCatching {
                    sync(contested).await(fetched(manga, listOf(chapter(manga, "new").toSChapter())), manga)
                }.exceptionOrNull() is ConcurrentModificationException,
            )
            assertEquals(3, attempts)
            assertTrue(repository.getChapterByMangaId(manga.id).isEmpty())
        }
    }

    private fun change(manga: Manga, snapshot: List<Chapter>) = ChapterSourceUpdate(
        manga.id,
        manga.source,
        manga.url,
        snapshot,
        emptyList(),
        emptyList(),
        emptyList(),
    )

    private fun chapter(manga: Manga, url: String) = Chapter.create().copy(mangaId = manga.id, url = url, name = url)

    private fun source(manga: Manga) = object : Source {
        override val id = manga.source
        override val name = "Source update fixture"
    }

    private suspend fun fetched(manga: Manga, chapters: List<SChapter>) =
        ChapterFetchSession.capture(source(manga), ChapterFetchOrigin.OPEN).fetch { chapters }

    private fun sync(repository: ChapterRepository, reads: ChapterRepository = repository) = SyncChaptersWithSource(
        downloadManager = Injekt.get(),
        downloadProvider = Injekt.get(),
        chapterRepository = repository,
        shouldUpdateDbChapter = Injekt.get(),
        updateManga = Injekt.get(),
        getChaptersByMangaId = GetChaptersByMangaId(reads),
        getExcludedScanlators = Injekt.get(),
        libraryPreferences = Injekt.get(),
    )

    private suspend fun fixture(block: suspend (Manga, ChapterRepository) -> Unit) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "app.koharia.dev.devicefixture")
        val identity = System.nanoTime()
        val mangas = Injekt.get<MangaRepository>()
        val manga = mangas.insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = identity,
                    url = "source-update-$identity",
                    title = "Source update fixture",
                ),
            ),
        ).single()
        try {
            block(manga, Injekt.get())
        } finally {
            mangas.deleteMangaById(manga.id)
        }
    }
}
