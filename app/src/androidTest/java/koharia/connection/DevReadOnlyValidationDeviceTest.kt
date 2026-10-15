package koharia.connection

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.model.ReaderProgressSession
import koharia.komga.progress.KomgaProgressQueueJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Run only with -PdeviceTestFixture=true -PdevReadOnlyValidation=true. */
@RunWith(AndroidJUnit4::class)
class DevReadOnlyValidationDeviceTest {
    @Test
    fun requestBoundaryAllowsReadsAndRejectsMutationsBeforeProceeding() {
        assertEquals(
            "app.koharia.dev.devicefixture",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
        assumeTrue(DevReadOnlyValidation.enabled)
        var proceeded = 0
        fun invoke(request: okhttp3.Request) {
            val chain = java.lang.reflect.Proxy.newProxyInstance(
                okhttp3.Interceptor.Chain::class.java.classLoader,
                arrayOf(okhttp3.Interceptor.Chain::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "request" -> request
                    "proceed" -> {
                        proceeded++
                        okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200).message("fixture").build()
                    }
                    else -> error("Unexpected chain method")
                }
            } as okhttp3.Interceptor.Chain
            ConnectionWriteGuard { false }.intercept(chain)
        }
        val base = okhttp3.Request.Builder().url("https://example.invalid/fixture")
        invoke(base.get().build())
        val body = okhttp3.RequestBody.create(null, byteArrayOf())
        invoke(base.post(body).connectionReadOnly().build())
        assertEquals(2, proceeded)
        for (method in listOf("POST", "PUT", "PATCH", "DELETE", "MOVE", "MKCOL")) {
            val request = okhttp3.Request.Builder().url("https://example.invalid/fixture").method(method, body).build()
            org.junit.Assert.assertThrows(ConnectionWritesPaused::class.java) { invoke(request) }
        }
        assertEquals(2, proceeded)
    }

    @Test
    fun startupAndReaderLeaveExistingQueuesUntouched(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        assumeTrue(DevReadOnlyValidation.enabled)
        val sources = Injekt.get<SourceManager>()
        withTimeout(30_000) { sources.isInitialized.first { it } }
        val preferences = listOf("active_downloads", "chapters_to_delete").map {
            context.getSharedPreferences(it, Context.MODE_PRIVATE)
        }
        val before = preferences.map { it.all.toMap() }
        val downloads = Injekt.get<DownloadManager>()
        assertFalse(downloads.downloaderStart())
        downloads.deletePendingChapters()
        assertEquals(before, preferences.map { it.all.toMap() })

        // A session capture must not call provider progress APIs, even on a registered source.
        val source = sources.getCatalogueSources().first()
        val chapter = Chapter.create().copy(id = Long.MAX_VALUE, mangaId = Long.MAX_VALUE, url = "readonly-fixture")
        val session = ReaderProgressSession.capture(source, source.id, chapter, sources)
        session.checkCurrent()
        assertNull(session.pages)
        assertNull(session.epub)

        // An existing worker wakeup retries before identity validation/dispatch/acknowledgement.
        val manager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<KomgaProgressQueueJob>().build()
        try {
            manager.enqueue(request).result.get()
            withTimeout(30_000) {
                while (true) {
                    val info = manager.getWorkInfoById(request.id).get()!!
                    assertFalse(info.state.isFinished)
                    if (info.runAttemptCount > 0 && info.state == WorkInfo.State.ENQUEUED) break
                    delay(100)
                }
            }
            assertTrue(DevReadOnlyValidation.enabled)
            assertEquals(before, preferences.map { it.all.toMap() })
        } finally {
            manager.cancelWorkById(request.id).result.get()
        }
    }
}
