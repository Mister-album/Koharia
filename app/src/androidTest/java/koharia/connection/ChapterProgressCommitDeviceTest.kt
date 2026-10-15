package koharia.connection

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.source.Source
import koharia.domain.komga.KomgaReadStatusChapter
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusRepository
import koharia.komga.progress.KomgaProgressOrdering
import koharia.komga.progress.KomgaProgressSuperseded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterProgressOwner
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@RunWith(AndroidJUnit4::class)
class ChapterProgressCommitDeviceTest {
    @Test
    fun pageJournalAndAllChapterAliasesCommitOrRollBackTogether(): Unit = runBlocking(Dispatchers.IO) {
        withChapters { repository, owners ->
            val pages = Injekt.get<koharia.domain.komga.KomgaPageEventRepository>()
            val journal = Injekt.get<koharia.komga.progress.KomgaPageJournal>()
            val key = KomgaReadStatusKey(owners.first().sourceId, "fixture", "book")
            val token = koharia.komga.progress.KomgaProgressRevision(key, 0)
            val before = owners.map { repository.getChapterById(it.chapterId) }
            val changes = owners.map { it to ChapterUpdate(it.chapterId, read = true, lastPageRead = 9) }
            try {
                assertTrue(
                    runCatching {
                        journal.record(token, null, 9, 10, 100, false, {}) {
                            repository.updateForReader(changes) {}
                            error("interrupted local commit")
                        }
                    }.isFailure,
                )
                org.junit.Assert.assertNull(pages.get(key))
                assertEquals(before, owners.map { repository.getChapterById(it.chapterId) })
                journal.record(token, null, 9, 10, 100, false, {}) {
                    repository.updateForReader(changes) {}
                }
                assertEquals(koharia.domain.komga.KomgaReadStatusPhase.HELD, pages.get(key)?.phase)
                owners.forEach { assertTrue(repository.getChapterById(it.chapterId)!!.read) }
            } finally {
                pages.removeConnection(key.connectionId)
            }
        }
    }

    @Test
    fun metadataRefreshPreservesReadingChangesCommittedAfterItsSnapshot(): Unit = runBlocking(Dispatchers.IO) {
        withChapters { repository, owners ->
            val manga = Injekt.get<MangaRepository>().getMangaById(owners.first().mangaId)
            val source = object : Source {
                override val id = manga.source
                override val name = "Metadata fixture"
            }
            for (read in listOf(true, false)) {
                var afterReading = emptyList<Chapter>()
                val interleaved = object : ChapterRepository by repository {
                    override suspend fun getChapterByMangaId(
                        mangaId: Long,
                        applyScanlatorFilter: Boolean,
                    ): List<Chapter> {
                        val snapshot = repository.getChapterByMangaId(mangaId, applyScanlatorFilter)
                        repository.updateForReader(
                            owners.map { owner ->
                                owner to ChapterUpdate(
                                    owner.chapterId,
                                    read = read,
                                    bookmark = read,
                                    lastPageRead = if (read) 9L else 0L,
                                )
                            },
                        ) {}
                        afterReading = repository.getChapterByMangaId(mangaId, applyScanlatorFilter)
                        return snapshot
                    }
                }
                val sync = SyncChaptersWithSource(
                    downloadManager = Injekt.get(),
                    downloadProvider = Injekt.get(),
                    chapterRepository = repository,
                    shouldUpdateDbChapter = Injekt.get(),
                    updateManga = Injekt.get(),
                    getChaptersByMangaId = GetChaptersByMangaId(interleaved),
                    getExcludedScanlators = Injekt.get(),
                    libraryPreferences = Injekt.get(),
                )
                val metadata = repository.getChapterByMangaId(manga.id).map { chapter ->
                    chapter.toSChapter().apply {
                        name = "Updated metadata $read"
                        date_upload = 1234L
                    }
                }
                val fetched = ChapterFetchSession.capture(source, ChapterFetchOrigin.OPEN).fetch { metadata }
                assertTrue(sync.await(fetched, manga).isEmpty())
                afterReading.forEach { saved ->
                    val refreshed = repository.getChapterById(saved.id)!!
                    assertEquals("Updated metadata $read", refreshed.name)
                    assertEquals(1234L, refreshed.dateUpload)
                    assertEquals(saved.read, refreshed.read)
                    assertEquals(saved.bookmark, refreshed.bookmark)
                    assertEquals(saved.lastPageRead, refreshed.lastPageRead)
                    assertEquals(saved.version, refreshed.version)
                    assertEquals(saved.dateFetch, refreshed.dateFetch)
                    assertEquals(saved.mangaId, refreshed.mangaId)
                    assertEquals(saved.url, refreshed.url)
                }
            }
        }
    }

