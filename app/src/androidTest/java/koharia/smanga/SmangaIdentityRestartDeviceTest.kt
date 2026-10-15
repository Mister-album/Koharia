package koharia.smanga

import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.hasPausedConnectionWrites
import koharia.domain.smanga.SmangaHistoryEvent
import koharia.domain.smanga.SmangaReadState
import koharia.domain.smanga.SmangaRepository
import koharia.source.smanga.SmangaPreferences
import koharia.source.smanga.SmangaSource
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

class SmangaIdentityRestartDeviceTest {
    @Serializable
    private data class Checkpoint(
        val oldId: Long,
        val newId: Long = 0,
        val oldAccount: String = "",
        val newAccount: String = "",
        val baseline: SmangaChapter? = null,
        val process: Int,
        val hadActive: Boolean,
        val active: Long,
    )

    @Test
    fun credentialReplacementRetainsOldPendingAcrossSourceRestart() = runIdentityRestart(false)

    @Test
    fun committedHistoryRemainsUncertainAfterRestart() = runIdentityRestart(true)

    private fun runIdentityRestart(uncertainHistory: Boolean): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("identityRestartPhase")
        assumeTrue(phase in setOf("prepare", "recover", "cleanup"))
        val run = checkNotNull(args.getString("identityRestartRun"))
        require(run.matches(Regex("[0-9]+")))
        val address = checkNotNull(args.getString("identityRestartAddress"))
        require(address.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/")))
        val file = File(context.cacheDir, "smanga-identity-restart-$run.json")
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val repository = Injekt.get<SmangaRepository>()
        suspend fun source(id: Long): SmangaSource = withTimeout(10_000) {
            while (sources.get(id) !is SmangaSource) delay(25)
            sources.get(id) as SmangaSource
        }
        fun save(checkpoint: Checkpoint) = file.outputStream().use {
            it.write(Json.encodeToString(checkpoint).toByteArray())
            it.fd.sync()
        }
        if (phase == "prepare") {
            check(!file.exists())
            val hadActive = connections.activeConnectionId.isSet()
            val active = connections.activeConnectionId.get()
            val old = profiles.add("smanga", "Smanga identity restart $run")
            var checkpoint = Checkpoint(old.id, process = Process.myPid(), hadActive = hadActive, active = active)
            save(checkpoint)
            profiles.commitConfiguration(old.id) { draft ->
                SmangaPreferences(old.id, draft).save(address, "old", "fixture", 1)
            }
            val oldSource = source(old.id)
            val baseline = oldSource.session().api.chapter(1)
            repository.putReadState(
                old.id,
                oldSource.preferences.accountKey,
                SmangaReadState(1, 7, 3, 10, false, 100, 100, true),
            )
            checkpoint = checkpoint.copy(oldAccount = oldSource.preferences.accountKey, baseline = baseline)
            save(checkpoint)
            val replacement = profiles.replaceIdentity(old.id, old.name) { id ->
                SmangaPreferences(id).save(address, "new", "fixture", 2)
            }
            checkpoint = checkpoint.copy(newId = replacement.id)
            save(checkpoint)
            val current = source(replacement.id)
            assertEquals(baseline, current.session().api.chapter(1))
            assertTrue(profiles.requiresIdentityReplacement(old.id))
            assertEquals("old", oldSource.preferences.username)
            checkpoint = checkpoint.copy(newAccount = current.preferences.accountKey)
            save(checkpoint)
            assertTrue(repository.readState(replacement.id, checkpoint.newAccount, 1) == null)
            repository.putReadState(
                replacement.id,
                checkpoint.newAccount,
                SmangaReadState(1, 7, 6, 10, false, 200, 200, true),
            )
            repository.enqueueHistory(old.id, checkpoint.oldAccount, SmangaHistoryEvent("identity-$run", 9, 2, 4, 100))
            repository.enqueueHistory(
                replacement.id,
                checkpoint.newAccount,
                SmangaHistoryEvent("identity-$run", 9, 2, 4, 200),
            )
            assertTrue(checkNotNull(repository.readState(old.id, checkpoint.oldAccount, 1)).pending)
            assertTrue(checkNotNull(repository.readState(replacement.id, checkpoint.newAccount, 1)).pending)
            return@runBlocking
        }
        val checkpoint = Json.decodeFromString<Checkpoint>(file.readText())
        val expectedHistoryStatus = if (uncertainHistory) 1 else 2
        if (phase == "recover") {
            ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use {
                assertNotEquals(checkpoint.process, Process.myPid())
                val old = source(checkpoint.oldId)
                val current = source(checkpoint.newId)
                assertTrue(profiles.requiresIdentityReplacement(checkpoint.oldId))
                assertEquals("old", old.preferences.username)
                assertEquals("new", current.preferences.username)
                // The replacement's pending operation is recovered by Source registration, not this test.
                withTimeout(25_000) {
                    while (repository.readState(checkpoint.newId, checkpoint.newAccount, 1)?.pending != false ||
                        repository.historyEvent(checkpoint.newId, checkpoint.newAccount, "identity-$run")?.status !=
                        expectedHistoryStatus
                    ) {
                        delay(50)
                    }
                }
                val failure = runCatching { old.session().api.pushProgress(7, 1, 3, 10, false) }.exceptionOrNull()
                assertTrue("Old source must reject the mutation", failure?.hasPausedConnectionWrites() == true)
                assertEquals(
                    SmangaReadState(1, 7, 3, 10, false, 100, 100, true),
                    repository.readState(checkpoint.oldId, checkpoint.oldAccount, 1),
                )
                assertEquals(
                    SmangaHistoryEvent("identity-$run", 9, 2, 4, 100),
                    repository.historyEvent(checkpoint.oldId, checkpoint.oldAccount, "identity-$run"),
                )
                assertEquals(
                    SmangaHistoryEvent("identity-$run", 9, 2, 4, 200, status = expectedHistoryStatus),
                    repository.historyEvent(checkpoint.newId, checkpoint.newAccount, "identity-$run"),
                )
                val latest = checkNotNull(repository.readState(checkpoint.newId, checkpoint.newAccount, 1))
                assertEquals(200L, latest.revision)
                assertFalse(latest.pending)
                assertEquals(6, latest.pageIndex)
                assertEquals(200L, latest.readAt)
                assertEquals(checkpoint.baseline, old.session().api.chapter(1))
            }
        }
        for (id in listOf(checkpoint.oldId, checkpoint.newId).filter { it > 0 }) {
            val profile = profiles.profiles().singleOrNull { it.id == id }
            check(profile == null || profile.name.startsWith("Smanga identity restart $run"))
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
