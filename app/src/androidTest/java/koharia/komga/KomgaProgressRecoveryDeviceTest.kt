package koharia.komga

import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.AndroidSharedConfigStorage
import koharia.connection.ConnectionIdentityChanges
import koharia.connection.ConnectionPreferences
import koharia.connection.LibraryConnectionProfile
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository
import koharia.komga.progress.KomgaProgressRecovery
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KomgaProgressRecoveryDeviceTest {
    @Test
    fun processRecoveryAfterCommitWithoutWakeup(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val phase = InstrumentationRegistry.getArguments().getString("komgaRecoveryPhase")
        org.junit.Assume.assumeTrue(phase in setOf("prepare", "recover"))
        val checkpointName = "komga-work-recovery-checkpoint"
        val checkpoint = context.getSharedPreferences(checkpointName, 0)
        val connections = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val pages = Injekt.get<KomgaPageEventRepository>()
        val manager = androidx.work.WorkManager.getInstance(context)
        if (phase == "prepare") {
            check(checkpoint.all.isEmpty()) { "Recover the previous fixture checkpoint first" }
            koharia.komga.progress.KomgaProgressRecoveryJob.enqueueAtStartup(context)
            kotlinx.coroutines.withTimeout(20_000) {
                while (manager.getWorkInfosForUniqueWork("komga-progress-recovery").get().any {
                        !it.state.isFinished
                    }
                ) {
                    kotlinx.coroutines.delay(50)
                }
            }
            val id = System.nanoTime()
            check(!connections.isKnownConnectionId(id))
            check(sourcePreferences("source_$id").all.isEmpty())
            check(
                checkpoint.edit().putLong("source", id)
                    .putLong("previousActive", connections.activeConnectionId.get()).commit(),
            )
            check(
                sourcePreferences("source_$id").edit().putString("Address", "http://127.0.0.1:1")
                    .putString("AuthMode", "Credentials").putString("Username", "fixture")
                    .putString("Password", "fixture").commit(),
            )
            connections.mutateProfiles { it + LibraryConnectionProfile(id, "komga", "Work recovery fixture") }
            kotlinx.coroutines.withTimeout(5_000) {
                while (sources.get(id) !is KomgaSource) kotlinx.coroutines.delay(20)
            }
            val source = sources.get(id) as KomgaSource
            val key = KomgaReadStatusKey(id, source.progressAccount().key, "book")
            check(checkpoint.edit().putString("owner", key.ownerKey).commit())
            pages.record(key, 0, 4, 10, 100, null) {}
            assertTrue(
                manager.getWorkInfosByTag(koharia.komga.progress.KomgaProgressWorkManager.name(key)).get().isEmpty(),
            )
            return@runBlocking
        }
        val id = checkpoint.getLong("source", -1)
        check(id > 0 && connections.getProfiles().any { it.id == id && it.name == "Work recovery fixture" })
        val key = KomgaReadStatusKey(id, checkNotNull(checkpoint.getString("owner", null)), "book")
        val name = koharia.komga.progress.KomgaProgressWorkManager.name(key)
        try {
            // App startup must discover the committed event; the test deliberately does not enqueue it.
            kotlinx.coroutines.withTimeout(20_000) {
                while (pages.get(key)?.phase != KomgaReadStatusPhase.HELD ||
                    manager.getWorkInfosByTag(name).get().none { it.state.isFinished }
                ) {
                    kotlinx.coroutines.delay(50)
                }
            }
            val event = checkNotNull(pages.get(key))
            assertEquals(1L, event.generation)
            assertEquals(4L, event.page)
            assertEquals(100L, event.readAt)
            assertEquals("HELD", manager.getWorkInfosByTag(name).get().single().outputData.getString("progressStatus"))
            Injekt.get<KomgaProgressRecovery>().recover()
            assertEquals(1, manager.getWorkInfosByTag(name).get().size)
        } finally {
            manager.cancelUniqueWork(name).result.get()
            connections.mutateProfiles { it.filterNot { profile -> profile.id == id } }
            if (connections.activeConnectionId.get() == id) {
                connections.activeConnectionId.set(checkpoint.getLong("previousActive", -1))
            }
            pages.removeConnection(id)
            Injekt.get<KomgaReadStatusRepository>().removeConnection(id)
            context.deleteSharedPreferences("source_$id")
            context.deleteSharedPreferences(checkpointName)
        }
    }

    @Test
    fun recoveryRetainsTerminalsAndSelectsExactOwnersIncludingNewExplicitActions(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val id = System.nanoTime()
        val connections = Injekt.get<ConnectionPreferences>()
        check(!connections.isKnownConnectionId(id))
        val prefs = sourcePreferences("source_$id")
        val journalName = "recovery-fixture-$id"
        val journal = context.getSharedPreferences(journalName, 0)
        check(prefs.all.isEmpty() && journal.all.isEmpty())
        val pages = Injekt.get<KomgaPageEventRepository>()
        val intents = Injekt.get<KomgaReadStatusRepository>()
        try {
            check(
                prefs.edit().putString("Address", "http://127.0.0.1:1")
                    .putString("AuthMode", "Credentials").putString("Username", "fixture")
                    .putString("Password", "fixture").commit(),
            )
            val source = KomgaSource(id)
            val realSources = Injekt.get<SourceManager>()
            val sources = object : SourceManager by realSources {
                override fun get(sourceKey: Long) = source.takeIf { sourceKey == id }
            }
            val identities = ConnectionIdentityChanges(AndroidSharedConfigStorage(journal), Json)
            val key = KomgaReadStatusKey(id, source.progressAccount().key, "page")
            val recovered = mutableListOf<KomgaReadStatusKey>()
            val recovery = KomgaProgressRecovery(pages, sources, identities) { recovered += it }
            val page = checkNotNull(pages.record(key, 0, 2, 10, 100, null) {})
            pages.begin(page, "uncertain") {}
            pages.record(key, 0, 4, 10, 101, null) {}
            val explicitKey = key.copy(bookId = "explicit")
            val denied = checkNotNull(pages.record(explicitKey, 0, 3, 10, 100, null) {})
            pages.transition(denied, KomgaReadStatusPhase.DENIED) {}
            intents.record(explicitKey, false, 101, null, emptyList()) {}
            val requestKey = key.copy(bookId = "request")
            val request = intents.record(requestKey, true, 100, "before", emptyList()) {}
            intents.begin(request, "before") {}
            intents.finish(requestKey, request.revision, KomgaReadStatusPhase.SYNCED)
            for (phase in listOf(
                KomgaReadStatusPhase.HELD,
                KomgaReadStatusPhase.CONFLICT,
                KomgaReadStatusPhase.DENIED,
            )) {
                val terminalKey = key.copy(bookId = phase.name)
                val terminal = checkNotNull(pages.record(terminalKey, 0, 1, 10, 100, null) {})
                pages.transition(terminal, phase) {}
                val desired = intents.record(key.copy(bookId = "intent-${phase.name}"), false, 100, null, emptyList()) {
                }
                intents.transition(desired, phase) {}
            }
            pages.record(key.copy(ownerKey = "other-account"), 0, 1, 10, 100, null) {}
            pages.record(key.copy(ownerKey = "restored:fixture:0"), 0, 1, 10, 100, null) {}
            val before = pages.unsettledStates().filter {
                (it.event?.key ?: it.attempt?.event?.key)?.connectionId == id
            }
            assertEquals(3, recovery.recover())
            assertEquals(setOf(key, explicitKey, requestKey), recovered.toSet())
            assertEquals(
                before,
                pages.unsettledStates().filter {
                    (it.event?.key ?: it.attempt?.event?.key)?.connectionId ==
                        id
                },
            )
            identities.prepareImport(listOf(LibraryConnectionProfile(id, "komga", "Recovery fixture")))
            identities.readyImported(setOf(id))
            recovered.clear()
            assertEquals(0, recovery.recover())
            assertTrue(recovered.isEmpty())
        } finally {
            pages.removeConnection(id)
            intents.removeConnection(id)
            context.deleteSharedPreferences("source_$id")
            context.deleteSharedPreferences(journalName)
        }
    }
}
