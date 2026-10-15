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
import kotlinx.serialization.json.JsonObject
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

class KavitaBookmarkIdentityRestartDeviceTest {
    @Serializable
    private data class Checkpoint(
        val oldId: Long,
        val newId: Long = 0,
        val oldAccount: String = "",
        val newAccount: String = "",
        val process: Int,
        val hadActive: Boolean,
        val active: Long,
    )

    private fun account(role: String) = KavitaAccount(
        id = if (role == "old") 1 else 2,
        username = role,
        token = "fixture-$role",
        roles = listOf("Login", "Bookmark"),
        kavitaVersion = "0.9.1.4",
    )

    private fun states(role: String) = KavitaBookmarkKind.entries.map { kind ->
        KavitaBookmarkState(
            KavitaChapterRef(7, 8, 4, 1, if (kind == KavitaBookmarkKind.TOC) 3 else 1),
            kind,
            page = 3,
            title = "Owned fixture",
            anchor = "//$role",
        )
    }

    @Test
    fun credentialsKeepBothBookmarkKindsWithOriginalOwnerAfterRestart(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("identityRestartPhase")
        assumeTrue(phase in setOf("prepare", "recover", "cleanup"))
        val run = checkNotNull(args.getString("identityRestartRun"))
        require(run.matches(Regex("[0-9]+")))
        val address = checkNotNull(args.getString("identityRestartAddress"))
        require(address.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/")))
        val file = File(context.cacheDir, "kavita-bookmark-identity-restart-$run.json")
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
            val old = profiles.add("kavita", "Kavita bookmark identity restart $run")
            var checkpoint = Checkpoint(old.id, process = Process.myPid(), hadActive = hadActive, active = active)
            save(checkpoint)
            profiles.commitConfiguration(old.id) { draft ->
                KavitaPreferences(old.id, draft).save(address, "fixture-old", account("old"))
            }
            val previous = source(old.id)
            for (state in states("old")) {
                assertTrue(previous.session().api.get<List<JsonObject>>(state.path).isEmpty())
            }
            previous.close()
            checkpoint = checkpoint.copy(oldAccount = previous.preferences.accountKey)
            save(checkpoint)
            for (state in states("old")) {
                repository.putOperation(
                    old.id,
                    checkpoint.oldAccount,
                    KavitaOperation(state.key, Json.encodeToString(state), 100, true),
                )
            }
            val replacement = profiles.replaceIdentity(old.id, old.name) { id ->
                KavitaPreferences(id).save(address, "fixture-new", account("new"))
            }
            checkpoint = checkpoint.copy(newId = replacement.id)
            save(checkpoint)
            val current = source(replacement.id)
            for (state in states("new")) {
                assertTrue(current.session().api.get<List<JsonObject>>(state.path).isEmpty())
            }
            current.close()
            checkpoint = checkpoint.copy(newAccount = current.preferences.accountKey)
            save(checkpoint)
            assertNotEquals(checkpoint.oldAccount, checkpoint.newAccount)
            assertTrue(repository.operations(replacement.id, checkpoint.newAccount).isEmpty())
            for (state in states("new")) {
                repository.putOperation(
                    replacement.id,
                    checkpoint.newAccount,
                    KavitaOperation(state.key, Json.encodeToString(state), 200, true),
                )
            }
            assertTrue(profiles.requiresIdentityReplacement(old.id))
            return@runBlocking
        }
        val checkpoint = Json.decodeFromString<Checkpoint>(file.readText())
        if (phase == "recover") {
            val foreground = ActivityScenario.launch(EInkMotionFixtureActivity::class.java)
            try {
                assertNotEquals(checkpoint.process, Process.myPid())
                val old = source(checkpoint.oldId)
                val current = source(checkpoint.newId)
                assertTrue(profiles.requiresIdentityReplacement(checkpoint.oldId))
                assertEquals("old", old.preferences.principal)
                assertEquals("new", current.preferences.principal)
                // Source registration recovers both bookmark kinds without an explicit new-account flush.
                withTimeout(30_000) {
                    while (repository.pendingOperations(checkpoint.newId, checkpoint.newAccount).isNotEmpty()) {
                        delay(50)
                    }
                }
                val failure = runCatching { old.session().bookmarks.flush() }.exceptionOrNull()
                assertTrue("Old bookmarks must remain paused", failure?.hasPausedConnectionWrites() == true)
                for (state in states("old")) {
                    assertEquals(
                        KavitaOperation(state.key, Json.encodeToString(state), 100, true),
                        repository.operation(checkpoint.oldId, checkpoint.oldAccount, state.key),
                    )
                    assertTrue(old.session().api.get<List<JsonObject>>(state.path).isEmpty())
                }
                for (state in states("new")) {
                    val latest = checkNotNull(repository.operation(checkpoint.newId, checkpoint.newAccount, state.key))
                    assertEquals(200L, latest.revision)
                    assertEquals(Json.encodeToString(state), latest.payload)
                    assertFalse(latest.pending)
                    assertEquals(listOf(state.body()), current.session().api.get<List<JsonObject>>(state.path))
                }
            } finally {
                foreground.close()
            }
        }
        for (id in listOf(checkpoint.oldId, checkpoint.newId).filter { it > 0 }) {
            val profile = profiles.profiles().singleOrNull { it.id == id }
            check(profile == null || profile.name.startsWith("Kavita bookmark identity restart $run"))
            profiles.remove(id).getOrThrow()
            repository.removeConnection(id)
        }
        if (checkpoint.hadActive) {
            connections.activeConnectionId.set(checkpoint.active)
        } else {
            connections.activeConnectionId.delete()
        }
        assertTrue(file.delete())
    }
}
