package koharia.komga

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.track.komga.KomgaApi
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository
import koharia.komga.progress.KomgaApiPageTransport
import koharia.komga.progress.KomgaProgressQueueJob
import koharia.komga.progress.KomgaProgressRecoveryJob
import koharia.komga.progress.KomgaProgressWorkManager
import koharia.source.komga.KomgaSource
import koharia.source.komga.captureKomgaProgressSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class KomgaIdentityRestartDeviceTest {
    @Serializable
    private data class Checkpoint(
        val oldId: Long,
        val newId: Long = 0,
        val oldOwner: String = "",
        val newOwner: String = "",
        val baseline: String = "",
        val work: String = "",
        val process: Int,
        val hadActive: Boolean,
        val active: Long,
    )

    @Test
    fun credentialReplacementKeepsOldWorkHeldAfterActualProcessRestart(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("identityRestartPhase")
        assumeTrue(phase in setOf("prepare", "recover", "cleanup"))
        val run = checkNotNull(args.getString("identityRestartRun"))
        require(run.matches(Regex("[0-9]+")))
        val address = checkNotNull(args.getString("identityRestartAddress"))
        require(address.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+")))
        val file = File(context.cacheDir, "komga-identity-restart-$run.json")
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val pages = Injekt.get<KomgaPageEventRepository>()
        val manager = WorkManager.getInstance(context)
        suspend fun source(id: Long): KomgaSource = withTimeout(10_000) {
            while (sources.get(id) !is KomgaSource) delay(25)
            sources.get(id) as KomgaSource
        }
        suspend fun baseline(source: KomgaSource): String {
            val session = captureKomgaProgressSession(source, sources)
            return KomgaApiPageTransport(KomgaApi(6, source.client), session, "$address/api/v1/books/book") {
                session.guard.requireCurrent()
            }.read().encode()
        }
        fun save(checkpoint: Checkpoint) = file.outputStream().use {
            it.write(Json.encodeToString(checkpoint).toByteArray())
            it.fd.sync()
        }
        if (phase == "prepare") {
            check(!file.exists())
            KomgaProgressRecoveryJob.enqueueAtStartup(context)
            withTimeout(20_000) {
                while (manager.getWorkInfosForUniqueWork("komga-progress-recovery").get().any {
                        !it.state.isFinished
                    }
                ) {
                    delay(50)
                }
            }
            val hadActive = connections.activeConnectionId.isSet()
            val active = connections.activeConnectionId.get()
            val old = profiles.add("komga", "Identity restart $run")
            var checkpoint = Checkpoint(old.id, process = Process.myPid(), hadActive = hadActive, active = active)
            save(checkpoint)
            profiles.commitConfiguration(old.id) { draft ->
                check(
                    draft.edit().putString("Address", address).putString("AuthMode", "Credentials")
                        .putString("Username", "fixture-old").putString("Password", "fixture-password").commit(),
                )
            }
            val oldSource = source(old.id)
            val oldKey = KomgaReadStatusKey(old.id, oldSource.progressAccount().key, "book")
            val before = baseline(oldSource)
            pages.record(oldKey, 0, 3, 10, 100, before) {}
            val request = OneTimeWorkRequestBuilder<KomgaProgressQueueJob>()
                .setInputData(workDataOf("source" to old.id, "owner" to oldKey.ownerKey, "book" to "book"))
                .setInitialDelay(45, TimeUnit.SECONDS)
                .addTag(KomgaProgressWorkManager.name(oldKey)).build()
            manager.enqueue(request).result.get()
            checkpoint = checkpoint.copy(oldOwner = oldKey.ownerKey, baseline = before, work = request.id.toString())
            save(checkpoint)
            val replacement = profiles.replaceIdentity(old.id, old.name) { id ->
                check(
                    sourcePreferences("source_$id").edit().putString("Address", address)
                        .putString("AuthMode", "Credentials").putString("Username", "fixture-new")
                        .putString("Password", "fixture-password").commit(),
                )
            }
            checkpoint = checkpoint.copy(newId = replacement.id)
            save(checkpoint)
            val newSource = source(replacement.id)
            val newKey = KomgaReadStatusKey(replacement.id, newSource.progressAccount().key, "book")
            assertNotEquals(oldKey.ownerKey, newKey.ownerKey)
            assertEquals(before, baseline(newSource))
            assertTrue(profiles.requiresIdentityReplacement(old.id))
            assertEquals("fixture-old", sourcePreferences("source_${old.id}").getString("Username", null))
            pages.record(newKey, 0, 5, 10, 200, before) {}
            checkpoint = checkpoint.copy(newOwner = newKey.ownerKey)
            save(checkpoint)
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(request.id).get()?.state)
            assertEquals(KomgaReadStatusPhase.PENDING, pages.get(oldKey)?.phase)
            assertEquals(KomgaReadStatusPhase.PENDING, pages.get(newKey)?.phase)
            return@runBlocking
        }
        val checkpoint = Json.decodeFromString<Checkpoint>(file.readText())
        val oldKey = checkpoint.oldOwner.takeIf(String::isNotBlank)?.let {
            KomgaReadStatusKey(checkpoint.oldId, it, "book")
        }
        val newKey = checkpoint.newOwner.takeIf(String::isNotBlank)?.let {
            KomgaReadStatusKey(checkpoint.newId, it, "book")
        }
        if (phase == "recover") {
            checkNotNull(oldKey)
            checkNotNull(newKey)
            assertNotEquals(checkpoint.process, Process.myPid())
            assertTrue(profiles.requiresIdentityReplacement(checkpoint.oldId))
            assertEquals(checkpoint.newOwner, source(checkpoint.newId).progressAccount().key)
            withTimeout(65_000) {
                while (pages.get(newKey)?.phase != KomgaReadStatusPhase.SYNCED ||
                    manager.getWorkInfoById(UUID.fromString(checkpoint.work)).get()?.state?.isFinished != true
                ) {
                    delay(100)
                }
            }
            val work = checkNotNull(manager.getWorkInfoById(UUID.fromString(checkpoint.work)).get())
            assertEquals(WorkInfo.State.SUCCEEDED, work.state)
            assertEquals("HELD", work.outputData.getString("progressStatus"))
            val old = checkNotNull(pages.get(oldKey))
            assertEquals(KomgaReadStatusPhase.PENDING, old.phase)
            assertEquals(3L, old.page)
            assertEquals(100L, old.readAt)
            assertEquals(checkpoint.baseline, old.baseline)
            assertEquals(1L, old.generation)
            assertEquals(5L, pages.get(newKey)?.page)
        }
        if (checkpoint.work.isNotBlank()) manager.cancelWorkById(UUID.fromString(checkpoint.work)).result.get()
        for (id in listOf(checkpoint.oldId, checkpoint.newId).filter { it > 0 }) {
            val profile = profiles.profiles().singleOrNull { it.id == id }
            check(profile == null || profile.name.startsWith("Identity restart $run"))
            val key = if (id == checkpoint.oldId) oldKey else newKey
            if (key != null) manager.cancelUniqueWork(KomgaProgressWorkManager.name(key)).result.get()
            profiles.remove(id).getOrThrow()
            pages.removeConnection(id)
            Injekt.get<KomgaReadStatusRepository>().removeConnection(id)
        }
        if (checkpoint.hadActive) {
            connections.activeConnectionId.set(
                checkpoint.active,
            )
        } else {
            connections.activeConnectionId.delete()
        }
        assertTrue(file.delete())
    }
}
