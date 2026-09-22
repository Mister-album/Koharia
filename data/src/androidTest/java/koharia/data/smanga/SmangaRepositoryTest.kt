package koharia.data.smanga

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.async.coroutines.awaitAsList
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import koharia.domain.smanga.SmangaCacheEntry
import koharia.domain.smanga.SmangaHistoryEvent
import koharia.domain.smanga.SmangaReadState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.Epub_bookmark
import tachiyomi.data.Epub_pagination_cache
import tachiyomi.data.Epub_progress
import tachiyomi.data.Epub_remote_progress_cache
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter

@RunWith(AndroidJUnit4::class)
class SmangaRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "smanga-test-${System.nanoTime()}.db"
    private lateinit var driver: AndroidxSqliteDriver
    private lateinit var database: Database
    private lateinit var repository: SmangaRepositoryImpl
    private val cached = SmangaCacheEntry("snapshot", "[]", 100)
    private val state = SmangaReadState(10, 20, 2, 12, false, 200, 1, true)
    private val event = SmangaHistoryEvent("event", 20, 10, 30, 200)

    @Before
    fun open() {
        driver = AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.FileProvider(context, name),
            schema = Database.Schema,
            configuration = AndroidxSqliteConfiguration(isForeignKeyConstraintsEnabled = true),
        )
        database = Database(
            driver,
            chaptersAdapter = Chapters.Adapter(MemoColumnAdapter),
            epub_bookmarkAdapter = Epub_bookmark.Adapter(DateColumnAdapter),
            epub_pagination_cacheAdapter = Epub_pagination_cache.Adapter(DateColumnAdapter),
            epub_progressAdapter = Epub_progress.Adapter(DateColumnAdapter, DateColumnAdapter),
            epub_remote_progress_cacheAdapter = Epub_remote_progress_cache.Adapter(
                DateColumnAdapter,
                DateColumnAdapter,
                DateColumnAdapter,
            ),
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter, MemoColumnAdapter),
            tts_progressAdapter = tachiyomi.data.Tts_progress.Adapter(DateColumnAdapter),
        )
        repository = SmangaRepositoryImpl(database)
        runBlocking { repository.cacheGroup(1, "account", "shelf") }
    }

    @After
    fun close() {
        driver.close()
        context.deleteDatabase(name)
    }

    @Test
    fun failedGroupReplacementRollsBackDeletionAndPartialInserts() = runBlocking {
        repository.putCache(1, "account", "shelf", cached)
        val replacement = object : AbstractList<SmangaCacheEntry>() {
            override val size = 2
            override fun get(index: Int): SmangaCacheEntry {
                check(index == 0) { "Simulated failure after the first insert" }
                return cached.copy(key = "replacement", payload = "[1]", updatedAt = 300, generation = 300)
            }
        }

        val result = runCatching { repository.replaceCacheGroup(1, "account", "shelf", replacement) }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf(cached), repository.cacheGroup(1, "account", "shelf"))
        repository.replaceCacheGroup(1, "account", "shelf", listOf(cached.copy(key = "new")))
        assertNull(repository.cache(1, "account", "shelf", cached.key))
        assertEquals("new", repository.cacheGroup(1, "account", "shelf").single().key)
    }

    @Test
    fun scopesStayIsolatedThroughRefreshResetAndRemoval() = runBlocking {
        val scopes = listOf(1L to "first", 1L to "second", 2L to "first")
        scopes.forEach { (connection, account) ->
            repository.putCache(connection, account, "shelf", cached)
            repository.putCache(connection, account, "details", cached)
            repository.putReadState(connection, account, state)
            repository.putReadState(connection, account, state.copy(chapterId = 11))
            repository.enqueueHistory(connection, account, event)
        }
        repository.replaceCacheGroup(1, "first", "shelf", emptyList())
        assertTrue(repository.cacheGroup(1, "first", "shelf").isEmpty())
        assertEquals(cached, repository.cache(1, "first", "details", cached.key))
        assertEquals(state, repository.readState(1, "first", state.chapterId))
        repository.resetReadStates(1, "first", emptyList())
        assertEquals(2, repository.readStates(1, "first").size)
        repository.resetReadStates(1, "first", listOf(state.chapterId))
        assertEquals(listOf(11L), repository.readStates(1, "first").map { it.chapterId })
        assertEquals(2, repository.readStates(1, "second").size)

        repository.removeAccount(1, "first")
        assertTrue(repository.cacheGroup(1, "first", "details").isEmpty())
        assertTrue(repository.readStates(1, "first").isEmpty())
        assertTrue(repository.historyEvents(1, "first").isEmpty())
        assertEquals(cached, repository.cache(1, "second", "shelf", cached.key))
        assertEquals(event, repository.historyEvents(1, "second").single())
        repository.removeConnection(1)
        assertTrue(repository.cacheGroup(1, "second", "shelf").isEmpty())
        assertTrue(repository.readStates(1, "second").isEmpty())
        assertTrue(repository.historyEvents(1, "second").isEmpty())
        assertEquals(cached, repository.cache(2, "first", "shelf", cached.key))
        assertEquals(2, repository.readStates(2, "first").size)
        assertEquals(event, repository.historyEvents(2, "first").single())
    }

    @Test
    fun acknowledgmentOnlyClearsMatchingPendingRevisionAndAccount() = runBlocking {
        repository.putReadState(1, "first", state)
        repository.putReadState(1, "second", state)
        val newer = state.copy(pageIndex = 4, readAt = 300, revision = 2)
        repository.putReadState(1, "first", newer)

        assertFalse(repository.acknowledgeReadState(1, "first", state.chapterId, state.revision))
        assertFalse(repository.acknowledgeReadState(2, "first", state.chapterId, newer.revision))
        assertEquals(newer, repository.readState(1, "first", state.chapterId))
        assertTrue(repository.acknowledgeReadState(1, "first", newer.chapterId, newer.revision))
        assertEquals(newer.copy(pending = false), repository.readState(1, "first", newer.chapterId))
        assertEquals(state, repository.readState(1, "second", state.chapterId))
        assertFalse(repository.acknowledgeReadState(1, "first", newer.chapterId, newer.revision))
    }

    @Test
    fun pendingAndMangaReadStateQueriesFilterWithinTheAccount() = runBlocking {
        val acknowledged = state.copy(chapterId = 11, pending = false)
        val otherManga = state.copy(chapterId = 12, mangaId = 21)
        repository.putReadState(1, "first", state)
        repository.putReadState(1, "first", acknowledged)
        repository.putReadState(1, "first", otherManga)
        repository.putReadState(1, "second", state.copy(chapterId = 13))
        repository.putReadState(2, "first", state.copy(chapterId = 14))

        assertEquals(listOf(state, otherManga), repository.pendingReadStates(1, "first"))
        assertEquals(listOf(state, acknowledged), repository.readStatesForManga(1, "first", 20))
        assertEquals(listOf(otherManga), repository.readStatesForManga(1, "first", 21))
        assertTrue(repository.readStatesForManga(1, "first", 99).isEmpty())
        assertTrue(repository.pendingReadStates(1, "missing").isEmpty())
        repository.acknowledgeReadState(1, "first", state.chapterId, state.revision)
        assertEquals(listOf(otherManga), repository.pendingReadStates(1, "first"))
        assertEquals(listOf(13L), repository.pendingReadStates(1, "second").map { it.chapterId })
        assertEquals(listOf(14L), repository.readStatesForManga(2, "first", 20).map { it.chapterId })
        assertScopedQueryIndexes()
    }

    @Test
    fun pendingHistoryAndEventLookupRespectAccountAndDeliveryStatus() = runBlocking {
        val earlier = event.copy(id = "earlier", readAt = 100)
        val attempted = event.copy(id = "attempted", readAt = 50, status = 1)
        val sent = event.copy(id = "sent", readAt = 75, status = 2)
        val otherAccount = event.copy(mangaId = 99)
        repository.enqueueHistory(1, "first", event)
        repository.enqueueHistory(1, "first", earlier)
        repository.enqueueHistory(1, "first", attempted)
        repository.enqueueHistory(1, "first", sent)
        repository.enqueueHistory(1, "second", otherAccount)
        repository.enqueueHistory(2, "first", otherAccount)

        assertEquals(listOf(earlier, event), repository.pendingHistoryEvents(1, "first"))
        assertEquals(attempted, repository.historyEvent(1, "first", attempted.id))
        assertEquals(sent, repository.historyEvent(1, "first", sent.id))
        assertEquals(event, repository.historyEvent(1, "first", event.id))
        assertEquals(otherAccount, repository.historyEvent(1, "second", event.id))
        assertEquals(otherAccount, repository.historyEvent(2, "first", event.id))
        assertNull(repository.historyEvent(1, "missing", event.id))
        assertNull(repository.historyEvent(1, "first", "missing"))
        repository.updateHistoryStatus(1, "first", earlier.id, 1)
        assertEquals(listOf(event), repository.pendingHistoryEvents(1, "first"))
        assertEquals(listOf(otherAccount), repository.pendingHistoryEvents(1, "second"))
        assertEquals(listOf(otherAccount), repository.pendingHistoryEvents(2, "first"))
    }

    @Test
    fun historyAttemptCannotBeResetByDuplicateEnqueueOrStaleStatus() = runBlocking {
        repository.enqueueHistory(1, "first", event)
        repository.enqueueHistory(1, "second", event)
        repository.updateHistoryStatus(1, "first", event.id, 1)
        repository.enqueueHistory(1, "first", event.copy(readAt = 999))
        repository.updateHistoryStatus(1, "first", event.id, 0)
        driver.close()
        open()

        assertEquals(event.copy(status = 1), repository.historyEvents(1, "first").single())
        assertEquals(event, repository.historyEvents(1, "second").single())
        repository.updateHistoryStatus(1, "first", event.id, 2)
        repository.updateHistoryStatus(1, "first", event.id, 1)
        assertEquals(event.copy(status = 2), repository.historyEvents(1, "first").single())
    }

    @Test
    fun emptyShelfSnapshotAndUnreadStateSurviveReopening() = runBlocking {
        val unread = state.copy(pageIndex = 0, explicitUnread = true, initialPage = true)
        assertNull(repository.cache(1, "account", "shelf", cached.key))
        repository.putCache(1, "account", "shelf", cached)
        repository.putReadState(1, "account", unread)
        driver.close()
        open()

        assertEquals(cached, repository.cache(1, "account", "shelf", cached.key))
        assertEquals(unread, repository.readState(1, "account", state.chapterId))
    }

    @Test
    fun upgradesPreviousSchemaAndPreservesOtherProviderState() = runBlocking {
        database.lanraragiQueries.record(77, "existing-archive", 1, 10, 100, 0, 1, 0)
        driver.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            listOf("smanga_cache", "smanga_read_state", "smanga_history_event").forEach {
                db.execSQL("DROP TABLE $it")
            }
            db.execSQL("PRAGMA user_version = 20")
        }
        open()

        repository.putCache(1, "account", "shelf", cached)
        repository.putReadState(1, "account", state)
        repository.enqueueHistory(1, "account", event)
        assertEquals(cached, repository.cache(1, "account", "shelf", cached.key))
        assertEquals(state, repository.readState(1, "account", state.chapterId))
        assertEquals(event, repository.historyEvents(1, "account").single())
        assertEquals("existing-archive", database.lanraragiQueries.getReadStates(77).awaitAsList().single().archive_id)
        assertScopedQueryIndexes()
    }

    private fun assertScopedQueryIndexes() {
        val selections = mapOf(
            "smanga_read_state_pending_index" to
                "SELECT * FROM smanga_read_state WHERE connection_id = 1 AND account_key = 'first' " +
                "AND pending = 1 ORDER BY chapter_id",
            "smanga_read_state_manga_index" to
                "SELECT * FROM smanga_read_state WHERE connection_id = 1 AND account_key = 'first' " +
                "AND manga_id = 20 ORDER BY chapter_id",
            "smanga_history_event_pending_index" to
                "SELECT * FROM smanga_history_event WHERE connection_id = 1 AND account_key = 'first' " +
                "AND status = 0 ORDER BY read_at, event_id",
        )
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            selections.forEach { (index, selection) ->
                db.rawQuery("EXPLAIN QUERY PLAN $selection", null).use { cursor ->
                    val details = buildList {
                        while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail")))
                    }
                    assertTrue("Expected $index in query plan $details", details.any { index in it })
                }
            }
        }
    }
}
