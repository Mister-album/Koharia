package koharia.smanga

import android.os.StrictMode
import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.LoadState
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.model.ScreenModelStore
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.domain.smanga.SmangaRepository
import koharia.smanga.ui.SmangaLibraryScreenModel
import koharia.source.smanga.SmangaConnectionProvider
import koharia.source.smanga.SmangaPreferences
import koharia.source.smanga.SmangaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Exercises the actual Main-thread Paging consumer without preparing catalogue caches first. */
@RunWith(AndroidJUnit4::class)
class SmangaShelfColdStartTest {
    @Test
    fun coldPagerLoadsDelayedBodyAndNewModelUsesWarmCacheWithoutRequests(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val manager: ConnectionProfileManager = Injekt.get()
        val connections: ConnectionPreferences = Injekt.get()
        val downloadedOnly = Injekt.get<BasePreferences>().downloadedOnly
        val previousDownloadedOnly = downloadedOnly.get()
        val hadDownloadedOnly = downloadedOnly.isSet()
        val previousActive = connections.activeConnectionId.get()
        val hadActive = connections.activeConnectionId.isSet()
        var originalPolicy: StrictMode.ThreadPolicy? = null
        var source: SmangaSource? = null
        var connectionId: Long? = null
        val server = ShelfServer()
        try {
            originalPolicy = withContext(Dispatchers.Main) {
                StrictMode.getThreadPolicy().also {
                    StrictMode.setThreadPolicy(
                        StrictMode.ThreadPolicy.Builder(it).detectNetwork().penaltyDeathOnNetwork().build(),
                    )
                }
            }
            assertTrue("The fixture must require more than a buffered body read", server.mangaBodySize > 8192)
            downloadedOnly.set(false)
            val profile = manager.add(SmangaConnectionProvider.ID, "Smanga cold shelf fixture")
            connectionId = profile.id
            SmangaPreferences(profile.id).save(server.address, server.username, "fixture-password", server.accountId)
            source = withTimeout(10_000) {
                var registered: SmangaSource?
                do {
                    registered = Injekt.get<SourceManager>().get(profile.id) as? SmangaSource
                    if (registered == null) delay(25)
                } while (registered == null)
                registered
            }.also { it.reload() }
            val active = source
            val accountKey = active.session().accountKey
            assertEquals(null, Injekt.get<SmangaRepository>().cache(profile.id, accountKey, "media", "data"))
            assertEquals(0, server.requestCount.get())

            val cold = collectShelf(active, "Cold shelf") { model, differ, initial ->
                assertEquals("A fresh connection defaults to all authorized media", 0L, model.state.value.selectedMedia)
                assertEquals(listOf(BOOK_TITLE), initial.map { it.title })
                assertEquals(1, server.mediaRequests.get())
                assertEquals(
                    "Only the initial automatic page request may satisfy this assertion",
                    1,
                    server.mangaRequests.get(),
                )
                server.failManga.set(true)
                model.refresh()
                val failedRefresh = withTimeoutOrNull(10_000) {
                    while (model.state.value.error == null || model.state.value.refreshing) delay(25)
                    true
                } ?: false
                assertTrue(
                    "Refresh must report its failure: ${failureTypes(model.state.value.error)}",
                    failedRefresh,
                )
                assertEquals(2, server.mangaRequests.get())
                assertEquals(initial.map { it.url }, differ.snapshot().items.map { it.value.url })

                server.failManga.set(false)
                var searchLoad: LoadState? = null
                differ.addLoadStateListener { searchLoad = it.refresh }
                model.search("missing")
                val emptySearch = withTimeoutOrNull(10_000) {
                    while (
                        server.emptySearchResponses.get() == 0 || differ.itemCount != 0 ||
                        searchLoad !is LoadState.NotLoading
                    ) {
                        val error = (searchLoad as? LoadState.Error)?.error
                        if (error != null) throw AssertionError("Empty search failed: ${failureTypes(error)}")
                        delay(25)
                    }
                    true
                } ?: false
                assertTrue(
                    "Empty search must complete: ${failureTypes(model.state.value.error)}",
                    emptySearch,
                )
                assertEquals(1, server.emptySearchResponses.get())
                assertEquals(3, server.mangaRequests.get())
                assertEquals("missing", model.state.value.query)
                assertEquals(
                    "Successful empty search must clear the old refresh failure",
                    null,
                    model.state.value.error,
                )
                assertTrue(differ.snapshot().items.isEmpty())
            }
            val account = withContext(Dispatchers.Main) { active.session().api.validate() }
            assertEquals(server.accountId, account.id)
            assertEquals(1, server.opdsRequests.get())
            val requestsBeforeWarm = server.requestCount.get()

            active.session().api.close()
            val warm = collectShelf(active, "Warm shelf with a closed API")
            assertEquals(cold.map { it.url }, warm.map { it.url })
            assertEquals(listOf(BOOK_TITLE), warm.map { it.title })
            assertEquals(
                "A new model must render the warm shelf without contacting the server",
                requestsBeforeWarm,
                server.requestCount.get(),
            )
        } finally {
            withContext(NonCancellable) {
                try {
                    source?.close()
                    connectionId?.let { id ->
                        try {
                            manager.remove(id).getOrThrow()
                        } finally {
                            Injekt.get<MangaRepository>().deleteMangaBySourceId(id)
                            Injekt.get<SmangaRepository>().removeConnection(id)
                        }
                    }
                } finally {
                    try {
                        if (hadDownloadedOnly) downloadedOnly.set(previousDownloadedOnly) else downloadedOnly.delete()
                        if (hadActive) {
                            connections.activeConnectionId.set(
                                previousActive,
                            )
                        } else {
                            connections.activeConnectionId.delete()
                        }
                    } finally {
                        try {
                            server.close()
                        } finally {
                            originalPolicy?.let { saved ->
                                withContext(Dispatchers.Main) { StrictMode.setThreadPolicy(saved) }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(InternalVoyagerApi::class)
    private suspend fun collectShelf(
        source: SmangaSource,
        label: String,
        verifyLoaded: suspend (
            SmangaLibraryScreenModel,
            AsyncPagingDataDiffer<StateFlow<Manga>>,
            List<Manga>,
        ) -> Unit = { _, _, _ -> },
    ): List<Manga> = withContext(Dispatchers.Main) {
        val holderKey = "smanga-shelf-fixture-${UUID.randomUUID()}"
        lateinit var initializationJob: Job
        val model = ScreenModelStore.getOrPut(holderKey, null) {
            SmangaLibraryScreenModel(source, null).also {
                initializationJob = checkNotNull(it.screenModelScope.coroutineContext[Job])
            }
        }
        val modelJob = checkNotNull(model.screenModelScope.coroutineContext[Job])
        val differ = AsyncPagingDataDiffer(
            diffCallback = object : DiffUtil.ItemCallback<StateFlow<Manga>>() {
                override fun areItemsTheSame(oldItem: StateFlow<Manga>, newItem: StateFlow<Manga>) =
                    oldItem.value.url == newItem.value.url

                override fun areContentsTheSame(oldItem: StateFlow<Manga>, newItem: StateFlow<Manga>) =
                    oldItem.value == newItem.value
            },
            updateCallback = object : ListUpdateCallback {
                override fun onInserted(position: Int, count: Int) = Unit
                override fun onRemoved(position: Int, count: Int) = Unit
                override fun onMoved(fromPosition: Int, toPosition: Int) = Unit
                override fun onChanged(position: Int, count: Int, payload: Any?) = Unit
            },
        )
        var failure: Throwable? = null
        differ.addLoadStateListener { states ->
            (states.refresh as? LoadState.Error)?.error?.let { failure = it }
        }
        val collector = launch {
            try {
                model.pages.collectLatest(differ::submitData)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failure = error
            }
        }
        try {
            assertTrue("$label must start with an active initialization scope", initializationJob.isActive)
            assertTrue("$label must have an active registered model scope", modelJob.isActive)
            val initial = withTimeoutOrNull(15_000) {
                while (true) {
                    val error = failure ?: model.state.value.error
                    if (error != null) throw AssertionError("$label failed: ${failureTypes(error)}")
                    val items = differ.snapshot().items
                    if (items.isNotEmpty()) return@withTimeoutOrNull items.map { it.value }
                    delay(25)
                }
                @Suppress("UNREACHABLE_CODE")
                emptyList<Manga>()
            } ?: throw AssertionError("$label timed out: ${failureTypes(failure ?: model.state.value.error)}")
            verifyLoaded(model, differ, initial)
            initial
        } finally {
            withContext(NonCancellable) {
                collector.cancelAndJoin()
                ScreenModelStore.onDisposeNavigator(holderKey)
                initializationJob.join()
                modelJob.join()
            }
        }
    }

    private fun failureTypes(error: Throwable?): String = error?.let {
        generateSequence(it) { cause ->
            cause.cause
        }.take(6).joinToString(" -> ") { cause -> cause.javaClass.simpleName }
    } ?: "no reported failure"

    private class ShelfServer : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val accountId = Random.nextLong(1, Int.MAX_VALUE.toLong())
        val username = "cold-${UUID.randomUUID()}"
        val address: String get() = "http://127.0.0.1:${listener.localPort}/fixture"
        val requestCount = AtomicInteger()
        val mediaRequests = AtomicInteger()
        val mangaRequests = AtomicInteger()
        val opdsRequests = AtomicInteger()
        val emptySearchResponses = AtomicInteger()
        val failManga = AtomicBoolean()
        private val mangaBody = buildJsonObject {
            put("code", 200)
            put("count", 1)
            put(
                "list",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("mangaId", 4)
                            put("mediaId", 2)
                            put("mangaName", BOOK_TITLE)
                            put("intro", "fixture ".repeat(4096))
                            put("chapterCount", 1)
                        },
                    )
                },
            )
        }.toString().toByteArray()
        val mangaBodySize: Int get() = mangaBody.size

        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = try {
                        listener.accept()
                    } catch (_: IOException) {
                        break
                    }
                    sockets += socket
                    executor.execute {
                        try {
                            socket.use(::respond)
                        } catch (_: IOException) {
                            // Cancellation and the intentionally failing old implementation close the socket.
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } finally {
                            sockets -= socket
                        }
                    }
                }
            }
        }

        private fun respond(socket: Socket) {
            socket.soTimeout = 10_000
            val input = socket.getInputStream().buffered()
            val target = readLine(input)?.split(' ')?.getOrNull(1) ?: return
            var remaining = 0
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    remaining =
                        line.substringAfter(':').trim().toInt()
                }
            }
            require(remaining in 0..16_384)
            while (remaining > 0) {
                if (input.read() == -1) return
                remaining--
            }
            val url = address.toHttpUrl().resolve(target) ?: return
            val path = url.encodedPath
            val emptySearch = path == "/fixture/api/manga" && url.queryParameter("keyWord") == "missing"
            requestCount.incrementAndGet()
            var status = 200
            var contentType = "application/json"
            val bytes = when (path) {
                "/fixture/api/login", "/fixture/api/user/me" -> buildJsonObject {
                    put("code", 200)
                    put(
                        "data",
                        buildJsonObject {
                            put("userId", accountId)
                            put("userName", username)
                            put("role", "admin")
                            put("token", "fixture-token")
                        },
                    )
                }.toString().toByteArray()
                "/fixture/api/media" -> {
                    mediaRequests.incrementAndGet()
                    (
                        """{"code":200,"count":1,"list":[""" +
                            """{"mediaId":2,"mediaName":"Fixture media","mangaCount":1}]}"""
                        ).toByteArray()
                }
                "/fixture/api/manga" -> {
                    mangaRequests.incrementAndGet()
                    when {
                        failManga.get() -> {
                            status = 503
                            """{"code":503}""".toByteArray()
                        }
                        emptySearch -> """{"code":200,"count":0,"list":[]}""".toByteArray()
                        else -> mangaBody
                    }
                }
                "/fixture/api/opds" -> {
                    opdsRequests.incrementAndGet()
                    contentType = "application/atom+xml"
                    (
                        """<feed xmlns="http://www.w3.org/2005/Atom">""" +
                            "<id>fixture</id><title>Fixture</title><!--" +
                            "fixture ".repeat(4096) + "--></feed>"
                        ).toByteArray()
                }
                else -> {
                    status = 404
                    """{"code":404}""".toByteArray()
                }
            }
            val output = socket.getOutputStream()
            output.write(
                (
                    "HTTP/1.1 $status Fixture\r\nContent-Type: $contentType\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            output.flush()
            if (path == "/fixture/api/manga" || path == "/fixture/api/opds") Thread.sleep(200)
            output.write(bytes)
            output.flush()
            if (emptySearch && status == 200) emptySearchResponses.incrementAndGet()
        }

        private fun readLine(input: BufferedInputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() <= 8192) {
                val byte = input.read()
                if (byte == -1) return null
                if (byte == '\n'.code) return bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r')
                bytes.write(byte)
            }
            throw IOException("Fixture request line exceeds its bound")
        }

        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
        }
    }

    private companion object {
        const val BOOK_TITLE = "Cold shelf fixture book"
    }
}
