package koharia.smanga

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import koharia.domain.smanga.SmangaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in read-only protocol checks against a dedicated server; never changes remote reading state. */
@RunWith(AndroidJUnit4::class)
class SmangaLiveProtocolTest {
    @Test
    fun validatesAccountRejectsWrongPasswordAndRequiresOpdsAuthentication(): Unit = runBlocking(Dispatchers.IO) {
        val fixture = SmangaLiveFixture()
        val api = fixture.createApi(namespace())
        val incorrect = SmangaApi(
            networkClient = Injekt.get<NetworkHelper>().client,
            json = Injekt.get<Json>(),
            address = fixture.config.address,
            username = fixture.config.username,
            password = "invalid-${UUID.randomUUID()}",
            namespace = namespace(),
        )
        try {
            withTimeout(90_000) {
                val account = api.validate()
                assertTrue("Authenticated account ID must be positive", account.id > 0)
                val failure = runCatching { incorrect.login() }.exceptionOrNull()
                assertTrue("An incorrect password must produce AUTH", failure is SmangaException)
                assertEquals(SmangaException.Reason.AUTH, (failure as SmangaException).reason)

                val anonymous = OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .callTimeout(30, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(api.base.newBuilder().addPathSegment("opds").build()).build()
                anonymous.newCall(request).await().use { response ->
                    assertTrue("Anonymous OPDS must reject access", response.code in listOf(401, 403))
                }
                println("Smanga live authentication: accountId=${account.id}")
            }
        } finally {
            incorrect.close()
            api.close()
        }
    }

    @Test
    fun browsesScopedPagesSearchAndSortsAndDecodesRepresentativeImages(): Unit = runBlocking(Dispatchers.IO) {
        val fixture = SmangaLiveFixture()
        val api = fixture.createApi(namespace())
        try {
            withTimeout(600_000) {
                api.validate()
                val media = api.media().take(MAX_MEDIA)
                assertTrue("The dedicated fixture must expose a media library", media.isNotEmpty())
                val sampledManga = linkedMapOf<Long, SmangaManga>()
                for (library in media) {
                    val first = api.mangas(library.id, page = 1, pageSize = PAGE_SIZE, order = "id asc")
                    assertPageScope(first, library.id)
                    for (manga in first.data) {
                        if (sampledManga.size < MAX_MANGA) sampledManga[manga.id] = manga
                    }
                    val seen = first.data.mapTo(mutableSetOf()) { it.id }
                    var page = first
                    while (page.hasNext && page.page < MAX_CATALOG_PAGES && sampledManga.size < MAX_MANGA) {
                        page = api.mangas(library.id, page = page.page + 1, pageSize = PAGE_SIZE, order = "id asc")
                        assertPageScope(page, library.id)
                        assertTrue("Stable ID pagination must not repeat manga", page.data.none { it.id in seen })
                        for (manga in page.data) {
                            seen += manga.id
                            if (sampledManga.size < MAX_MANGA) sampledManga[manga.id] = manga
                        }
                    }
                    for (order in listOf("mangaName asc", "updateTime desc", "createTime desc")) {
                        assertPageScope(api.mangas(library.id, pageSize = PAGE_SIZE, order = order), library.id)
                    }
                    first.data.firstOrNull { it.name.isNotBlank() }?.let { manga ->
                        val search = api.mangas(library.id, pageSize = PAGE_SIZE, query = manga.name)
                        assertPageScope(search, library.id)
                        assertTrue("Searching an existing manga name must return results", search.total > 0)
                    }
                }
                assertTrue("The dedicated fixture must expose manga", sampledManga.isNotEmpty())

                val representatives = linkedMapOf<String, SmangaChapter>()
                for (manga in sampledManga.values) {
                    val chapters = api.chapters(manga.id)
                    assertTrue(
                        "Chapter lists must retain their requested manga",
                        chapters.all { it.mangaId == manga.id },
                    )
                    assertTrue(
                        "Chapter lists must retain their authorized media",
                        chapters.all { it.mediaId == manga.mediaId },
                    )
                    for (chapter in chapters) {
                        if (chapter.format != "pdf" && chapter.format.isNotBlank() &&
                            representatives.size < MAX_FORMATS
                        ) {
                            representatives.putIfAbsent(chapter.format, chapter)
                        }
                    }
                }

                var covers = 0
                val coverManga = mutableSetOf<Long>()
                for ((format, chapter) in representatives) {
                    val detail = api.chapter(chapter.id)
                    assertEquals(chapter.id, detail.id)
                    assertEquals(chapter.mangaId, detail.mangaId)
                    if (coverManga.add(chapter.mangaId) && decodeImage(api, api.coverUrl(chapter.mangaId), true)) {
                        covers++
                    }
                    if (decodeImage(api, api.chapterCoverUrl(chapter.id), true)) covers++
                    val manifest = api.preparePages(chapter.id)
                    assertEquals(chapter.id, manifest.chapterId)
                    assertTrue("Prepared image chapters must contain pages", manifest.pageCount > 0)
                    val indexes = listOf(0, manifest.pageCount / 2, manifest.pageCount - 1).distinct()
                    for (index in indexes) {
                        val page = manifest.pages[index]
                        assertEquals(index, page.displayIndex)
                        assertTrue(page.opdsPage in 1..manifest.pageCount)
                        assertTrue(decodeImage(api, api.pageUrl(chapter.id, page.opdsPage)))
                    }
                    val safeFormat = format.filter { it.isLetterOrDigit() && it.code < 128 }.take(16)
                    println(
                        "Smanga live images: mangaId=${chapter.mangaId} chapterId=${chapter.id} " +
                            "format=$safeFormat pages=${manifest.pageCount} decoded=${indexes.size}",
                    )
                }
                if (representatives.isEmpty()) {
                    if (decodeImage(api, api.coverUrl(sampledManga.values.first().id), true)) covers++
                }
                println(
                    "Smanga live catalog: media=${media.size} manga=${sampledManga.size} " +
                        "formats=${representatives.size} covers=$covers",
                )
            }
        } finally {
            api.close()
        }
    }

    @Test
    fun persistedWarmAndEmptyShelvesWorkWithoutNetworkAndFailedRefreshRetainsGeneration(): Unit =
        runBlocking(Dispatchers.IO) {
            val fixture = SmangaLiveFixture()
            val repository = Injekt.get<SmangaRepository>()
            val json = Injekt.get<Json>()
            val connectionId = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1)
            val accountKey = namespace().encodeUtf8().sha256().hex()
            val api = fixture.createApi(accountKey)
            fun catalog() = SmangaCatalog(connectionId, accountKey, repository, api, json) {}
            try {
                withTimeout(120_000) {
                    val cold = catalog()
                    val media = cold.media()
                    assertTrue("The dedicated fixture must expose a media library", media.isNotEmpty())
                    var selected: Pair<Long, SmangaMangaPage>? = null
                    for (library in media.take(MAX_MEDIA)) {
                        val page = cold.page(library.id, "", ORDER, 1)
                        if (page.data.isNotEmpty()) {
                            selected = library.id to page
                            break
                        }
                    }
                    assertNotNull("The sampled fixture must contain a nonempty shelf", selected)
                    val (mediaId, warm) = requireNotNull(selected)
                    val emptyQuery = "smanga-live-missing-${UUID.randomUUID()}"
                    val empty = cold.page(mediaId, emptyQuery, ORDER, 1)
                    assertTrue("A unique missing query must produce a valid empty cache", empty.data.isEmpty())
                    assertEquals(0, empty.total)
                    val group = shelfGroup(mediaId, "", json)
                    val emptyGroup = shelfGroup(mediaId, emptyQuery, json)
                    val before = repository.cacheGroup(connectionId, accountKey, group)
                    val beforeEmpty = repository.cacheGroup(connectionId, accountKey, emptyGroup)
                    val beforeMedia = repository.cache(connectionId, accountKey, "media", "data")
                    assertTrue(before.isNotEmpty() && beforeEmpty.isNotEmpty())

                    // A closed API throws before creating any call, so successful reads prove no network access.
                    api.close()
                    val restarted = catalog()
                    assertTrue("Warm media must survive a new catalog instance", media == restarted.media())
                    assertTrue(
                        "Warm shelves must survive a new catalog instance",
                        warm == restarted.page(mediaId, "", ORDER, 1),
                    )
                    assertTrue(
                        "Valid empty shelves must remain warm",
                        empty == restarted.page(mediaId, emptyQuery, ORDER, 1),
                    )
                    val refreshFailure = runCatching { restarted.refresh(mediaId, "", ORDER) }.exceptionOrNull()
                    assertNotNull("Explicit refresh must fail with a closed transport", refreshFailure)
                    assertTrue(
                        "Failed refresh must retain complete entries and generations",
                        before == repository.cacheGroup(connectionId, accountKey, group),
                    )
                    assertTrue(
                        "Failed refresh must preserve the empty query",
                        beforeEmpty == repository.cacheGroup(connectionId, accountKey, emptyGroup),
                    )
                    assertTrue(
                        "Failed refresh must preserve media cache",
                        beforeMedia == repository.cache(connectionId, accountKey, "media", "data"),
                    )
                    assertTrue("The original warm page remains usable", warm == restarted.page(mediaId, "", ORDER, 1))
                    println("Smanga live cache: mediaId=$mediaId warm=${warm.data.size} empty=${empty.data.size}")
                }
            } finally {
                api.close()
                withContext(NonCancellable) { repository.removeAccount(connectionId, accountKey) }
            }
        }

