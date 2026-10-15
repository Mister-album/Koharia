package koharia.kavita

import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.hasPausedConnectionWrites
import koharia.domain.kavita.KavitaOperation
import koharia.domain.kavita.KavitaRepository
import koharia.source.kavita.KavitaPreferences
import koharia.source.kavita.KavitaSource
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

class KavitaIdentityRestartDeviceTest {
    @Serializable
    private data class Checkpoint(
        val oldId: Long,
        val newId: Long = 0,
        val oldAccount: String = "",
        val newAccount: String = "",
        val oldPayload: String = "",
        val baseline: KavitaProgress? = null,
        val process: Int,
        val hadActive: Boolean,
        val active: Long,
    )

    private fun account(role: String) = KavitaAccount(
        id = if (role == "old") 1 else 2,
        username = role,
        token = "fixture-$role",
        roles = listOf("Login"),
        kavitaVersion = "0.8.8",
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
        val file = File(context.cacheDir, "kavita-identity-restart-$run.json")
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val repository = Injekt.get<KavitaRepository>()
        suspend fun source(id: Long): KavitaSource = withTimeout(10_000) {
            while (sources.get(id) !is KavitaSource) delay(25)
            sources.get(id) as KavitaSource
        }
        fun save(checkpoint: Checkpoint) = file.outputStream().use {
            it.write(Json.encodeToString(checkpoint).toByteArray())
            it.fd.sync()
        }
        if (phase == "prepare") {
            check(!file.exists())
            val hadActive = connections.activeConnectionId.isSet()
            val active = connections.activeConnectionId.get()
            val old = profiles.add("kavita", "Kavita identity restart $run")
            var checkpoint = Checkpoint(old.id, process = Process.myPid(), hadActive = hadActive, active = active)
            save(checkpoint)
            profiles.commitConfiguration(old.id) { draft ->
                KavitaPreferences(old.id, draft).save(address, "fixture-old", account("old"))
            }
            val oldSource = source(old.id)
            val baseline = oldSource.session().api.progress(1)
            val oldPayload = Json.encodeToString(
                KavitaReadingState(
                    KavitaChapterRef(4, 7, 8, 1, 0),
                    baseline.copy(pageNum = 3, lastModifiedUtc = "2026-01-01T00:00:01Z"),
                    10,
                    baseline,
                ),
            )
            repository.putOperation(
                old.id,
                oldSource.preferences.accountKey,
                KavitaOperation("progress/1", oldPayload, 100, true),
            )
            checkpoint =
                checkpoint.copy(
                    oldAccount = oldSource.preferences.accountKey,
                    oldPayload = oldPayload,
                    baseline = baseline,
                )
            save(checkpoint)
            val replacement = profiles.replaceIdentity(old.id, old.name) { id ->
                KavitaPreferences(id).save(address, "fixture-new", account("new"))
            }
            checkpoint = checkpoint.copy(newId = replacement.id)
            save(checkpoint)
            val current = source(replacement.id)
            assertEquals(baseline, current.session().api.progress(1))
            assertTrue(profiles.requiresIdentityReplacement(old.id))
            assertEquals("old", oldSource.preferences.principal)
            checkpoint = checkpoint.copy(newAccount = current.preferences.accountKey)
            save(checkpoint)
            assertTrue(repository.operation(replacement.id, checkpoint.newAccount, "progress/1") == null)
            val newPayload = Json.encodeToString(
                KavitaReadingState(
                    KavitaChapterRef(4, 7, 8, 1, 0),
                    baseline.copy(pageNum = 6, lastModifiedUtc = "2026-01-01T00:00:02Z"),
                    10,
                    baseline,
                ),
            )
            repository.putOperation(
                replacement.id,
                checkpoint.newAccount,
                KavitaOperation("progress/1", newPayload, 200, true),
            )
            assertTrue(checkNotNull(repository.operation(old.id, checkpoint.oldAccount, "progress/1")).pending)
            assertTrue(checkNotNull(repository.operation(replacement.id, checkpoint.newAccount, "progress/1")).pending)
            return@runBlocking
        }
        val checkpoint = Json.decodeFromString<Checkpoint>(file.readText())
        if (phase == "recover") {
            ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use {
                assertNotEquals(checkpoint.process, Process.myPid())
                val old = source(checkpoint.oldId)
                val current = source(checkpoint.newId)
                assertTrue(profiles.requiresIdentityReplacement(checkpoint.oldId))
                assertEquals("old", old.preferences.principal)
                assertEquals("new", current.preferences.principal)
                // The replacement's pending operation is recovered by Source registration, not this test.
                withTimeout(25_000) {
                    while (repository.operation(checkpoint.newId, checkpoint.newAccount, "progress/1")?.pending !=
                        false
                    ) {
                        delay(50)
                    }
                }
                val failure = runCatching { old.session().reading.flush() }.exceptionOrNull()
                assertTrue("Old source must reject the mutation", failure?.hasPausedConnectionWrites() == true)
                assertEquals(
                    KavitaOperation("progress/1", checkpoint.oldPayload, 100, true),
                    repository.operation(checkpoint.oldId, checkpoint.oldAccount, "progress/1"),
                )
                val latest = checkNotNull(repository.operation(checkpoint.newId, checkpoint.newAccount, "progress/1"))
                assertEquals(200L, latest.revision)
                assertFalse(latest.pending)
                val state = Json.decodeFromString<KavitaReadingState>(latest.payload)
                assertEquals(6, state.progress.pageNum)
                assertEquals("2026-01-01T00:00:02Z", state.progress.lastModifiedUtc)
                assertEquals(checkpoint.baseline, old.session().api.progress(1))
            }
        }
        for (id in listOf(checkpoint.oldId, checkpoint.newId).filter { it > 0 }) {
            val profile = profiles.profiles().singleOrNull { it.id == id }
            check(profile == null || profile.name.startsWith("Kavita identity restart $run"))
            profiles.remove(id).getOrThrow()
            repository.removeConnection(id)
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
