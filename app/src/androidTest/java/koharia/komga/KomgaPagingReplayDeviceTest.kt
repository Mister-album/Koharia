package koharia.komga

import android.content.Context
import android.content.ContextWrapper
import androidx.paging.PagingSource
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import koharia.connection.ConnectionPreferences
import koharia.komga.api.KomgaApiClient
import koharia.komga.domain.repository.KomgaRepository
import koharia.komga.ui.library.KomgaSortedSearchPagingSource
import koharia.source.komga.KomgaCacheControlInterceptor
import koharia.source.komga.KomgaCachePolicy
import koharia.source.komga.KomgaMetadataCacheStore
import koharia.source.komga.SeriesSort
import koharia.source.komga.TYPE_ALL_INDEX
import koharia.source.komga.TypeSelect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class KomgaPagingReplayDeviceTest {
    @Test
    fun refreshedOrReplacedSourceRejectsOldMergeCursorsBeforeFetching(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val id = System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val preferences = eu.kanade.tachiyomi.source.sourcePreferences("source_$id")
        check(preferences.all.isEmpty())
        try {
            check(
                preferences.edit().putString("Address", "http://127.0.0.1:1")
                    .putString("AuthMode", "Credentials").putString("Username", "fixture")
                    .putString("Password", "fixture").commit(),
            )
            val source = koharia.source.komga.KomgaSource(id)
            val filters = FilterList(SeriesSort().apply { state = Filter.Sort.Selection(2, true) })
            val beforeRefresh = source.sortedSearchSession("fixture", filters)
            source.refreshBrowseRequests()
            assertTrue(
                runCatching { beforeRefresh.load(1) }.exceptionOrNull() is kotlinx.coroutines.CancellationException,
            )
            val beforeReplacement = source.sortedSearchSession("fixture", filters)
            check(preferences.edit().putString("Username", "another").commit())
            assertTrue(
                runCatching { beforeReplacement.load(1) }.exceptionOrNull() is kotlinx.coroutines.CancellationException,
            )
        } finally {
            context.deleteSharedPreferences("source_$id")
        }
    }

    @Test
    fun sortedPagingReplaysDroppedPagesFromProductionDiskCache(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val id = System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val root = File(context.cacheDir, "komga-paging-$id")
        check(!root.exists() && root.mkdirs())
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getExternalFilesDir(type: String?): File = File(root, "external").apply { mkdirs() }
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        val namespace = "fixture-$id"
        val requests = AtomicInteger()
        var online = true
        val client = OkHttpClient.Builder()
            .addInterceptor(KomgaCacheControlInterceptor(isolated) { namespace })
            .addInterceptor { chain ->
                requests.incrementAndGet()
                if (!online) throw java.io.IOException("Unexpected network after persistent cache bootstrap")
                val request = chain.request()
                val page = request.url.queryParameter("page")!!.toInt()
                val book = "/books/" in request.url.encodedPath
                val content = (1..25).joinToString(",") {
                    val number = page * 25 + it
                    val time = Instant.ofEpochSecond(number * 2L + if (book) 0 else 1)
                    """{"id":"$number","libraryId":"fixture","name":"Fixture $number",
                        "fileLastModified":"$time","created":"$time","metadata":{"title":"Fixture $number"}}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(
                        """{"content":[$content],"last":${page == 19}}""".toResponseBody(
                            "application/json".toMediaType(),
                        ),
                    )
                    .build()
            }.build()
        val mangas = Injekt.get<MangaRepository>()
        fun repository(scope: String = namespace): KomgaRepository {
            val store = KomgaMetadataCacheStore(isolated) { scope }
            val api = KomgaApiClient(
                "https://komga.fixture",
                Headers.Builder().build(),
                client,
                Json,
                shelfCache = { store.load(it) },
            )
            return KomgaRepository("https://komga.fixture", api)
        }
        val filters = FilterList(
            TypeSelect().apply { state = TYPE_ALL_INDEX },
            SeriesSort().apply { state = Filter.Sort.Selection(2, true) },
        )
        fun pager() = KomgaSortedSearchPagingSource(
            id,
            repository().sortedSearchSession("fixture", filters, emptySet(), KomgaCachePolicy.Default) {},
            NetworkToLocalManga(mangas),
        )
        suspend fun load(pager: KomgaSortedSearchPagingSource, page: Int): PagingSource.LoadResult.Page<Long, Manga> {
            val result = pager.load(PagingSource.LoadParams.Refresh(page.toLong(), 25, false))
            assertTrue(result.toString(), result is PagingSource.LoadResult.Page)
            return result as PagingSource.LoadResult.Page
        }
        try {
            val first = pager()
            val expected = (1..40).associateWith { load(first, it).data.map(Manga::url) }
            assertEquals(1000, expected.values.flatten().toSet().size)
            assertEquals(40, requests.get())
            online = false
            for (page in 40 downTo 1) {
                val result = load(first, page)
                assertEquals(expected[page], result.data.map(Manga::url))
                assertEquals((page - 1L).takeIf { page > 1 }, result.prevKey)
            }
            val restored = pager()
            for (page in 1..40) assertEquals(expected[page], load(restored, page).data.map(Manga::url))
            assertEquals(40, requests.get())
            assertTrue(
                runCatching {
                    repository("another-account").sortedSearchSession(
                        "fixture",
                        filters,
                        emptySet(),
                        KomgaCachePolicy.Default,
                    ) {}.load(1)
                }.isFailure,
            )
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            mangas.deleteMangaBySourceId(id)
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(root.deleteRecursively())
        }
    }
}
