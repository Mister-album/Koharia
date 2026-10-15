package koharia.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.track.komga.KomgaApi
import eu.kanade.tachiyomi.data.track.komga.KomgaHeldProgress
import eu.kanade.tachiyomi.data.track.komga.KomgaPageProgressRetryJob
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.api.get
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class KomgaRetryQueueDeviceTest {
    @Test
    fun newerPositionReplacesOnlyItsOwnPendingWork(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val url = "https://example.invalid/api/v1/books/${UUID.randomUUID()}"
        val sourceId = Long.MAX_VALUE
        val baseline = KomgaApi.BookProgressSnapshot(
            url, null, null, 0, 10, false, null, false, false, null, null, 0, "fixture.cbz",
        )
        try {
            val first = KomgaPageProgressRetryJob.enqueue(sourceId, url, 1, 10, baseline, "owner-a", 0)
            val other = KomgaPageProgressRetryJob.enqueue(sourceId - 1, url, 3, 10, baseline, "owner-a", 0)
            val otherAccount = KomgaPageProgressRetryJob.enqueue(sourceId, url, 3, 10, baseline, "owner-b", 0)
            val second = KomgaPageProgressRetryJob.enqueue(sourceId, url, 2, 10, baseline, "owner-a", 0)
            val old = context.workManager.getWorkInfoById(first).get()
            org.junit.Assert.assertTrue(old == null || old.state == WorkInfo.State.CANCELLED)
            assertEquals(WorkInfo.State.ENQUEUED, context.workManager.getWorkInfoById(second).get()?.state)
            assertEquals(WorkInfo.State.ENQUEUED, context.workManager.getWorkInfoById(other).get()?.state)

            val local = KomgaPageProgressRetryJob.enqueueLocal(
                sourceId = sourceId,
                url = url,
                page = 4,
                total = 10,
                readAt = System.currentTimeMillis(),
                ownerKey = "owner-a",
                revision = 0,
            )
            val nextRevision = KomgaPageProgressRetryJob.enqueueLocal(
                sourceId,
                url,
                5,
                10,
                System.currentTimeMillis(),
                "owner-a",
                1,
            )
            KomgaPageProgressRetryJob.cancelRevision(sourceId, url, "owner-a", 0)
            assertEquals(WorkInfo.State.ENQUEUED, context.workManager.getWorkInfoById(nextRevision).get()?.state)
            val replaced = context.workManager.getWorkInfoById(second).get()
            org.junit.Assert.assertTrue(replaced == null || replaced.state == WorkInfo.State.CANCELLED)
            assertEquals(WorkInfo.State.CANCELLED, context.workManager.getWorkInfoById(local).get()?.state)
            assertEquals(WorkInfo.State.ENQUEUED, context.workManager.getWorkInfoById(other).get()?.state)
            KomgaPageProgressRetryJob.cancel(sourceId, url, "owner-a")
            assertEquals(WorkInfo.State.ENQUEUED, context.workManager.getWorkInfoById(otherAccount).get()?.state)
        } finally {
            KomgaPageProgressRetryJob.cancel(sourceId, url, "owner-a")
            KomgaPageProgressRetryJob.cancel(sourceId, url, "owner-b")
            KomgaPageProgressRetryJob.cancel(sourceId - 1, url, "owner-a")
        }
    }

    @Test
    fun legacyWorkIsHeldWithItsOriginalPayloadInsteadOfUsingCurrentCredentials() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val url = "https://example.invalid/api/v1/books/${UUID.randomUUID()}"
        val request = OneTimeWorkRequestBuilder<KomgaPageProgressRetryJob>().setInputData(
            workDataOf(
                "source" to Long.MAX_VALUE,
                "url" to url,
                "page" to 3,
                "total" to 10,
                "bytes" to byteArrayOf(-128, 127),
                "longs" to longArrayOf(Long.MAX_VALUE),
                "strings" to arrayOf("fixture", null),
                "float" to Float.NaN,
            ),
        ).build()
        try {
            context.workManager.enqueue(request).result.get()
            val work = withTimeout(20_000) {
                var current = context.workManager.getWorkInfoById(request.id).get()
                while (current?.state?.isFinished != true) {
                    delay(100)
                    current = context.workManager.getWorkInfoById(request.id).get()
                }
                checkNotNull(current)
            }
            assertEquals(WorkInfo.State.FAILED, work.state)
            assertEquals("held", work.outputData.getString("progressStatus"))
            assertEquals("unknown_owner", work.outputData.getString("reason"))
            val saved = checkNotNull(KomgaHeldProgress.read(context, request.id))
            assertTrue(saved.contains(url))
            assertTrue(saved.contains("unknown_owner"))
            assertTrue(saved.contains("\"page\":3"))
            val payload = org.json.JSONObject(saved)
            assertEquals(2, payload.getInt("schema"))
            assertEquals("[Ljava.lang.Byte;", payload.getJSONObject("inputTypes").getString("bytes"))
            assertEquals("java.lang.Float", payload.getJSONObject("inputTypes").getString("float"))
            assertEquals(-128, payload.getJSONObject("input").getJSONArray("bytes").getInt(0))
            assertEquals(Long.MAX_VALUE, payload.getJSONObject("input").getJSONArray("longs").getLong(0))
            assertTrue(payload.getJSONObject("input").getJSONArray("strings").isNull(1))
            assertEquals("NaN", payload.getJSONObject("input").getString("float"))
        } finally {
            context.workManager.cancelWorkById(request.id).result.get()
            KomgaHeldProgress.remove(context, request.id)
        }
    }

    @Test
    fun ownedLegacyWorkWithoutRevisionIsHeld(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val request = OneTimeWorkRequestBuilder<KomgaPageProgressRetryJob>().setInputData(
            workDataOf(
                "source" to Long.MAX_VALUE,
                "owner" to "fixture",
                "url" to "https://example.invalid/api/v1/books/book",
                "page" to 3,
                "total" to 10,
            ),
        ).build()
        try {
            context.workManager.enqueue(request).result.get()
            val work = withTimeout(20_000) {
                var current = context.workManager.getWorkInfoById(request.id).get()
                while (current?.state?.isFinished != true) {
                    delay(100)
                    current = context.workManager.getWorkInfoById(request.id).get()
                }
                checkNotNull(current)
            }
            assertEquals(WorkInfo.State.FAILED, work.state)
            assertEquals("unknown_revision", work.outputData.getString("reason"))
            assertTrue(checkNotNull(KomgaHeldProgress.read(context, request.id)).contains("unknown_revision"))
        } finally {
            context.workManager.cancelWorkById(request.id).result.get()
            KomgaHeldProgress.remove(context, request.id)
        }
    }

    @Test
    fun oldWorkerIsQuarantinedEvenAfterDurableUnread(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val sourceId = System.nanoTime()
        val repository = uy.kohesive.injekt.Injekt.get<koharia.domain.komga.KomgaReadStatusRepository>()
        val key = koharia.domain.komga.KomgaReadStatusKey(sourceId, "fixture", "book")
        val request = OneTimeWorkRequestBuilder<KomgaPageProgressRetryJob>().setInputData(
            workDataOf(
                "source" to sourceId,
                "owner" to "fixture",
                "revision" to 0L,
                "url" to "https://example.invalid/api/v1/books/book",
                "page" to 3,
                "total" to 10,
            ),
        ).build()
        try {
            repository.record(key, false, 100, null, emptyList()) {}
            context.workManager.enqueue(request).result.get()
            val work = withTimeout(20_000) {
                var current = context.workManager.getWorkInfoById(request.id).get()
                while (current?.state?.isFinished != true) {
                    delay(100)
                    current = context.workManager.getWorkInfoById(request.id).get()
                }
                checkNotNull(current)
            }
            assertEquals(WorkInfo.State.FAILED, work.state)
            assertEquals("held", work.outputData.getString("progressStatus"))
            assertEquals("legacy_payload", work.outputData.getString("reason"))
            assertTrue(checkNotNull(KomgaHeldProgress.read(context, request.id)).contains("legacy_payload"))
            assertEquals(1L, repository.get(key)!!.revision)
            assertEquals(false, repository.get(key)!!.read)
        } finally {
            context.workManager.cancelWorkById(request.id).result.get()
            KomgaHeldProgress.remove(context, request.id)
            repository.removeConnection(sourceId)
        }
    }
}