    @Test
    fun allMediaRefreshPreservesDatabaseNameOrderAndWarmCache(): Unit = runBlocking(Dispatchers.IO) {
        val fixture = SmangaLiveFixture()
        val repository = Injekt.get<SmangaRepository>()
        val json = Injekt.get<Json>()
        val connectionId = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1)
        val accountKey = namespace().encodeUtf8().sha256().hex()
        val api = fixture.createApi(accountKey)
        fun catalog() = SmangaCatalog(connectionId, accountKey, repository, api, json) {}
        try {
            withTimeout(120_000) {
                val cold = catalog()
                val media = cold.media()
                assertTrue("This regression requires multiple authorized libraries", media.size in 2..MAX_MEDIA)
                val ids = media.map { it.id }
                for (order in listOf(ORDER, "mangaName desc")) {
                    val expected = ids.associateWith { api.mangas(it, order = order) }
                    val total = expected.values.sumOf { it.total }
                    assertTrue(
                        "Use a small fixture to verify every result without a full catalogue scan",
                        total in 1..100,
                    )
                    repeat(2) {
                        cold.refreshShelf(0, "", order)
                        assertEquals(media, cold.media())
                        val merged = cold.page(ids, "", order, 1)
                        assertEquals(total, merged.total)
                        assertEquals(total, merged.data.size)
                        assertEquals(total, merged.data.map { it.id }.toSet().size)
                        for ((id, page) in expected) {
                            assertEquals(
                                "Merging must preserve each library's database order",
                                page.data,
                                merged.data.filter { it.mediaId == id },
                            )
                        }
                    }
                }
                val warm = cold.page(ids, "", ORDER, 1)
                // A closed client cannot create requests, including on explicit refresh.
                api.close()
                val restarted = catalog()
                assertEquals(media, restarted.media())
                assertEquals(warm, restarted.page(ids, "", ORDER, 1))
                assertNotNull(runCatching { restarted.refreshShelf(0, "", ORDER) }.exceptionOrNull())
                assertEquals(media, restarted.media())
                assertEquals(warm, restarted.page(ids, "", ORDER, 1))
                println("Smanga live all-media refresh: media=${ids.size} manga=${warm.total} warmWithoutNetwork=true")
            }
        } finally {
            api.close()
            withContext(NonCancellable) { repository.removeAccount(connectionId, accountKey) }
        }
    }

    private fun assertPageScope(page: SmangaMangaPage, mediaId: Long) {
        assertEquals(PAGE_SIZE, page.pageSize)
        assertTrue("A small page must respect the requested page size", page.data.size <= PAGE_SIZE)
        assertTrue("Manga listings must stay in the authorized media", page.data.all { it.mediaId == mediaId })
        assertEquals(page.data.size, page.data.map { it.id }.distinct().size)
    }

    private suspend fun decodeImage(api: SmangaApi, url: String, allowMissing: Boolean = false): Boolean {
        val client = api.opdsClient.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(url).build()).await().use { response ->
            if (allowMissing && response.code == 404) return false
            checkedSmangaImageResponse(response)
            assertTrue("Image body must fit the bounded live fixture", response.body.contentLength() <= MAX_IMAGE_BYTES)
            val bytes = ByteArrayOutputStream().use { output ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var count = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        count += read
                        assertTrue("Image body must fit the bounded live fixture", count <= MAX_IMAGE_BYTES)
                        output.write(buffer, 0, read)
                    }
                }
                output.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            assertTrue("Image bounds must decode", bounds.outWidth > 0 && bounds.outHeight > 0)
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (bounds.outWidth / options.inSampleSize > 2048 || bounds.outHeight / options.inSampleSize > 2048) {
                options.inSampleSize *= 2
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            assertNotNull("Representative page must decode into pixels", bitmap)
            requireNotNull(bitmap).recycle()
            return true
        }
    }

    private fun namespace() = "smanga-live-${UUID.randomUUID()}"
    private fun shelfGroup(mediaId: Long, query: String, json: Json) =
        "shelf/$mediaId/" + json.encodeToString(listOf(query, ORDER)).encodeUtf8().sha256().hex()

    private companion object {
        const val PAGE_SIZE = 3
        const val MAX_MEDIA = 6
        const val MAX_MANGA = 24
        const val MAX_CATALOG_PAGES = 8
        const val MAX_FORMATS = 8
        const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
        const val ORDER = "mangaName asc"
    }
}
