package koharia.kavita

import koharia.connection.withConnectionWriteGuard
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Requires a fresh official service with task-owned accounts/media and the opt-in loopback control bridge. */
class KavitaControlledServiceTest {
    @Test
    fun official08ProgressCommitCanBeReadAfterLostResponse(): Unit = runBlocking {
        val configured = System.getenv("KAVITA_CONTROLLED_BRIDGE")
        assumeTrue(!configured.isNullOrBlank())
        val bridge = checkNotNull(configured).toHttpUrl()
        check(bridge.scheme == "http" && bridge.host == "127.0.0.1" && bridge.port > 1024)
        val control = OkHttpClient()
        fun get(path: String = "") = control.newCall(Request.Builder().url("$bridge$path").build()).execute().use {
            check(it.code == 200)
            Json.parseToJsonElement(it.body.string()).jsonObject
        }
        fun arm(path: String) = control.newCall(
            Request.Builder().url("$bridge/$path").post(ByteArray(0).toRequestBody()).build(),
        ).execute().use { check(it.code == 200) }
        try {
            val credentials = get()
            val server = credentials.getValue("server").jsonPrimitive.content.toHttpUrl()
            check(server.scheme == "http" && server.host == "127.0.0.1" && server.port > 1024)
            KavitaApiClient(
                control.withConnectionWriteGuard { false },
                server.toString(),
                credentials.getValue("key").jsonPrimitive.content,
                "owned-service",
            ).use { api ->
                val account = api.getAccount()
                assertEquals(KavitaVersion(0, 8, 8), api.capabilities(account).version)
                val catalog = KavitaCatalog(-111, "owned", MemoryKavitaRepository(), api) {}
                assertEquals(3, catalog.libraries().size)
                val series = catalog.page(1, KavitaFilter()).items
                assertTrue(series.map { it.format }.containsAll(listOf(1, 3, 4)))
                val comic = series.single { it.format == 1 }
                val volume = catalog.volumes(comic.id).first { it.chapters.isNotEmpty() }
                val chapter = volume.chapters.first()
                assertTrue(chapter.pages >= 3)
                assertEquals(0, api.progress(chapter.id).pageNum)
                val progress = KavitaProgress(
                    libraryId = comic.libraryId,
                    seriesId = comic.id,
                    volumeId = volume.id,
                    chapterId = chapter.id,
                    pageNum = 1,
                    lastModifiedUtc = java.time.Instant.now().toString(),
                )
                arm("drop-progress")
                assertNotNull(runCatching { api.saveProgress(progress) }.exceptionOrNull())
                assertEquals(1L, get("/state").getValue("progress_writes").jsonPrimitive.long)
                assertEquals(1L, get("/state").getValue("dropped").jsonPrimitive.long)
                assertEquals(1, api.progress(chapter.id).pageNum)
                arm("delay-read")
                val start = System.nanoTime()
                assertEquals(1, api.progress(chapter.id).pageNum)
                assertTrue((System.nanoTime() - start) / 1_000_000 >= 100)
                val state = get("/state")
                assertEquals(1L, state.getValue("progress_writes").jsonPrimitive.long)
                assertEquals(1L, state.getValue("delayed_reads").jsonPrimitive.long)
                assertEquals(3L, state.getValue("progress_reads").jsonPrimitive.long)
            }
        } finally {
            control.connectionPool.evictAll()
            control.dispatcher.executorService.shutdown()
        }
    }
}
