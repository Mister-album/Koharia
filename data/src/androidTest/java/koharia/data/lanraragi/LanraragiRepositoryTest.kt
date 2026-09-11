package koharia.data.lanraragi

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import koharia.domain.lanraragi.LanraragiEntry
import koharia.domain.lanraragi.LanraragiReadState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class LanraragiRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "lanraragi-test-${System.nanoTime()}.db"
    private lateinit var driver: AndroidxSqliteDriver
    private lateinit var repository: LanraragiRepositoryImpl
    private val archive = LanraragiEntry("same-archive", title = "Archive")

    @Before
    fun open() {
        driver = AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.FileProvider(context, name),
            schema = Database.Schema,
            configuration = AndroidxSqliteConfiguration(isForeignKeyConstraintsEnabled = true),
        )
        val database = Database(
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
        )
        repository = LanraragiRepositoryImpl(database, Json)
        runBlocking { repository.entries(1) }
    }

    @After
    fun close() {
        driver.close()
        context.deleteDatabase(name)
    }

    @Test
    fun onlyCompleteGenerationBecomesVisible() = runBlocking {
        repository.begin(1, 1)
        repository.stage(1, 1, listOf(archive))
        assertTrue(repository.entries(1).isEmpty())
        repository.publish(1, 1, 100)
        repository.begin(1, 2)
        repository.stage(1, 2, listOf(archive.copy(title = "Changed")))
        assertEquals("Archive", repository.entries(1).single().title)
        repository.abort(1, 2)
        assertEquals("Archive", repository.entries(1).single().title)
        assertEquals(100L, repository.lastSync(1))
        repository.abort(1, 1)
        assertEquals(1, repository.entries(1).size)
    }

    @Test
    fun connectionsAndReadStateStayIsolatedDuringRefreshAndRemoval() = runBlocking {
        for (connection in listOf(1L, 2L)) {
            repository.stage(connection, 1, listOf(archive))
            repository.publish(connection, 1, 100)
            repository.record(connection, LanraragiReadState(archive.id, connection.toInt(), 10, 200))
        }
        repository.stage(1, 2, listOf(archive.copy(progress = 9)))
        repository.publish(1, 2, 300)
        assertEquals(1, repository.readStates(1).single().pageIndex)
        repository.remove(1)
        assertTrue(repository.entries(1).isEmpty())
        assertTrue(repository.readStates(1).isEmpty())
        assertEquals(1, repository.entries(2).size)
        assertEquals(2, repository.readStates(2).single().pageIndex)
    }

    @Test
    fun staleAcknowledgementCannotDropNewerReadingEvent() = runBlocking {
        repository.record(1, LanraragiReadState(archive.id, 1, 10, 100))
        val first = repository.readStates(1).single()
        repository.record(1, first.copy(pageIndex = 2, readAt = 200))
        repository.acknowledge(1, first)
        assertTrue(repository.readStates(1).single().pending)
        repository.acknowledge(1, repository.readStates(1).single())
        assertFalse(repository.readStates(1).single().pending)
    }

    @Test
    fun unreadAndPendingProgressSurviveReopeningDatabase() = runBlocking {
        repository.record(1, LanraragiReadState(archive.id, 0, 10, 300, localUnread = true, pending = false))
        repository.record(2, LanraragiReadState(archive.id, 4, 10, 400, initialPage = true))
        driver.close()
        open()
        assertTrue(repository.readStates(1).single().localUnread)
        assertFalse(repository.readStates(1).single().pending)
        assertEquals(4, repository.readStates(2).single().pageIndex)
        assertTrue(repository.readStates(2).single().pending)
        assertTrue(repository.readStates(2).single().initialPage)
    }

    @Test
    fun upgradesPreviousSchemaWithoutChangingExistingTables() = runBlocking {
        driver.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            listOf("lanraragi_catalog", "lanraragi_members", "lanraragi_sync", "lanraragi_read_state").forEach {
                db.execSQL("DROP TABLE $it")
            }
            db.execSQL("PRAGMA user_version = 17")
        }
        open()
        repository.record(1, LanraragiReadState(archive.id, 0, 1, 1))
        assertEquals(1, repository.readStates(1).size)
    }
}
