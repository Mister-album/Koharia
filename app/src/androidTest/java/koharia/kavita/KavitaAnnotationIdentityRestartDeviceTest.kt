package koharia.kavita

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.hasPausedConnectionWrites
import koharia.domain.kavita.KavitaAnnotationEntry
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

class KavitaAnnotationIdentityRestartDeviceTest {
    @Serializable
    private data class Checkpoint(
        val oldId: Long,
        val newId: Long = 0,
        val oldAccount: String = "",
        val newAccount: String = "",
        val oldPayload: String = "",
        val baseline: List<KavitaAnnotation> = emptyList(),
        val process: Int,
        val hadActive: Boolean,
        val active: Long,
    )

    private fun account(role: String) = KavitaAccount(
        id = if (role == "old") 1 else 2,
        username = role,
        token = "fixture-$role",
        roles = listOf("Login"),
        kavitaVersion = "0.9.1.4",
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
        val file = File(context.cacheDir, "kavita-annotation-identity-restart-$run.json")
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
            val old = profiles.add("kavita", "Kavita annotation identity restart $run")
            var checkpoint = Checkpoint(old.id, process = Process.myPid(), hadActive = hadActive, active = active)
            save(checkpoint)
            profiles.commitConfiguration(old.id) { draft ->
                KavitaPreferences(old.id, draft).save(address, "fixture-old", account("old"))
            }
            val oldSource = source(old.id)
            val baseline: List<KavitaAnnotation> = oldSource.session().api.get("Annotation/all?chapterId=1")
            oldSource.close()
            val oldPayload = Json.encodeToString(
                KavitaAnnotationState(KavitaAnnotation(0, 1, 7, 8, 4, 1, "//p", selectedText = "Owned fixture")),
            )
            repository.putAnnotation(
                old.id,
                oldSource.preferences.accountKey,
                KavitaAnnotationEntry("note", 1, null, oldPayload, 100, true),
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
            assertEquals(baseline, current.session().api.get<List<KavitaAnnotation>>("Annotation/all?chapterId=1"))
            current.close()
            assertTrue(profiles.requiresIdentityReplacement(old.id))
            assertEquals("old", oldSource.preferences.principal)
            checkpoint = checkpoint.copy(newAccount = current.preferences.accountKey)
            save(checkpoint)
            assertTrue(repository.annotations(replacement.id, checkpoint.newAccount).isEmpty())
            val newPayload = Json.encodeToString(
                KavitaAnnotationState(KavitaAnnotation(0, 1, 7, 8, 4, 2, "//p", selectedText = "Owned fixture")),
            )
            repository.putAnnotation(
                replacement.id,
                checkpoint.newAccount,
                KavitaAnnotationEntry("note", 1, null, newPayload, 200, true),
            )
            assertTrue(repository.annotations(old.id, checkpoint.oldAccount).single().pending)
            assertTrue(repository.annotations(replacement.id, checkpoint.newAccount).single().pending)
            return@runBlocking
        }
        val checkpoint = Json.decodeFromString<Checkpoint>(file.readText())
        if (phase == "recover") {
            assertNotEquals(checkpoint.process, Process.myPid())
            val old = source(checkpoint.oldId)
            val current = source(checkpoint.newId)
            assertTrue(profiles.requiresIdentityReplacement(checkpoint.oldId))
            assertEquals("old", old.preferences.principal)
            assertEquals("new", current.preferences.principal)
            // The replacement's pending operation is recovered by Source registration, not this test.
            try {
                withTimeout(25_000) {
                    while (repository.annotations(checkpoint.newId, checkpoint.newAccount).singleOrNull()?.pending !=
                        false
                    ) {
                        delay(50)
                    }
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                val explicitFailure = runCatching { current.session().annotations.flush() }.exceptionOrNull()
                throw AssertionError(
                    "Automatic annotation recovery failed; paused=" +
                        Injekt.get<koharia.connection.ConnectionIdentityChanges>().isPaused(checkpoint.newId) +
                        ", network=" + Injekt.get<koharia.connection.ConnectionNetworkMonitor>().available.value,
                    explicitFailure ?: timeout,
                )
            }
            val failure = runCatching { old.session().annotations.flush() }.exceptionOrNull()
            assertTrue(
                "Old source must retain the unsubmitted annotation",
                failure?.hasPausedConnectionWrites() == true,
            )
            assertEquals(
                KavitaAnnotationEntry("note", 1, null, checkpoint.oldPayload, 100, true),
                repository.annotations(checkpoint.oldId, checkpoint.oldAccount).single(),
            )
            val latest = repository.annotations(checkpoint.newId, checkpoint.newAccount).single()
            assertEquals(200L, latest.revision)
            assertFalse(latest.pending)
            assertEquals(11L, latest.remoteId)
            val state = Json.decodeFromString<KavitaAnnotationState>(latest.payload)
            assertEquals(2L, state.annotation.ownerUserId)
            assertTrue(state.submittedCreate == null)
            assertEquals(
                checkpoint.baseline,
                old.session().api.get<List<KavitaAnnotation>>("Annotation/all?chapterId=1"),
            )
        }
        for (id in listOf(checkpoint.oldId, checkpoint.newId).filter { it > 0 }) {
            val profile = profiles.profiles().singleOrNull { it.id == id }
            check(profile == null || profile.name.startsWith("Kavita annotation identity restart $run"))
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
