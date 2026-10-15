package koharia.komga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.domain.epub.model.EpubProgress
import koharia.domain.epub.model.EpubRemoteProgressCache
import koharia.domain.epub.repository.EpubProgressRepository
import koharia.domain.epub.repository.EpubRemoteProgressCacheRepository
import koharia.domain.komga.KomgaReadStatusChapter
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository
import koharia.domain.komga.KomgaRemoteProgressChange
import koharia.domain.komga.KomgaRemoteProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

@RunWith(AndroidJUnit4::class)
class KomgaRemoteProgressDeviceTest {
    @Test
    fun supersededPageStateUnblocksOnlyFreshRemoteSnapshots(): Unit = runBlocking(Dispatchers.IO) {
        withBooks {
            val old = checkNotNull(pages.record(key(first), 0, 3, 10, 100, "opening") {})
            val before = capture()
            val intent = intents.record(key(first), false, 101, "opening", emptyList()) {}
            val sent = checkNotNull(intents.begin(intent, "opening") {})
            intents.settle(sent, KomgaReadStatusPhase.SYNCED, "unread") {}
            assertEquals(old.copy(supersededByRevision = intent.revision), pages.get(key(first)))
            assertEquals(setOf(second.id), remote.apply(before, books.map(::change)) {})
            assertEquals(setOf(first.id), remote.apply(capture(), listOf(change(first))) {})
        }
    }

    @Test
    fun uncertainExplicitRequestBlocksRemoteApplicationAfterLegacyCompletion(): Unit = runBlocking(Dispatchers.IO) {
        withBooks {
            val intent = intents.record(key(first), true, 100, "opening", emptyList()) {}
            val attempt = checkNotNull(intents.begin(intent, "opening") {})
            intents.finish(intent.key, intent.revision, KomgaReadStatusPhase.SYNCED)
            val captured = capture()
            assertEquals(attempt, captured.attempts[key(first).bookId])
            assertFalse(remote.isCurrent(captured, first.id, key(first).bookId) {})
            intents.settle(attempt, KomgaReadStatusPhase.SYNCED, "confirmed") {}
            // Removing the request cannot make the earlier snapshot safe retrospectively.
            assertEquals(setOf(second.id), remote.apply(captured, books.map(::change)) {})
            assertEquals(setOf(first.id), remote.apply(capture(), listOf(change(first))) {})
        }
    }

    @Test
    fun missingExplicitRequestSnapshotIsNotAnImplicitEmptyState(): Unit = runBlocking(Dispatchers.IO) {
        withBooks {
            assertTrue(remote.apply(capture().copy(attempts = emptyMap()), books.map(::change)) {}.isEmpty())
            val own = intents.record(key(first), true, 100, "opening", emptyList()) {}
            val sent = checkNotNull(intents.begin(own, "opening") {})
            val requested = (0..1000).map { "missing-$it" } + key(first).bookId
            val results = intents.attemptsForBooks(manga.source, "fixture-owner", requested)
            assertEquals(requested.toSet(), results.keys)
            assertEquals(sent, results[key(first).bookId])
            assertEquals(1001, results.values.count { it == null })
            assertTrue(intents.attemptsForBooks(manga.source, "other", requested).values.all { it == null })
            assertEquals(mapOf(key(second).bookId to null), remote.captureBook(key(second), second.url) {}.attempts)
        }
    }

    @Test
    fun offlineEventsPresentBeforeOrCreatedDuringRequestCannotBeErasedByLateRemoteState(): Unit = runBlocking(
        Dispatchers.IO,
    ) {
        withBooks {
            val offline = checkNotNull(pages.record(key(first), 0, 2, 10, 100, null) {})
            val before = capture()
            val sent = checkNotNull(pages.begin(offline, "confirmed-opening") {})
            pages.settle(sent, KomgaReadStatusPhase.SYNCED, "after-first") {}
            val during = checkNotNull(pages.record(key(second), 0, 4, 10, 101, "opening") {})
            val later = checkNotNull(pages.begin(during, "opening") {})
            pages.settle(later, KomgaReadStatusPhase.SYNCED, "after-second") {}
            assertTrue(remote.apply(before, books.map(::change)) {}.isEmpty())
            assertTrue(histories.getHistoryByMangaId(manga.id).isEmpty())
            books.forEach { assertFalse(chapters.getChapterById(it.id)!!.read) }
            assertEquals(books.map { it.id }.toSet(), remote.apply(capture(), books.map(::change)) {})
        }
    }

