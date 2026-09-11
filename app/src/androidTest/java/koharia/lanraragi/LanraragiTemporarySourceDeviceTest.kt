package koharia.lanraragi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionProfileManager
import koharia.connection.ConnectionRegistry
import koharia.domain.lanraragi.LanraragiEntry
import koharia.source.lanraragi.LanraragiConnectionProvider
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@RunWith(AndroidJUnit4::class)
class LanraragiTemporarySourceDeviceTest {
    @Test
    fun temporaryStorageSourcesArePassiveAndExplicitParallelSyncsKeepAllArchives() = runBlocking(Dispatchers.IO) {
        assertEquals(
            "app.koharia.dev.devicefixture",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
        val profile = Injekt.get<ConnectionProfileManager>().add(
            LanraragiConnectionProvider.ID,
            "Temporary source regression",
        )
        LanraragiPreferences(profile.id).save("http://127.0.0.1:38709/v80_test_temporary/lrr/", "fixture-key")
        val source = withTimeout(10_000) {
            while (Injekt.get<SourceManager>().get(profile.id) !is LanraragiSource) delay(100)
            Injekt.get<SourceManager>().get(profile.id) as LanraragiSource
        }
        source.refreshLibrary().getOrThrow()
        delay(1500)
        val entries = source.repository.entries(source.id)
        assertEquals(5, entries.count { it.kind == LanraragiEntry.Kind.ARCHIVE })
        val generation = source.repository.lastSync(source.id) + 1
        val staleTime = System.currentTimeMillis() - 120_000
        source.repository.stage(source.id, generation, entries)
        source.repository.publish(source.id, generation, staleTime)

        // Same constructor used by download-directory migration and detached storage lookup.
        val temporary = List(6) { Injekt.get<ConnectionRegistry>().createSource(profile) as LanraragiSource }
        try {
            temporary.forEach { assertEquals("LANraragi_${profile.id}", it.downloadDirectoryName()) }
            delay(2500)
            assertEquals(staleTime, source.repository.lastSync(source.id))
            assertTrue(temporary.all { !it.status.value.running && it.status.value.completedAt == 0L })

            withTimeout(30_000) {
                temporary.map { instance -> async { instance.refreshLibrary().getOrThrow() } }.awaitAll()
            }
            val refreshed = source.repository.entries(source.id)
            assertEquals(entries.map { it.id }.toSet(), refreshed.map { it.id }.toSet())
            assertEquals(5, refreshed.count { it.kind == LanraragiEntry.Kind.ARCHIVE })

            // Reproduce the observed fresh "categories only" cache. A newly registered
            // source must repair it on first startup instead of skipping it for one minute.
            val damagedGeneration = source.repository.lastSync(source.id) + 1
            source.repository.stage(
                source.id,
                damagedGeneration,
                entries.filter {
                    it.kind ==
                        LanraragiEntry.Kind.CATEGORY
                },
            )
            source.repository.publish(source.id, damagedGeneration, System.currentTimeMillis())
            val replacement = Injekt.get<ConnectionRegistry>().createSource(profile) as LanraragiSource
            try {
                replacement.onRegistered()
                withTimeout(15_000) {
                    while (source.repository.entries(source.id).count { it.kind == LanraragiEntry.Kind.ARCHIVE } != 5) {
                        delay(100)
                    }
                }
            } finally {
                replacement.close()
            }
        } finally {
            temporary.forEach(LanraragiSource::close)
        }
    }
}
