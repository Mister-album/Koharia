package koharia.epub

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.domain.epub.model.EpubProgress
import koharia.domain.epub.repository.EpubProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

@RunWith(AndroidJUnit4::class)
class EpubProgressCommitDeviceTest {
    @Test
    fun explicitUnreadInvalidatesAnEmptySnapshotUsingDurableRevision(): Unit = runBlocking(Dispatchers.IO) {
        withProgress { repository, original ->
            val sourceId = Injekt.get<MangaRepository>().getMangaById(original.mangaId).source
            val intents = Injekt.get<koharia.domain.komga.KomgaReadStatusRepository>()
            val ordering = koharia.komga.progress.KomgaProgressOrdering(intents, Injekt.get())
            val key = koharia.domain.komga.KomgaReadStatusKey(sourceId, "fixture", "book")
            try {
                val opened = ordering.capture(key)
                assertNull(repository.getProgress(original.chapterId))
                val chapter = checkNotNull(Injekt.get<ChapterRepository>().getChapterById(original.chapterId))
                ordering.record(
                    key,
                    false,
                    100,
                    null,
                    listOf(koharia.domain.komga.KomgaReadStatusChapter(chapter.id, chapter.url)),
                ) {}
                assertNull(repository.getProgress(original.chapterId))
                val failure = runCatching {
                    repository.compareAndSetProgress(null, original) { ordering.checkCurrent(opened) }
                }.exceptionOrNull()
                assertTrue(failure is koharia.komga.progress.KomgaProgressSuperseded)
                assertNull(repository.getProgress(original.chapterId))
                val recreated = koharia.komga.progress.KomgaProgressOrdering(intents, Injekt.get())
                val fresh = recreated.capture(key)
                assertEquals(1L, fresh.revision)
                assertTrue(repository.compareAndSetProgress(null, original) { recreated.checkCurrent(fresh) })
                assertEquals(original, repository.getProgress(original.chapterId))
            } finally {
                intents.removeConnection(sourceId)
            }
        }
    }

    @Test
    fun lateAcknowledgementsCannotReplaceNewerOrDeletedProgress(): Unit = runBlocking(Dispatchers.IO) {
        withProgress { repository, original ->
            assertTrue(repository.compareAndSetProgress(null, original) {})
            val acknowledged = original.copy(lastSyncedAt = original.updatedAt)
            for (time in listOf(200L, 50L)) {
                val newer = original.copy(locatorJson = "new-$time", updatedAt = Date(time))
                repository.upsertProgress(newer)
                assertFalse(repository.compareAndSetProgress(original, acknowledged) {})
                assertEquals(newer, repository.getProgress(original.chapterId))
            }
            repository.deleteProgress(original.chapterId)
            assertFalse(repository.compareAndSetProgress(original, acknowledged) {})
            assertNull(repository.getProgress(original.chapterId))
        }
    }

    @Test
    fun sessionInvalidationDuringCommitRollsBackAndAllowsFreshRetry(): Unit = runBlocking(Dispatchers.IO) {
        withProgress { repository, original ->
            repository.upsertProgress(original)
            val acknowledged = original.copy(lastSyncedAt = original.updatedAt)
            var checks = 0
            val error = runCatching {
                repository.compareAndSetProgress(original, acknowledged) {
                    if (++checks == 2) throw CancellationException("fixture session replaced")
                }
            }.exceptionOrNull()
            assertTrue(error is CancellationException)
            assertEquals(2, checks)
            assertEquals(original, repository.getProgress(original.chapterId))
            assertTrue(repository.compareAndSetProgress(original, acknowledged) {})
            assertEquals(acknowledged, repository.getProgress(original.chapterId))
        }
    }

    @Test
    fun concurrentResultsHaveOneWinnerAndCannotMoveProgressToAnotherSeries(): Unit = runBlocking(Dispatchers.IO) {
        withProgress { repository, original ->
            repository.upsertProgress(original)
            val results = coroutineScope {
                (1..12).map { revision ->
                    async {
                        repository.compareAndSetProgress(
                            original,
                            original.copy(locatorJson = "result-$revision", lastSyncedAt = Date(revision.toLong())),
                        ) {}
                    }
                }.awaitAll()
            }
            assertEquals(1, results.count { it })
            val winner = repository.getProgress(original.chapterId)
            val mismatched = runCatching {
                repository.compareAndSetProgress(winner, original.copy(mangaId = original.mangaId + 1)) {}
            }
            assertTrue(mismatched.exceptionOrNull() is IllegalArgumentException)
            assertEquals(winner, repository.getProgress(original.chapterId))
        }
    }

    @Test
    fun invalidForeignKeysAreReportedInsteadOfAppearingSaved(): Unit = runBlocking(Dispatchers.IO) {
        withProgress { repository, original ->
            val missingChapter = original.copy(chapterId = -System.nanoTime())
            check(repository.getProgress(missingChapter.chapterId) == null)
            try {
                val failure = runCatching { repository.upsertProgress(missingChapter) }.exceptionOrNull()
                assertNotNull(failure)
                assertNull(repository.getProgress(missingChapter.chapterId))
            } finally {
                repository.deleteProgress(missingChapter.chapterId)
            }
            val invalidOwner = runCatching {
                repository.compareAndSetProgress(null, original.copy(mangaId = -1)) {}
            }.exceptionOrNull()
            assertTrue(invalidOwner is IllegalStateException)
            assertNull(repository.getProgress(original.chapterId))
        }
    }

    private suspend fun withProgress(block: suspend (EpubProgressRepository, EpubProgress) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val repository = Injekt.get<EpubProgressRepository>()
        val identity = System.nanoTime()
        val manga = mangas.insertNetworkManga(
            listOf(Manga.create().copy(source = identity, url = "/epub-commit-$identity", title = "Progress fixture")),
        ).single()
        try {
            val chapter = chapters.addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, url = "/book-$identity", name = "Fixture")),
            ).single()
            val original = EpubProgress(chapter.id, manga.id, chapter.url, "initial", 0.25, 1, Date(100), null)
            block(repository, original)
        } finally {
            mangas.deleteMangaById(manga.id)
        }
    }
}
