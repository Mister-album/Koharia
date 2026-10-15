package koharia.komga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.connection.ConnectionPreferences
import koharia.data.komga.KomgaReadStatusRepositoryImpl
import koharia.domain.komga.KomgaReadStatusChapter
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class KomgaReadStatusRepositoryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val database get() = Injekt.get<Database>()
    private val chapters get() = Injekt.get<ChapterRepository>()
    private val mangas get() = Injekt.get<MangaRepository>()
    private val repository get() = Injekt.get<KomgaReadStatusRepository>()

    @Test
    fun backupKeepsChapterFlagsAndIntentsInOneSnapshot(): Unit = runBlocking(Dispatchers.IO) {
        requireFixture()
        val profiles = Injekt.get<koharia.connection.ConnectionProfileManager>()
        val preferences = Injekt.get<ConnectionPreferences>()
        val active = preferences.activeConnectionId.get()
        val profile = profiles.add("komga", "Read-status backup fixture")
        val created = mutableListOf<Manga>()
        val file = File(context.cacheDir, "komga-status-backup-${System.nanoTime()}.kohariabackup")
        val pages = Injekt.get<koharia.domain.komga.KomgaPageEventRepository>()
        try {
            check(file.createNewFile())
            val chapter = newChapter(profile.id, "komga-backup-${profile.id}", "series", created)
            mangas.update(tachiyomi.domain.manga.model.MangaUpdate(chapter.mangaId, favorite = true))
            val key = KomgaReadStatusKey(profile.id, "backup-owner", "book")
            val aliases = listOf(KomgaReadStatusChapter(chapter.id, chapter.url))
            val firstIntent = repository.record(key, false, 100, null, aliases) {}
            repository.begin(firstIntent, "before-request") {}
            val pageKey = key.copy(ownerKey = "earlier-page-owner", bookId = "page-book")
            val firstPage = checkNotNull(pages.record(pageKey, 0, 2, 10, 100, "before-page") {})
            pages.begin(firstPage, "before-page") {}
            pages.record(pageKey, 0, 4, 10, 101, "before-page") {}
            val options = eu.kanade.tachiyomi.data.backup.create.BackupOptions(
                categories = false,
                tracking = false,
                history = false,
                readEntries = false,
                appSettings = false,
                connectionSettings = false,
                privateSettings = false,
            )
            val creator = eu.kanade.tachiyomi.data.backup.create.BackupCreator(context, false)
            val decoder = eu.kanade.tachiyomi.data.backup.BackupDecoder(context)
            coroutineScope {
                val writes = async {
                    repeat(12) {
                        repository.record(key, it % 2 == 0, 101L + it, null, aliases) {}
                        kotlinx.coroutines.yield()
                    }
                }
                repeat(3) {
                    creator.backup(android.net.Uri.fromFile(file), options)
                    val backup = decoder.decode(android.net.Uri.fromFile(file))
                    val intent = backup.backupKomgaReadStatus.single { it.connectionId == profile.id }
                    val saved = backup.backupManga.single { it.source == profile.id }.chapters.single()
                    val request = backup.backupKomgaReadStatusAttempts.single { it.connectionId == profile.id }
                    val desiredPage = backup.backupKomgaPages.single { it.connectionId == profile.id }
                    val pageRequest = backup.backupKomgaPageAttempts.single { it.connectionId == profile.id }
                    assertEquals(intent.ownerSlot, request.ownerSlot)
                    assertEquals(firstIntent.revision, request.revision)
                    assertFalse(request.read)
                    assertEquals(desiredPage.ownerSlot, pageRequest.ownerSlot)
                    assertTrue(desiredPage.ownerSlot != intent.ownerSlot)
                    assertEquals(2L, desiredPage.generation)
                    assertEquals(1L, pageRequest.generation)
                    assertEquals(intent.read, saved.read)
                    assertTrue(backup.backupConnections.any { it.id == profile.id && it.providerId == "komga" })
                    if (!intent.read) assertEquals(0L, saved.lastPageRead)
                }
                writes.await()
            }
            creator.backup(android.net.Uri.fromFile(file), options.copy(chapters = false))
            assertTrue(decoder.decode(android.net.Uri.fromFile(file)).backupKomgaReadStatus.isEmpty())
            val noChapters = decoder.decode(android.net.Uri.fromFile(file))
            assertTrue(noChapters.backupKomgaPages.isEmpty())
            assertTrue(noChapters.backupKomgaPageAttempts.isEmpty())
            assertTrue(noChapters.backupKomgaReadStatusAttempts.isEmpty())
        } finally {
            pages.removeConnection(profile.id)
            repository.removeConnection(profile.id)
            created.forEach { mangas.deleteMangaById(it.id) }
            profiles.remove(profile.id).getOrThrow()
            preferences.activeConnectionId.set(active)
            file.delete()
        }
    }

    @Test
    fun transactionsPreserveOwnershipAndNewerIntents(): Unit = runBlocking(Dispatchers.IO) {
        requireFixture()
        val sourceId = System.nanoTime()
        val prefix = "komga-intent-$sourceId"
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(sourceId))
        val created = mutableListOf<Manga>()
        try {
            val local = newChapter(sourceId, prefix, "series", created)
            val alias = newChapter(sourceId, prefix, "readlist", created)
            val foreign = newChapter(sourceId + 1, prefix, "foreign", created)
            val snapshots = listOf(local, alias).map { KomgaReadStatusChapter(it.id, it.url) }
            val key = KomgaReadStatusKey(sourceId, "owner-a", "book")
            val otherAccount = key.copy(ownerKey = "owner-b")
            val otherConnection = key.copy(connectionId = sourceId + 1)
            val first = repository.record(key, true, 100, "baseline-a", snapshots) {}
            assertEquals(1L, first.revision)
            repository.record(otherAccount, true, 100, "baseline-b", emptyList()) {}
            repository.record(otherConnection, true, 100, "baseline-c", emptyList()) {}
            seedProgress(local)

            var checks = 0
            assertTrue(
                runCatching {
                    repository.record(key, false, 101, null, snapshots) {
                        check(++checks < 2) { "Injected owner change before commit" }
                    }
                }.isFailure,
            )
            assertEquals(first, repository.get(key))
            assertTrue(chapters.getChapterById(local.id)!!.read)
            assertNotNull(database.epub_progressQueries.getByChapterId(local.id).awaitAsOneOrNull())

            for (invalid in listOf(
                KomgaReadStatusChapter(foreign.id, foreign.url),
                KomgaReadStatusChapter(local.id, "changed-url"),
                KomgaReadStatusChapter(Long.MAX_VALUE, local.url),
            )) {
                assertTrue(
                    runCatching {
                        repository.record(key, false, 101, null, snapshots + invalid) {}
                    }.isFailure,
                )
                assertEquals(first, repository.get(key))
            }

            val unread = repository.record(key, false, 100, "baseline-a", snapshots) {}
            assertEquals(2L, unread.revision)
            for (chapter in listOf(local, alias)) {
                val saved = chapters.getChapterById(chapter.id)!!
                assertFalse(saved.read)
                assertEquals(0L, saved.lastPageRead)
            }
            assertEquals(9L, chapters.getChapterById(foreign.id)!!.lastPageRead)
            assertNull(database.epub_progressQueries.getByChapterId(local.id).awaitAsOneOrNull())
            assertNull(database.epub_remote_progress_cacheQueries.getByChapterId(local.id).awaitAsOneOrNull())
            assertNull(database.tts_progressQueries.getByChapterId(local.id).awaitAsOneOrNull())
            assertEquals(
                "keep-note",
                database.epub_bookmarkQueries.getByChapterId(local.id).awaitAsList().single().note,
            )
            assertEquals(
                33L,
                database.historyQueries.getHistoryByMangaId(local.mangaId).awaitAsList().single().time_read,
            )

            assertFalse(repository.finish(key, first.revision, KomgaReadStatusPhase.SYNCED))
            assertEquals(unread, KomgaReadStatusRepositoryImpl(database).get(key))
            assertEquals(listOf(unread), repository.pending(sourceId, "owner-a"))
            assertEquals("baseline-b", repository.get(otherAccount)!!.baseline)
            assertEquals("baseline-c", repository.get(otherConnection)!!.baseline)
            assertTrue(repository.finish(key, unread.revision, KomgaReadStatusPhase.DENIED))
            assertFalse(repository.finish(key, unread.revision, KomgaReadStatusPhase.SYNCED))
            assertTrue(repository.pending(sourceId, "owner-a").isEmpty())

            val reissued = repository.record(key, true, 99, null, snapshots) {}
            assertEquals(3L, reissued.revision)
            assertTrue(repository.finish(key, reissued.revision, KomgaReadStatusPhase.SYNCED))
            repository.holdConnection(sourceId)
            assertEquals(KomgaReadStatusPhase.SYNCED, repository.get(key)!!.phase)
            assertEquals(KomgaReadStatusPhase.HELD, repository.get(otherAccount)!!.phase)
            assertEquals(KomgaReadStatusPhase.PENDING, repository.get(otherConnection)!!.phase)

            val concurrent = key.copy(bookId = "unindexed")
            val revisions = coroutineScope {
                (1..12).map { value ->
                    async { repository.record(concurrent, value % 2 == 0, 100, null, emptyList()) {}.revision }
                }.awaitAll()
            }
            assertEquals((1L..12L).toSet(), revisions.toSet())
            assertEquals(12L, repository.get(concurrent)!!.revision)

            val restored = reissued.copy(
                key = key.copy(ownerKey = "restored:fixture:0"),
                baseline = null,
                phase = KomgaReadStatusPhase.HELD,
            )
            repository.restoreHeld(listOf(restored))
            repository.restoreHeld(listOf(restored.copy(read = false, revision = 1)))
            assertEquals(restored, repository.get(restored.key))
            assertTrue(repository.pending(sourceId, restored.key.ownerKey).isEmpty())
            assertTrue(repository.unsettled().any { it == restored })
            assertFalse(repository.unsettled().any { it.key == key })
            assertTrue(chapters.getChapterById(local.id)!!.read)
            assertTrue(
                runCatching {
                    repository.restoreHeld(listOf(restored.copy(phase = KomgaReadStatusPhase.PENDING)))
                }.isFailure,
            )
            assertEquals(restored, repository.get(restored.key))
        } finally {
            repository.removeConnection(sourceId)
            repository.removeConnection(sourceId + 1)
            created.forEach { mangas.deleteMangaById(it.id) }
        }
    }

    /** Run prepare/interrupt and recover as separate instrumentation invocations with an intervening fixture force-stop. */
    @Test
    fun processRecovery(): Unit = runBlocking(Dispatchers.IO) {
        requireFixture()
        val phase = InstrumentationRegistry.getArguments().getString("komgaReadStatusPhase")
        assumeTrue(phase in setOf("prepare", "interrupt", "recover"))
        val checkpoint = context.getSharedPreferences(CHECKPOINT, 0)
        val ready = File(context.cacheDir, READY)
        if (phase == "recover") {
            val sourceId = checkpoint.getLong("source", -1)
            val mangaId = checkpoint.getLong("manga", -1)
            val chapterId = checkpoint.getLong("chapter", -1)
            val interrupted = checkpoint.getBoolean("interrupted", false)
            check(sourceId > 0 && mangaId > 0 && chapterId > 0)
            val prefix = "komga-restart-$sourceId"
            check(mangas.getMangaById(mangaId).let { it.source == sourceId && it.title == prefix })
            try {
                val key = KomgaReadStatusKey(sourceId, "restart-owner", "book")
                val row = repository.get(key)
                val chapter = chapters.getChapterById(chapterId)!!
                if (interrupted) {
                    assertNull(row)
                    assertTrue(chapter.read)
                    assertEquals(9L, chapter.lastPageRead)
                } else {
                    assertNotNull(row)
                    assertFalse(row!!.read)
                    assertEquals(KomgaReadStatusPhase.PENDING, row.phase)
                    assertFalse(chapter.read)
                    assertEquals(0L, chapter.lastPageRead)
                }
            } finally {
                repository.removeConnection(sourceId)
                mangas.deleteMangaById(mangaId)
                context.deleteSharedPreferences(CHECKPOINT)
                ready.delete()
            }
            return@runBlocking
        }
        check(checkpoint.all.isEmpty()) { "Recover the preceding checkpoint before preparing another" }
        check(!ready.exists())
        val sourceId = System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(sourceId))
        val chapter = newChapter(sourceId, "komga-restart-$sourceId", "series", mutableListOf())
        check(
            checkpoint.edit().putLong("source", sourceId).putLong("manga", chapter.mangaId)
                .putLong("chapter", chapter.id).putBoolean("interrupted", phase == "interrupt").commit(),
        )
        var checks = 0
        repository.record(
            KomgaReadStatusKey(sourceId, "restart-owner", "book"),
            false,
            100,
            null,
            listOf(KomgaReadStatusChapter(chapter.id, chapter.url)),
        ) {
            if (++checks == 2 && phase == "interrupt") {
                ready.writeText("uncommitted")
                CountDownLatch(1).await(45, TimeUnit.SECONDS)
                error("Host did not interrupt the fixture transaction")
            }
        }
    }

    private fun requireFixture() {
        check(context.packageName == "app.koharia.dev.devicefixture")
    }

    private suspend fun newChapter(source: Long, prefix: String, suffix: String, created: MutableList<Manga>): Chapter {
        val manga = mangas.insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = source,
                    url = "/$prefix/$suffix",
                    title = prefix,
                ),
            ),
        ).single().also(created::add)
        return chapters.addAll(
            listOf(
                Chapter.create().copy(
                    mangaId = manga.id,
                    url = "https://komga.test/api/v1/books/book",
                    name = "Fixture",
                    read = true,
                    lastPageRead = 9,
                ),
            ),
        ).single()
    }

    private suspend fun seedProgress(chapter: Chapter) {
        database.epub_progressQueries.upsert(chapter.id, chapter.mangaId, chapter.url, "{}", 0.5, 4, Date(100), null)
        database.epub_remote_progress_cacheQueries.upsert(
            chapter.id, chapter.mangaId, chapter.url, "{}", 0.5, 4, Date(100), Date(100), null,
        )
        database.tts_progressQueries.upsert(chapter.id, chapter.mangaId, 8, Date(100))
        database.epub_bookmarkQueries.insert(chapter.id, chapter.mangaId, "{}", "Fixture", 0.5, "keep-note", Date(100))
        database.historyQueries.upsert(chapter.id, Date(100), 33)
    }

    private companion object {
        const val CHECKPOINT = "komga_read_status_test_checkpoint"
        const val READY = "komga-read-status-interrupt-ready"
    }
}