    @Test
    fun unsettledPageStatesBlockPointCacheValidationAndOnlyTheirOwnBooks(): Unit = runBlocking(Dispatchers.IO) {
        withBooks {
            for (phase in listOf(
                KomgaReadStatusPhase.CONFLICT,
                KomgaReadStatusPhase.DENIED,
                KomgaReadStatusPhase.HELD,
            )) {
                pages.removeConnection(manga.source)
                val event = checkNotNull(pages.record(key(first), 0, 2, 10, 100, null) {})
                val attempt = checkNotNull(pages.begin(event, "opening") {})
                pages.settle(attempt, phase, null) {}
                val point = remote.captureChapter(key(first), first.id) {}
                assertFalse(remote.isCurrent(point, first.id, key(first).bookId) {})
                assertTrue(
                    remote.apply(point, listOf(change(first).copy(epubCache = cache(first, "old-server")))) {
                    }.isEmpty(),
                )
                assertNull(epubCache.getByChapterId(first.id))
            }
            assertEquals(setOf(second.id), remote.apply(capture(), books.map(::change)) {})
            assertFalse(chapters.getChapterById(first.id)!!.read)
        }
    }

    @Test
    fun remoteBookIdentityMustMatchTheCapturedChapter(): Unit = runBlocking(Dispatchers.IO) {
        withBooks {
            assertTrue(remote.captureChapter(key(second), first.id) {}.chapters.isEmpty())
            assertTrue(remote.captureBook(key(second), first.url) {}.chapters.isEmpty())
            assertTrue(remote.apply(capture(), listOf(change(first).copy(bookId = key(second).bookId))) {}.isEmpty())
            assertFalse(chapters.getChapterById(first.id)!!.read)
            assertTrue(histories.getHistoryByMangaId(manga.id).isEmpty())
        }
    }

    @Test
    fun pageSnapshotsAreLimitedToRequestedBooksAndExactOwner(): Unit = runBlocking(Dispatchers.IO) {
        withBooks {
            pages.record(key(first), 0, 2, 10, 100, null) {}
            pages.record(key(second).copy(ownerKey = "other"), 0, 3, 10, 100, null) {}
            val point = remote.captureBook(key(second), second.url) {}
            assertEquals(setOf(key(second).bookId), point.pages.keys)
            assertTrue(point.pages.values.single().settled)
            assertEquals(setOf(second.id), remote.apply(point, books.map(::change)) {})
            val requested = (0..1000).map { "missing-$it" } + key(first).bookId
            val bulk = pages.states(manga.source, "fixture-owner", requested)
            assertEquals(requested.toSet(), bulk.keys)
            assertFalse(bulk.getValue(key(first).bookId).settled)
            assertEquals(1001, bulk.values.count { it.settled })
            assertTrue(pages.states(manga.source, "fixture-owner", emptyList()).isEmpty())
        }
    }

    @Test
    fun changedReadingVersionSkipsOnlyThatBookEvenWhenFlagsReturnToTheirOriginalValues(): Unit = runBlocking(
        Dispatchers.IO,
    ) {
        withBooks {
            val before = capture()
            chapters.update(ChapterUpdate(first.id, read = true, lastPageRead = 8))
            chapters.update(ChapterUpdate(first.id, read = false, lastPageRead = 0))
            val readerState = chapters.getChapterById(first.id)
            assertEquals(setOf(second.id), remote.apply(before, books.map(::change)) {})
            assertEquals(readerState, chapters.getChapterById(first.id))
            assertTrue(chapters.getChapterById(second.id)!!.read)
            assertEquals(listOf(second.id), histories.getHistoryByMangaId(manga.id).map { it.chapterId })
        }
    }

    @Test
    fun pendingAtRequestStartAndNewerExplicitActionsRemainExcludedAfterAcknowledgement(): Unit = runBlocking(
        Dispatchers.IO,
    ) {
        withBooks {
            val initial = intents.record(key(first), false, 100, null, listOf(alias(first))) {}
            val before = capture()
            assertTrue(intents.finish(initial.key, initial.revision, KomgaReadStatusPhase.SYNCED))
            val later = intents.record(key(second), false, 200, null, listOf(alias(second))) {}
            assertTrue(intents.finish(later.key, later.revision, KomgaReadStatusPhase.SYNCED))
            assertTrue(remote.apply(before, books.map(::change)) {}.isEmpty())
            books.forEach { assertFalse(chapters.getChapterById(it.id)!!.read) }
            assertTrue(histories.getHistoryByMangaId(manga.id).isEmpty())
            assertEquals(books.map { it.id }.toSet(), remote.apply(capture(), books.map(::change)) {})
        }
    }

