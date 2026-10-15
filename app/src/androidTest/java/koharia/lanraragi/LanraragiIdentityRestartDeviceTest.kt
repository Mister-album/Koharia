package koharia.lanraragi

import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.hasPausedConnectionWrites
import koharia.domain.lanraragi.LanraragiEntry
import koharia.domain.lanraragi.LanraragiReadState
import koharia.domain.lanraragi.LanraragiRepository
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class LanraragiIdentityRestartDeviceTest {
    @Serializable
    private data class Checkpoint(
        val oldId: Long,
        val newId: Long = 0,
        val oldRevision: Long = 0,
        val baseline: LanraragiEntry? = null,
        val process: Int,
        val hadActive: Boolean,
        val active: Long,
    )

    @Test
    fun credentialReplacementRetainsOldPendingAcrossSourceRestart(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("identityRestartPhase")
        assumeTrue(phase in setOf("prepare", "recover", "cleanup"))
        val run = checkNotNull(args.getString("identityRestartRun"))
        require(run.matches(Regex("[0-9]+")))
        val address = checkNotNull(args.getString("identityRestartAddress"))
        require(address.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/")))
        val archive = "0000000000000000000000000000000000000001"
        val file = File(context.cacheDir, "lanraragi-identity-restart-$run.json")
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val repository = Injekt.get<LanraragiRepository>()
        suspend fun source(id: Long): LanraragiSource = withTimeout(10_000) {
            while (sources.get(id) !is LanraragiSource) delay(25)
            sources.get(id) as LanraragiSource
        }
        suspend fun seed(id: Long, entry: LanraragiEntry) {
            val generation = System.nanoTime()
            repository.begin(id, generation)
            repository.stage(id, generation, listOf(entry))
            repository.publish(id, generation, System.currentTimeMillis())
            LanraragiPreferences(id).markIndexed(address)
            check(sourcePreferences("source_$id").edit().commit())
        }
        fun save(checkpoint: Checkpoint) = file.outputStream().use {
            it.write(Json.encodeToString(checkpoint).toByteArray())
            it.fd.sync()
        }
        if (phase == "prepare") {
            check(!file.exists())
            val hadActive = connections.activeConnectionId.isSet()
            val active = connections.activeConnectionId.get()
            val old = profiles.add("lanraragi", "LRR identity restart $run")
            var checkpoint = Checkpoint(old.id, process = Process.myPid(), hadActive = hadActive, active = active)
            save(checkpoint)
            profiles.commitConfiguration(old.id) { draft ->
                LanraragiPreferences(old.id, draft).save(address, "fixture-old")
            }
            val oldSource = source(old.id)
            val baseline = oldSource.api.archive(archive)
            seed(old.id, baseline)
            repository.record(old.id, LanraragiReadState(archive, 2, 10, 100))
            checkpoint = checkpoint.copy(
                oldRevision = checkNotNull(repository.readState(old.id, archive)).revision,
                baseline = baseline,
            )
            save(checkpoint)
            val replacement = profiles.replaceIdentity(old.id, old.name) { id ->
                LanraragiPreferences(id).save(address, "fixture-new")
            }
            checkpoint = checkpoint.copy(newId = replacement.id)
            save(checkpoint)
            val newSource = source(replacement.id)
            assertEquals(baseline, newSource.api.archive(archive))
            assertTrue(profiles.requiresIdentityReplacement(old.id))
            assertEquals("fixture-old", oldSource.preferences.apiKey)
            seed(replacement.id, baseline)
            assertTrue(repository.readState(replacement.id, archive) == null)
            repository.record(replacement.id, LanraragiReadState(archive, 5, 10, 200))
            assertTrue(checkNotNull(repository.readState(old.id, archive)).pending)
            assertTrue(checkNotNull(repository.readState(replacement.id, archive)).pending)
            return@runBlocking
        }
        val checkpoint = Json.decodeFromString<Checkpoint>(file.readText())
        if (phase == "recover") {
            ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use {
                assertNotEquals(checkpoint.process, Process.myPid())
                val old = source(checkpoint.oldId)
                val current = source(checkpoint.newId)
                assertTrue(profiles.requiresIdentityReplacement(checkpoint.oldId))
                assertEquals("fixture-old", old.preferences.apiKey)
                assertEquals("fixture-new", current.preferences.apiKey)
                // Startup registration must wake both sources; no explicit retryPending call is made here.
                withTimeout(25_000) {
                    while (repository.readState(checkpoint.newId, archive)?.pending != false ||
                        old.status.value.error?.hasPausedConnectionWrites() != true
                    ) {
                        delay(50)
                    }
                }
                assertEquals(
                    LanraragiReadState(archive, 2, 10, 100, revision = checkpoint.oldRevision),
                    repository.readState(checkpoint.oldId, archive),
                )
                val latest = checkNotNull(repository.readState(checkpoint.newId, archive))
                assertEquals(5, latest.pageIndex)
                assertEquals(200L, latest.readAt)
                assertFalse(latest.pending)
                assertEquals(checkpoint.baseline, old.api.archive(archive))
            }
        }
        for (id in listOf(checkpoint.oldId, checkpoint.newId).filter { it > 0 }) {
            val profile = profiles.profiles().singleOrNull { it.id == id }
            check(profile == null || profile.name.startsWith("LRR identity restart $run"))
            profiles.remove(id).getOrThrow()
            repository.remove(id)
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
