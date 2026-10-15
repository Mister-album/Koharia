package koharia.connection

import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.domain.kavita.KavitaOperation
import koharia.domain.kavita.KavitaRepository
import koharia.domain.lanraragi.LanraragiReadState
import koharia.domain.lanraragi.LanraragiRepository
import koharia.domain.smanga.SmangaCacheEntry
import koharia.domain.smanga.SmangaReadState
import koharia.domain.smanga.SmangaRepository
import koharia.domain.suwayomi.SuwayomiOperation
import koharia.domain.suwayomi.SuwayomiRepository
import koharia.kavita.KavitaAccount
import koharia.kavita.KavitaChapterRef
import koharia.kavita.KavitaProgress
import koharia.kavita.KavitaReadingState
import koharia.source.kavita.KavitaPreferences
import koharia.source.kavita.KavitaSource
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import koharia.source.smanga.SmangaPreferences
import koharia.source.smanga.SmangaSource
import koharia.source.suwayomi.SuwayomiPreferences
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiAuthMode
import koharia.suwayomi.SuwayomiReadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class ProviderProgressStatusDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val kavita get() = Injekt.get<KavitaRepository>()
    private val suwayomi get() = Injekt.get<SuwayomiRepository>()
    private val smanga get() = Injekt.get<SmangaRepository>()
    private val lanraragi get() = Injekt.get<LanraragiRepository>()

    @Test
    fun lanraragiRestoredHoldSurvivesReaderAndExplicitActions(): Unit = fixture { id, address ->
        LanraragiPreferences(id).save(address, "fixture")
        val source = LanraragiSource(context, LibraryConnectionProfile(id, "lanraragi", "Restore fixture"))
        val mangas = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
        val chapters = Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>()
        val url = "/lanraragi/$id/archive/book"
        val manga = mangas.insertNetworkManga(
            listOf(tachiyomi.domain.manga.model.Manga.create().copy(source = id, url = url)),
        ).single()
        try {
            val chapter = chapters.addAll(
                listOf(
                    tachiyomi.domain.chapter.model.Chapter.create().copy(
                        mangaId = manga.id,
                        url = url,
                        name = "fixture",
                    ),
                ),
            ).single()
            val adapter = eu.kanade.tachiyomi.data.backup.providers.LanraragiStateBackupAdapter()
            val backup = eu.kanade.tachiyomi.data.backup.providers.BackupLanraragiState(url, 2, 10, 100)
            adapter.restore(manga.id, mapOf(url to chapter.id), listOf(backup))
            assertTrue(lanraragi.readState(id, "book")!!.requiresConfirmation)
            assertTrue(lanraragi.pendingReadStates(id).isEmpty())
            assertEquals(
                setOf(ConnectionProgressSyncStatus.HELD),
                source.observeProgressSyncStatus(listOf(url)).first {
                    it.isNotEmpty()
                }[url],
            )
            source.recordLocalPageProgress(url, 4, 10, 200, initialPage = false)
            val reread = lanraragi.readState(id, "book")!!
            assertTrue(reread.requiresConfirmation)
            assertTrue(reread.pending)
            assertEquals(4, source.pullPageProgress(url, chapter.memo)!!.pageIndex)
            lanraragi.acknowledge(id, reread)
            assertTrue(lanraragi.readState(id, "book")!!.pending)
            adapter.restore(manga.id, mapOf(url to chapter.id), listOf(backup))
            assertEquals(reread, lanraragi.readState(id, "book"))
            assertEquals(ConnectionReadStatus.HELD, source.setChapterReadStatus(url, false))
            assertTrue(lanraragi.readState(id, "book")!!.localUnread)
            assertEquals(ConnectionReadStatus.HELD, source.setChapterReadStatus(url, true))
            val failure = runCatching { source.acceptRemotePageProgress(url, 8, 10, 300) }.exceptionOrNull()
            assertEquals(ConnectionReadStatus.HELD, (failure as ConnectionReadStatusException).status)
            val held = lanraragi.readState(id, "book")!!
            lanraragi.record(id, held.copy(requiresConfirmation = false))
            assertTrue(lanraragi.readState(id, "book")!!.requiresConfirmation)
            assertTrue(adapter.capture(manga.id, mapOf(chapter.id to url)).single().requiresConfirmation)
            assertTrue(lanraragi.pendingReadStates(id).isEmpty())
        } finally {
            source.close()
            mangas.deleteMangaById(manga.id)
        }
    }

    @Test
    fun smangaRestoreHoldsSelectedPendingStateAndPreservesNewerLiveState(): Unit = fixture { id, _ ->
        val account = "restore-fixture"
        val mangas = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
        val chapters = Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>()
        val manga = mangas.insertNetworkManga(
            listOf(tachiyomi.domain.manga.model.Manga.create().copy(source = id, url = "/smanga/$id/$account/manga/2")),
        ).single()
        try {
            val url = "/smanga/$id/$account/chapter/1/2/3.pages"
            val chapter = chapters.addAll(
                listOf(
                    tachiyomi.domain.chapter.model.Chapter.create().copy(
                        mangaId = manga.id,
                        url = url,
                        name = "fixture",
                    ),
                ),
            ).single()
            val adapter = eu.kanade.tachiyomi.data.backup.providers.SmangaStateBackupAdapter()
            val state = eu.kanade.tachiyomi.data.backup.providers.BackupSmangaState(
                url,
                eu.kanade.tachiyomi.data.backup.providers.BackupSmangaReadState(2, 10, false, 100, 1, true),
            )
            adapter.restore(manga.id, mapOf(url to chapter.id), listOf(state))
            assertTrue(smanga.readState(id, account, 3)!!.pending)
            assertTrue(
                Json.decodeFromString<koharia.smanga.SmangaUploadConfirmation>(
                    smanga.cache(id, account, "reading-confirmation", "3")!!.payload,
                ).required,
            )
            smanga.putCache(id, account, "reading-confirmation", SmangaCacheEntry("3", """{"required":false}""", 1))
            val newer = smanga.readState(id, account, 3)!!.copy(readAt = 200, pageIndex = 4)
            smanga.putReadState(id, account, newer)
            adapter.restore(
                manga.id,
                mapOf(url to chapter.id),
                listOf(state.copy(confirmationPayload = """{"required":true}""")),
            )
            assertEquals(newer, smanga.readState(id, account, 3))
            assertFalse(
                Json.decodeFromString<koharia.smanga.SmangaUploadConfirmation>(
                    smanga.cache(id, account, "reading-confirmation", "3")!!.payload,
                ).required,
            )
            adapter.restore(
                manga.id,
                mapOf(url to chapter.id),
                listOf(
                    state.copy(
                        readState = state.readState!!.copy(readAt = 300),
                        confirmationPayload = """{"required":false}""",
                    ),
                ),
            )
            assertTrue(
                Json.decodeFromString<koharia.smanga.SmangaUploadConfirmation>(
                    smanga.cache(id, account, "reading-confirmation", "3")!!.payload,
                ).required,
            )
            assertEquals(300L, smanga.readState(id, account, 3)!!.readAt)
        } finally {
            mangas.deleteMangaById(manga.id)
        }
    }

    @Test
    fun kavitaStatusObservesConflictAcknowledgementAndSameObjectAccountReload(): Unit = fixture { id, address ->
        val prefs = KavitaPreferences(id)
        prefs.save(address, "fixture", KavitaAccount(1, "fixture", roles = listOf("Admin"), kavitaVersion = "0.8.0"))
        val source = KavitaSource(context, LibraryConnectionProfile(id, "kavita", "Status fixture"))
        try {
            val account = source.session().accountKey
            val ref = KavitaChapterRef(1, 2, 3, 4, 1)
            val url = source.session().identity.chapter(ref)
            val state = KavitaReadingState(ref, KavitaProgress(chapterId = 4, pageNum = 2), 10)
            val operation = KavitaOperation("progress/4", Json.encodeToString(state), 1, true)
            kavita.putOperation(id, account, operation)
            kavita.putOperation(id, "foreign", operation.copy(payload = "broken"))
            observe(source.observeProgressSyncStatus(listOf(url))) { updates ->
                updates.expect(url, ConnectionProgressSyncStatus.PENDING)
                kavita.putOperation(
                    id,
                    account,
                    operation.copy(payload = Json.encodeToString(state.copy(conflict = true))),
                )
                updates.expect(url, ConnectionProgressSyncStatus.CONFLICT)
                kavita.putOperation(id, account, operation.copy(pending = false))
                updates.expectEmpty()
                kavita.putOperation(id, account, operation.copy(payload = "broken"))
                updates.expect(url, ConnectionProgressSyncStatus.UNAVAILABLE)
                prefs.save(
                    address,
                    "replacement",
                    KavitaAccount(2, "replacement", roles = listOf("Admin"), kavitaVersion = "0.8.0"),
                )
                source.reload()
                updates.expect(url, ConnectionProgressSyncStatus.UNVERIFIED_OWNER)
                kavita.putOperation(id, account, operation)
                assertEquals(
                    setOf(ConnectionProgressSyncStatus.UNVERIFIED_OWNER),
                    source.observeProgressSyncStatus(listOf(url)).first {
                        it.isNotEmpty()
                    }[url],
                )
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun suwayomiStatusDistinguishesInitialHoldConflictAndReload(): Unit = fixture { id, address ->
        SuwayomiPreferences(id).save(address, "", SuwayomiAuthMode.NONE, "", "")
        val source = SuwayomiSource(context, LibraryConnectionProfile(id, "suwayomi", "Status fixture"))
        try {
            val account = source.session().identity.account
            val url = source.session().identity.chapterUrl(1, 2)
            val state = SuwayomiReadState(1, 2, 0, 10, false, 100, initial = true)
            val operation = SuwayomiOperation("2", Json.encodeToString(state), 1, true)
            suwayomi.putOperation(id, account, operation)
            suwayomi.putOperation(id, "foreign", operation.copy(payload = "broken"))
            observe(source.observeProgressSyncStatus(listOf(url))) { updates ->
                updates.expect(url, ConnectionProgressSyncStatus.HELD)
                suwayomi.putOperation(
                    id,
                    account,
                    operation.copy(payload = Json.encodeToString(state.copy(initial = false))),
                )
                updates.expect(url, ConnectionProgressSyncStatus.PENDING)
                source.reload()
                updates.expectEmpty()
                updates.expect(url, ConnectionProgressSyncStatus.PENDING)
                suwayomi.putOperation(
                    id,
                    account,
                    operation.copy(payload = Json.encodeToString(state.copy(conflict = true))),
                )
                updates.expect(url, ConnectionProgressSyncStatus.CONFLICT)
                suwayomi.putOperation(id, account, operation.copy(pending = false))
                updates.expectEmpty()
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun smangaStatusObservesIndependentConfirmationRecords(): Unit = fixture { id, address ->
        SmangaPreferences(id).save(address, "fixture", "fixture", 1)
        val source = SmangaSource(context, LibraryConnectionProfile(id, "smanga", "Status fixture"))
        try {
            val account = source.session().accountKey
            val url = "${source.session().prefix}chapter/1/2/3.pages"
            val state = SmangaReadState(3, 2, 2, 10, false, 100, 1, true)
            smanga.putReadState(id, account, state)
            smanga.putCache(id, "foreign", "reading-confirmation", SmangaCacheEntry("3", "broken", 1))
            observe(source.observeProgressSyncStatus(listOf(url))) { updates ->
                updates.expect(url, ConnectionProgressSyncStatus.PENDING)
                smanga.putCache(id, account, "reading-confirmation", SmangaCacheEntry("3", """{"required":true}""", 2))
                updates.expect(url, ConnectionProgressSyncStatus.CONFLICT)
                smanga.putReadState(id, account, state.copy(pending = false))
                updates.expect(url, ConnectionProgressSyncStatus.CONFLICT)
                smanga.putCache(id, account, "reading-confirmation", SmangaCacheEntry("3", "broken", 3))
                updates.expect(url, ConnectionProgressSyncStatus.UNAVAILABLE)
                smanga.putCache(id, account, "reading-confirmation", SmangaCacheEntry("3", """{"required":false}""", 4))
                updates.expectEmpty()
                source.reload()
                smanga.putReadState(id, account, state)
                updates.expect(url, ConnectionProgressSyncStatus.PENDING)
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun lanraragiLocalUnreadIsNotPendingAndReloadResubscribes(): Unit = fixture { id, address ->
        LanraragiPreferences(id).save(address, "fixture")
        val source = LanraragiSource(context, LibraryConnectionProfile(id, "lanraragi", "Status fixture"))
        try {
            val url = "/lanraragi/$id/archive/book"
            val state = LanraragiReadState("book", 2, 10, 100, pending = true)
            lanraragi.record(id, state)
            observe(source.observeProgressSyncStatus(listOf(url))) { updates ->
                updates.expect(url, ConnectionProgressSyncStatus.PENDING)
                lanraragi.record(id, state.copy(localUnread = true, pending = false))
                updates.expect(url, ConnectionProgressSyncStatus.LOCAL_ONLY)
                source.reload()
                updates.expectEmpty()
                updates.expect(url, ConnectionProgressSyncStatus.LOCAL_ONLY)
                lanraragi.record(id, state.copy(pending = false))
                updates.expectEmpty()
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun scopedSqlObserversBatchThousandItemsAndExcludeOtherOwners(): Unit = fixture { id, _ ->
        val keys = (1L..1001L).toList()
        Injekt.get<Database>().transaction {
            keys.forEach { key ->
                for (account in listOf("owner", "foreign")) {
                    kavita.putOperation(id, account, KavitaOperation("progress/$key", account, key, true))
                    suwayomi.putOperation(id, account, SuwayomiOperation("$key", account, key, true))
                    smanga.putReadState(id, account, SmangaReadState(key, 1, 1, 10, false, 100, key, true))
                    smanga.putCache(id, account, "reading-confirmation", SmangaCacheEntry("$key", account, key))
                }
                lanraragi.record(id, LanraragiReadState("$key", 1, 10, 100, pending = true))
            }
        }
        withTimeout(10_000) {
            val kavitaRows = kavita.observeOperations(id, "owner", keys.map { "progress/$it" }).first()
            assertEquals(1001, kavitaRows.size)
            assertTrue(kavitaRows.all { it.payload == "owner" })
            val suwayomiRows = suwayomi.observeOperations(id, "owner", keys.map { "$it" }).first()
            assertEquals(1001, suwayomiRows.size)
            assertTrue(suwayomiRows.all { it.payload == "owner" })
            assertEquals(1001, smanga.observeReadStates(id, "owner", keys).first().size)
            val pauses = smanga.observeCacheEntries(id, "owner", "reading-confirmation", keys.map { "$it" }).first()
            assertEquals(1001, pauses.size)
            assertTrue(pauses.all { it.payload == "owner" })
            assertEquals(1001, lanraragi.observeReadStates(id, keys.map { "$it" }).first().size)
            assertEquals(
                listOf("progress/1"),
                kavita.observeOperations(id, "owner", listOf("progress/1")).first().map {
                    it.key
                },
            )
            assertTrue(smanga.observeReadStates(id, "owner", emptyList()).first().isEmpty())
            assertTrue(suwayomi.observeOperations(id, "owner", emptyList()).first().isEmpty())
            assertTrue(lanraragi.observeReadStates(id, emptyList()).first().isEmpty())
        }
    }

    private suspend fun observe(
        flow: Flow<ProgressStatuses>,
        block: suspend (Channel<ProgressStatuses>) -> Unit,
    ) = coroutineScope {
        val updates = Channel<ProgressStatuses>(Channel.UNLIMITED)
        val job = launch { flow.collect { updates.send(it) } }
        try {
            block(updates)
        } finally {
            job.cancelAndJoin()
            updates.close()
        }
    }

    private suspend fun Channel<ProgressStatuses>.expect(
        url: String,
        state: ConnectionProgressSyncStatus,
    ) = withTimeout(5_000) {
        while (receive()[url] != setOf(state)) Unit
    }

    private suspend fun Channel<ProgressStatuses>.expectEmpty() = withTimeout(5_000) {
        while (receive().isNotEmpty()) Unit
    }

    private fun fixture(block: suspend CoroutineScope.(Long, String) -> Unit) = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val id = System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val preferences = sourcePreferences("source_$id")
        check(preferences.all.isEmpty())
        val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        val counter = Thread {
            while (!listener.isClosed) {
                try {
                    listener.accept().use { requests.incrementAndGet() }
                } catch (_: java.io.IOException) {
                    if (!listener.isClosed) requests.incrementAndGet()
                }
            }
        }.apply { start() }
        try {
            block(id, "http://127.0.0.1:${listener.localPort}")
            assertEquals("Status observation must not open a server connection", 0, requests.get())
        } finally {
            listener.close()
            counter.join(2_000)
            kavita.removeConnection(id)
            suwayomi.removeConnection(id)
            smanga.removeConnection(id)
            lanraragi.remove(id)
            check(preferences.edit().clear().commit())
        }
    }
}
