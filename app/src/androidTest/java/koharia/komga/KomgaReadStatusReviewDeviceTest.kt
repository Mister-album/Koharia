package koharia.komga

import androidx.test.platform.app.InstrumentationRegistry
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository
import koharia.komga.progress.KomgaProgressBaseline
import koharia.komga.progress.KomgaProgressOrdering
import koharia.komga.progress.KomgaReadStatusReview
import koharia.komga.progress.KomgaReadStatusTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KomgaReadStatusReviewDeviceTest {
    private val database get() = Injekt.get<Database>()
    private val intents get() = Injekt.get<KomgaReadStatusRepository>()
    private val pages get() = Injekt.get<KomgaPageEventRepository>()
    private val review get() = KomgaReadStatusReview(database, intents, pages, KomgaProgressOrdering(intents, pages))

    @Test
    fun reviewIsReadOnlyAndReissueCannotAcknowledgeOldRequestOrOtherOwner(): Unit = fixture { key, transport ->
        val foreign = key.copy(ownerKey = "other-owner")
        val other = intents.record(foreign, false, 100, null, emptyList()) {}
        val first = intents.record(key, true, 100, transport.remote.encode(), emptyList()) {}
        val request = checkNotNull(intents.begin(first, transport.remote.encode()) {})
        val desired = intents.record(key, false, 101, null, emptyList()) {}
        val action = review
        val compared = checkNotNull(action.compare(key, transport, {}, { emptyList() }))
        assertEquals(desired, intents.get(key))
        assertEquals(request, intents.attempt(key))
        assertEquals(1, transport.reads)
        val next = action.reissue(compared, transport, {}, { emptyList() })
        assertEquals(desired.revision + 1, next.revision)
        assertFalse(next.read)
        assertEquals(KomgaReadStatusPhase.PENDING, next.phase)
        assertEquals(transport.remote.encode(), next.baseline)
        assertEquals(2, transport.reads)
        assertNull(intents.attempt(key))
        assertFalse(intents.settle(request, KomgaReadStatusPhase.SYNCED, transport.remote.encode()) {})
        assertEquals(next, intents.get(key))
        assertEquals(other, intents.get(foreign))
        assertTrue(runCatching { action.reissue(compared, transport, {}, { emptyList() }) }.isFailure)
        assertEquals(next, intents.get(key))
    }

    @Test
    fun changedRemoteRetainsPageAttemptUntilAnExplicitFreshComparison(): Unit = fixture { key, transport ->
        val page = checkNotNull(pages.record(key, 0, 3, 10, 100, transport.remote.encode()) {})
        val request = checkNotNull(pages.begin(page, transport.remote.encode()) {})
        val desired = intents.record(key, false, 101, null, emptyList()) {}
        val action = review
        val compared = checkNotNull(action.compare(key, transport, {}, { emptyList() }))
        transport.remote = transport.remote.copy(progressPresent = true, page = 5, progressModified = "changed")
        assertTrue(runCatching { action.reissue(compared, transport, {}, { emptyList() }) }.isFailure)
        assertEquals(request, pages.attempt(key))
        assertEquals(page, pages.get(key))
        assertEquals(desired, intents.get(key))
        val fresh = checkNotNull(action.compare(key, transport, {}, { emptyList() }))
        val next = action.reissue(fresh, transport, {}, { emptyList() })
        assertNull(pages.attempt(key))
        // The newer explicit revision owns the decision; an obsolete page must not be relabelled or acknowledged.
        assertEquals(page, pages.get(key))
        assertNull(pages.begin(page, transport.remote.encode()) {})
        assertEquals(KomgaReadStatusPhase.PENDING, next.phase)
        assertFalse(pages.settle(request, KomgaReadStatusPhase.SYNCED, transport.remote.encode()) {})
    }

    @Test
    fun newerIntentAndOwnerInvalidationRejectReviewWithoutRetiringAnything(): Unit = fixture { key, transport ->
        val first = intents.record(key, true, 100, transport.remote.encode(), emptyList()) {}
        val request = checkNotNull(intents.begin(first, transport.remote.encode()) {})
        val action = review
        val compared = checkNotNull(action.compare(key, transport, {}, { emptyList() }))
        val newer = intents.record(key, false, 101, null, emptyList()) {}
        assertTrue(runCatching { action.reissue(compared, transport, {}, { emptyList() }) }.isFailure)
        assertEquals(1, transport.reads)
        assertEquals(newer, intents.get(key))
        assertEquals(request, intents.attempt(key))
        val fresh = checkNotNull(action.compare(key, transport, {}, { emptyList() }))
        assertTrue(
            runCatching { action.reissue(fresh, transport, { error("Owner replaced") }, { emptyList() }) }.isFailure,
        )
        assertEquals(newer, intents.get(key))
        assertEquals(request, intents.attempt(key))
    }

    @Test
    fun interruptedCommitRollsBackRequestRetirementAndNewIntentTogether(): Unit = fixture { key, transport ->
        val first = intents.record(key, true, 100, transport.remote.encode(), emptyList()) {}
        val request = checkNotNull(intents.begin(first, transport.remote.encode()) {})
        val action = review
        val compared = checkNotNull(action.compare(key, transport, {}, { emptyList() }))
        assertTrue(
            runCatching {
                database.transaction {
                    action.reissue(compared, transport, {}, { emptyList() })
                    error("Interrupted before outer transaction commit")
                }
            }.isFailure,
        )
        assertEquals(first, intents.get(key))
        assertEquals(request, intents.attempt(key))
    }

    @Test
    fun chapterChangeDuringServerReadRejectsDecisionThenFreshReviewUpdatesAllAliases(): Unit = fixture {
            key,
            transport,
        ->
        val mangas = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
        val chapters = Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>()
        val url = "https://review-fixture.invalid/api/v1/books/book"
        val owners = mangas.insertNetworkManga(
            (1..2).map {
                tachiyomi.domain.manga.model.Manga.create().copy(source = key.connectionId, url = "series/$it")
            },
        )
        try {
            val aliases = chapters.addAll(
                owners.map { tachiyomi.domain.chapter.model.Chapter.create().copy(mangaId = it.id, url = url) },
            )
            val saved = intents.record(key, true, 100, transport.remote.encode(), emptyList()) {}
            val request = checkNotNull(intents.begin(saved, transport.remote.encode()) {})
            val action = review
            val capture = suspend { chapters.getChaptersByUrlAndSourceId(url, key.connectionId) }
            val compared = checkNotNull(action.compare(key, transport, {}, capture))
            transport.onRead = {
                chapters.update(tachiyomi.domain.chapter.model.ChapterUpdate(aliases.first().id, lastPageRead = 4))
                transport.onRead = {}
            }
            assertTrue(runCatching { action.reissue(compared, transport, {}, capture) }.isFailure)
            assertEquals(saved, intents.get(key))
            assertEquals(request, intents.attempt(key))
            assertEquals(4L, chapters.getChapterById(aliases.first().id)?.lastPageRead)
            assertTrue(capture().none { it.read })
            val fresh = checkNotNull(action.compare(key, transport, {}, capture))
            action.reissue(fresh, transport, {}, capture)
            assertTrue(capture().all { it.read })
            assertNull(intents.attempt(key))
        } finally {
            owners.forEach { mangas.deleteMangaById(it.id) }
        }
    }

    @Test
    fun pageOnlyAndForeignImportedRecordsAreNotAdoptedAsExplicitActions(): Unit = fixture { key, transport ->
        val page = checkNotNull(pages.record(key, 0, 3, 10, 100, null) {})
        assertNull(review.compare(key, transport, {}, { emptyList() }))
        assertEquals(0, transport.reads)
        assertEquals(page, pages.get(key))
        assertTrue(
            runCatching {
                review.compare(key.copy(ownerKey = "restored:fixture"), transport, {}, { emptyList() })
            }.isFailure,
        )
    }

    private class Transport(book: String) : KomgaReadStatusTransport {
        var remote = KomgaProgressBaseline(1, book, "hash", null, 100, 10, false, false, false, null, false, null, null)
        var reads = 0
        var onRead: suspend () -> Unit = {}
        override suspend fun read() = remote.also {
            reads++
            onRead()
        }
        override suspend fun write(page: Int, completed: Boolean): Unit = error("Review must not send a page")
        override suspend fun mark(read: Boolean): Unit = error("Review must not write before durable dispatch")
    }

    private fun fixture(block: suspend (KomgaReadStatusKey, Transport) -> Unit): Unit = runBlocking(Dispatchers.IO) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "app.koharia.dev.devicefixture")
        val key = KomgaReadStatusKey(System.nanoTime(), "review-fixture", "book")
        check(!Injekt.get<koharia.connection.ConnectionPreferences>().isKnownConnectionId(key.connectionId))
        try {
            block(key, Transport(key.bookId))
        } finally {
            pages.removeConnection(key.connectionId)
            intents.removeConnection(key.connectionId)
        }
    }
}
