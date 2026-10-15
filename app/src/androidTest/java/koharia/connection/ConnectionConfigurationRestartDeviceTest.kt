package koharia.connection

import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.sourcePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Run prepare and recover in separate instrumentation processes, retaining the fixture's data. */
class ConnectionConfigurationRestartDeviceTest {
    @Test
    fun interruptedConfigurationResumesThroughProductionStartup(): Unit = runBlocking(Dispatchers.IO) {
        val phase = InstrumentationRegistry.getArguments().getString("configurationPhase")
        assumeTrue(phase in setOf("prepare", "recover"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val control = context.getSharedPreferences("configuration_restart_fixture", 0)
        val profiles = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val journal = Injekt.get<ConnectionIdentityChanges>()
        if (phase == "prepare") {
            check(control.all.isEmpty()) { "Previous fixture must be recovered first" }
            val profile = profiles.add("local-folder", "Configuration restart ${System.nanoTime()}")
            Injekt.get<ConnectionPreferences>().flush()
            check(
                control.edit().putLong("id", profile.id).putString("name", profile.name)
                    .putInt("pid", android.os.Process.myPid()).commit(),
            )
            val live = sourcePreferences("source_${profile.id}")
            check(live.edit().putString("fixture-phase", "before").commit())
            val draft = sourcePreferences("connection_configuration_draft_${profile.id}")
            val coordinator = ConnectionConfigurationCommit(
                journal,
                {
                    object : SharedConfigStorage by AndroidSharedConfigStorage(live) {
                        override fun commit(values: Map<String, Any?>) = false
                    }
                },
                { AndroidSharedConfigStorage(draft) },
            )
            assertTrue(
                runCatching {
                    coordinator.commit(profile.id, { block ->
                        (sources as ConnectionSourceRestorer).withSourceConfiguration(profile.id, block)
                    }) { check(draft.edit().putString("fixture-phase", "recovered").commit()) }
                }.isFailure,
            )
            assertEquals("before", live.getString("fixture-phase", null))
            assertTrue(journal.isPaused(profile.id))
            assertNotNull(journal.pendingConfigurations().single { it.connectionId == profile.id }.keys)
            return@runBlocking
        }
        val id = control.getLong("id", -1)
        check(id > 0)
        check(profiles.profiles().single { it.id == id }.name == control.getString("name", null))
        try {
            assertNotEquals(control.getInt("pid", -1), android.os.Process.myPid())
            assertEquals("recovered", sourcePreferences("source_$id").getString("fixture-phase", null))
            assertFalse(journal.isPaused(id))
            assertTrue(journal.pendingConfigurations().none { it.connectionId == id })
            assertTrue(sourcePreferences("connection_configuration_draft_$id").all.isEmpty())
            assertNotNull(sources.get(id))
        } finally {
            profiles.remove(id).getOrThrow()
            withTimeout(10_000) { sources.catalogueSources.first { it.none { source -> source.id == id } } }
            context.deleteSharedPreferences("connection_configuration_draft_$id")
            context.deleteSharedPreferences("configuration_restart_fixture")
        }
    }
}