    @Test
    fun concurrentLocalOrRemoteEpubStateAndHistoryRejectLateClears(): Unit = runBlocking(Dispatchers.IO) {
        withBooks {
            var before = capture()
            val local = EpubProgress(first.id, manga.id, first.url, "fixture-locator", 0.5, 5, Date(900), null)
            epub.upsertProgress(local)
            val cached = cache(second, "remote-locator")
            epubCache.upsert(cached)
            val clears = books.map { change(it).copy(epubCache = cache(it, null)) }
            assertTrue(remote.apply(before, clears) {}.isEmpty())
            assertEquals(local, epub.getProgress(first.id))
            assertEquals(cached, epubCache.getByChapterId(second.id))
            before = capture()
            histories.upsertHistory(HistoryUpdate(first.id, Date(1200), 50))
            assertTrue(remote.apply(before, listOf(change(first))) {}.isEmpty())
            assertFalse(chapters.getChapterById(first.id)!!.read)
            assertEquals(50L, histories.getHistoryByMangaId(manga.id).single().readDuration)
        }
    }

    @Test
    fun guardedTransactionRollsBackFlagsHistoryAndCacheAndRemoteHistoryNeverMovesBackwards(): Unit = runBlocking(
        Dispatchers.IO,
    ) {
        withBooks {
            histories.upsertHistory(HistoryUpdate(first.id, Date(1200), 50))
            val cached = cache(first, "remote-locator")
            epubCache.upsert(cached)
            val before = capture()
            val changes = books.map { change(it).copy(epubCache = cache(it, null)) }
            var checks = 0
            assertTrue(
                runCatching {
                    remote.apply(before, changes) {
                        if (++checks == 2) throw CancellationException("Fixture session replaced")
                    }
                }.exceptionOrNull() is CancellationException,
            )
            books.forEach { assertFalse(chapters.getChapterById(it.id)!!.read) }
            assertEquals(cached, epubCache.getByChapterId(first.id))
            assertNull(epubCache.getByChapterId(second.id))
            assertEquals(1, histories.getHistoryByMangaId(manga.id).size)
            assertEquals(books.map { it.id }.toSet(), remote.apply(before, changes) {})
            val history = histories.getHistoryByMangaId(manga.id).first { it.chapterId == first.id }
            assertEquals(Date(1200), history.readAt)
            assertEquals(50L, history.readDuration)
            assertNull(epubCache.getByChapterId(first.id)!!.locatorJson)
        }
    }

    @Test
    fun singleBookCaptureKeepsAliasesAndExcludesOtherAccountsAndResources(): Unit = runBlocking(Dispatchers.IO) {
        withBooks {
            val other = key(first).copy(ownerKey = "another-account")
            intents.record(other, false, 100, null, emptyList()) {}
            intents.record(key(second), false, 100, null, emptyList()) {}
            val before = remote.captureBook(key(first), first.url) {}
            assertEquals(setOf(first.id), before.chapters.keys)
            assertTrue(before.intents.isEmpty())
            assertEquals(setOf(first.id), remote.apply(before, books.map(::change)) {})
            assertFalse(chapters.getChapterById(second.id)!!.read)
        }
    }

    private class Fixture(val manga: Manga, val books: List<Chapter>) {
        val first get() = books[0]
        val second get() = books[1]
        val remote = Injekt.get<KomgaRemoteProgressRepository>()
        val intents = Injekt.get<KomgaReadStatusRepository>()
        val pages = Injekt.get<koharia.domain.komga.KomgaPageEventRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val epub = Injekt.get<EpubProgressRepository>()
        val epubCache = Injekt.get<EpubRemoteProgressCacheRepository>()
        val histories = Injekt.get<HistoryRepository>()
        fun key(
            chapter: Chapter,
        ) = KomgaReadStatusKey(manga.source, "fixture-owner", chapter.url.substringAfterLast('/'))
        fun alias(chapter: Chapter) = KomgaReadStatusChapter(chapter.id, chapter.url)
        suspend fun capture() = remote.capture(manga.source, "fixture-owner", listOf(manga.id)) {}
        fun change(
            chapter: Chapter,
        ) = KomgaRemoteProgressChange(chapter.id, key(chapter).bookId, true, 9, Date(1000), null)
        fun cache(chapter: Chapter, locator: String?) = EpubRemoteProgressCache(
            chapter.id, manga.id, chapter.url, locator, null, null, null, Date(1000), null,
        )
    }

    private suspend fun withBooks(block: suspend Fixture.() -> Unit) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "app.koharia.dev.devicefixture")
        val mangas = Injekt.get<MangaRepository>()
        val identity = System.nanoTime()
        val manga = mangas.insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = identity,
                    url = "https://fixture.invalid/api/v1/series/$identity",
                    title = "Remote progress fixture",
                ),
            ),
        ).single()
        try {
            val books = Injekt.get<ChapterRepository>().addAll(
                listOf("one", "two").map { book ->
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "https://fixture.invalid/api/v1/books/$book",
                        name = book,
                    )
                },
            )
            Fixture(manga, books).block()
        } finally {
            Injekt.get<KomgaReadStatusRepository>().removeConnection(identity)
            Injekt.get<koharia.domain.komga.KomgaPageEventRepository>().removeConnection(identity)
            mangas.deleteMangaById(manga.id)
        }
    }
}