    @Test
    fun invalidationAfterWritesRollsBackEveryChapterAndPropagatesCancellation(): Unit = runBlocking(Dispatchers.IO) {
        withChapters { repository, owners ->
            val updates = owners.map { it to ChapterUpdate(it.chapterId, read = true, lastPageRead = 9) }
            var checks = 0
            var deletionReached = false
            val failure = runCatching {
                UpdateChapter(repository).awaitForReader(updates) {
                    if (++checks == 2) throw CancellationException("Fixture owner replaced")
                }
                deletionReached = true
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertFalse(deletionReached)
            assertEquals(2, checks)
            owners.forEach { assertFalse(repository.getChapterById(it.chapterId)!!.read) }
            repository.updateForReader(updates) {}
            owners.forEach { assertTrue(repository.getChapterById(it.chapterId)!!.read) }
        }
    }

    @Test
    fun replacedResourceConnectionOrMissingChapterCannotReceiveProgress(): Unit = runBlocking(Dispatchers.IO) {
        withChapters { repository, owners ->
            val owner = owners.first()
            for (wrong in listOf(owner.copy(sourceId = -1), owner.copy(url = "other"), owner.copy(mangaId = -1))) {
                assertTrue(
                    runCatching {
                        repository.updateForReader(listOf(wrong to ChapterUpdate(owner.chapterId, read = true))) {}
                    }
                        .exceptionOrNull() is IllegalStateException,
                )
            }
            assertTrue(
                runCatching {
                    repository.updateForReader(listOf(owner to ChapterUpdate(owner.chapterId, url = "other"))) {}
                }.exceptionOrNull() is IllegalArgumentException,
            )
            repository.removeChaptersWithIds(listOf(owner.chapterId))
            assertTrue(
                runCatching {
                    repository.updateForReader(listOf(owner to ChapterUpdate(owner.chapterId, read = true))) {}
                }
                    .exceptionOrNull() is IllegalStateException,
            )
        }
    }

    @Test
    fun explicitUnreadRejectsLateReaderFlagsEvenAfterOrderingControllerIsRecreated(): Unit = runBlocking(
        Dispatchers.IO,
    ) {
        withChapters { repository, owners ->
            val owner = owners.first()
            val intents = Injekt.get<KomgaReadStatusRepository>()
            val ordering = KomgaProgressOrdering(intents, Injekt.get())
            val key = KomgaReadStatusKey(owner.sourceId, "fixture", "book")
            try {
                val opened = ordering.capture(key)
                ordering.record(key, false, 100, null, listOf(KomgaReadStatusChapter(owner.chapterId, owner.url))) {}
                val recreated = KomgaProgressOrdering(intents, Injekt.get())
                val failure = runCatching {
                    repository.updateForReader(
                        listOf(owner to ChapterUpdate(owner.chapterId, read = true, lastPageRead = 9)),
                    ) {
                        recreated.checkCurrent(opened)
                    }
                }.exceptionOrNull()
                assertTrue(failure is KomgaProgressSuperseded)
                assertFalse(repository.getChapterById(owner.chapterId)!!.read)
                assertEquals(0L, repository.getChapterById(owner.chapterId)!!.lastPageRead)
            } finally {
                intents.removeConnection(owner.sourceId)
            }
        }
    }

    private suspend fun withChapters(block: suspend (ChapterRepository, List<ChapterProgressOwner>) -> Unit) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "app.koharia.dev.devicefixture")
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val identity = System.nanoTime()
        val manga = mangas.insertNetworkManga(
            listOf(Manga.create().copy(source = identity, url = "/comic-commit-$identity", title = "Progress fixture")),
        ).single()
        try {
            val entries = chapters.addAll(
                listOf("book", "duplicate").map {
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "https://fixture.invalid/api/v1/books/$it",
                        name = it,
                    )
                },
            )
            block(chapters, entries.map { ChapterProgressOwner(identity, manga.id, it.id, it.url) })
        } finally {
            mangas.deleteMangaById(manga.id)
        }
    }
}
