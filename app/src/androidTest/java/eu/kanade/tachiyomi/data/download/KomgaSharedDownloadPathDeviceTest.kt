package eu.kanade.tachiyomi.data.download

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import com.hippo.unifile.UniFile
import koharia.source.komga.KomgaServerPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
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
import tachiyomi.data.Tts_progress
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import java.io.File

@RunWith(AndroidJUnit4::class)
class KomgaSharedDownloadPathDeviceTest {
    @Test
    fun lookupTreatsOrdinaryDirectoryNamesLiterally(): Unit = withIndex { repository, _ ->
        val paths = listOf("Book_1", "Book_1/one.cbz", "BookA1/one.cbz", "Book_10/one.cbz")
        seed(repository, paths)
        assertEquals(paths.take(2).toSet(), repository.findByLocalRelativePathPrefix("Book_1").paths())
    }

    @Test
    fun deletingPercentDirectoryKeepsSiblingAndOtherAccountMatches(): Unit = withIndex { repository, manager ->
        val paths = listOf("100%", "100%/one.cbz", "100%/nested/two.cbz", "100 percent/one.cbz")
        seed(repository, paths)
        manager.deleteIndexedPathPrefix("100%")
        for (server in listOf(11L, 22L)) {
            assertEquals(setOf(paths.last()), repository.findByServerId(server).paths())
        }
    }

    @Test
    fun renamingDirectoryPreservesCaseDistinctSiblingAndPathBoundary(): Unit = withIndex { repository, manager ->
        val paths = listOf("Books", "Books/one.cbz", "Books/nested/two.cbz", "books/one.cbz", "Bookshelf/one.cbz")
        seed(repository, paths)
        manager.updateIndexedPathPrefix("Books", "Moved")
        for (server in listOf(11L, 22L)) {
            val rows = repository.findByServerId(server)
            assertEquals(setOf("Moved", "Moved/one.cbz", "Moved/nested/two.cbz", paths[3], paths[4]), rows.paths())
            assertEquals(
                listOf(1L, 1L),
                rows.filter {
                    it.localRelativePath in paths.takeLast(2)
                }.map { it.lastVerifiedAt },
            )
        }
    }

    @Test
    fun renamingEmojiDirectoryPreservesCompleteUnicodeSuffix(): Unit = withIndex { repository, manager ->
        val paths = listOf("Shelf/📚", "Shelf/📚/一.cbz", "Shelf/📚/nested/📖.pdf", "Shelf/📚 extra/one.cbz")
        seed(repository, paths)
        manager.updateIndexedPathPrefix("Shelf/📚", "Moved/读物")
        for (server in listOf(11L, 22L)) {
            val rows = repository.findByServerId(server)
            assertEquals(setOf("Moved/读物", "Moved/读物/一.cbz", "Moved/读物/nested/📖.pdf", paths.last()), rows.paths())
            assertEquals(paths.map { it.substringAfterLast('/') }, rows.sortedBy { it.bookUrl }.map { it.fileName })
            assertEquals(setOf(1L), rows.map { it.createdAt }.toSet())
        }
    }

    private fun List<KomgaSharedDownloadMatch>.paths() = map { it.localRelativePath }.toSet()

    private suspend fun seed(repository: KomgaSharedDownloadRepository, paths: List<String>) {
        for (server in listOf(11L, 22L)) {
            paths.forEachIndexed { index, path ->
                repository.upsert(
                    KomgaSharedDownloadMatch(
                        serverId = server,
                        bookUrl = "book-$index",
                        seriesUrl = "series",
                        fileHash = "fixture-$index",
                        sizeBytes = 12,
                        isbn = "",
                        seriesTitle = "Fixture",
                        bookTitle = "Book $index",
                        numberSort = index.toDouble(),
                        localRelativePath = path,
                        fileName = path.substringAfterLast('/'),
                        fileKind = "cbz",
                        createdAt = 1,
                        lastVerifiedAt = 1,
                    ),
                )
            }
        }
    }

    private fun withIndex(
        block: suspend (KomgaSharedDownloadRepository, KomgaSharedDownloadIndexManager) -> Unit,
    ): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val name = "shared-path-${System.nanoTime()}"
        val parent = context.cacheDir.canonicalFile
        val root = File(parent, name).canonicalFile
        check(root.parentFile == parent && !root.exists() && root.mkdirs())
        check(!context.getDatabasePath("$name.db").exists())
        val scopes = mutableListOf<Any>()
        try {
            AndroidxSqliteDriver(
                driver = BundledSQLiteDriver(),
                databaseType = AndroidxSqliteDatabaseType.FileProvider(context, "$name.db"),
                schema = Database.Schema,
            ).use { driver ->
                val repository = KomgaSharedDownloadRepository(database(driver))
                val store = InMemoryPreferenceStore()
                val folders = object : FolderProvider {
                    override fun directory() = root
                    override fun path() = UniFile.fromFile(root)!!.uri.toString()
                }
                val storage = StorageManager(context, StoragePreferences(folders, store)).also { scopes += it }
                val manager = KomgaSharedDownloadIndexManager(
                    repository,
                    storage,
                    LibraryPreferences(store),
                    KomgaServerPreferences(context, store, Json),
                ).also { scopes += it }
                block(repository, manager)
            }
        } finally {
            scopes.asReversed().forEach {
                val scope = it.javaClass.getDeclaredField("scope").apply {
                    isAccessible = true
                }.get(it) as CoroutineScope
                checkNotNull(scope.coroutineContext[Job]).cancelAndJoin()
            }
            context.deleteDatabase("$name.db")
            check(root.canonicalFile.parentFile == parent && root.deleteRecursively())
        }
    }

    private fun database(driver: SqlDriver) = Database(
        driver,
        chaptersAdapter = Chapters.Adapter(MemoColumnAdapter),
        mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter, MemoColumnAdapter),
        historyAdapter = History.Adapter(DateColumnAdapter),
        epub_progressAdapter = Epub_progress.Adapter(DateColumnAdapter, DateColumnAdapter),
        epub_bookmarkAdapter = Epub_bookmark.Adapter(DateColumnAdapter),
        epub_pagination_cacheAdapter = Epub_pagination_cache.Adapter(DateColumnAdapter),
        epub_remote_progress_cacheAdapter = Epub_remote_progress_cache.Adapter(
            DateColumnAdapter,
            DateColumnAdapter,
            DateColumnAdapter,
        ),
        tts_progressAdapter = Tts_progress.Adapter(DateColumnAdapter),
    )
}
