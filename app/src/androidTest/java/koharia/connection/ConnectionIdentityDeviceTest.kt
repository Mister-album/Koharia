package koharia.connection

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.preference.DeferredSharedPreferencesDataStore
import eu.kanade.tachiyomi.source.sourcePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.AndroidPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@RunWith(AndroidJUnit4::class)
class ConnectionIdentityDeviceTest {
    @Test
    fun registeredProvidersStageSeparatePreferencesAndRecoverDurably(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val registry = Injekt.get<ConnectionRegistry>()
        val json = Injekt.get<Json>()
        val prefix = "identity-fixture-${System.nanoTime()}"
        val inventory = context.getSharedPreferences(prefix, 0)
        val journalPrefs = context.getSharedPreferences("$prefix-journal", 0)
        check(inventory.all.isEmpty() && journalPrefs.all.isEmpty())
        val preferences = ConnectionPreferences(AndroidPreferenceStore(context, inventory), json) {
            inventory.edit().commit()
        }
        val journal = ConnectionIdentityChanges(AndroidSharedConfigStorage(journalPrefs), json)
        val manager = ConnectionProfileManager(preferences, registry, Injekt.get(), journal)
        val owned = mutableSetOf<String>()
        val profiles = registry.availableProviders().mapIndexed { index, provider ->
            LibraryConnectionProfile(System.currentTimeMillis() + index, provider.id, "$prefix-${provider.id}")
        } + listOf("WebDAV", "SMB").mapIndexed { index, mode ->
            LibraryConnectionProfile(System.currentTimeMillis() + 100 + index, "local-folder", "$prefix-$mode")
        }
        try {
            for (profile in profiles) {
                check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(profile.id))
                val key = "source_${profile.id}"
                check(sourcePreferences(key).all.isEmpty())
                owned += key
                check(
                    sourcePreferences(key).edit().putString("fixture-credential", "before")
                        .putString("fixture-reading-state", "retained").commit(),
                )
            }
            preferences.setProfiles(profiles)
            for (profile in profiles) {
                val old = sourcePreferences("source_${profile.id}")
                val draft = DeferredSharedPreferencesDataStore(old)
                draft.putString("fixture-credential", "after")
                val replacement = manager.replaceIdentity(profile.id, profile.name) { id ->
                    val key = "source_$id"
                    check(sourcePreferences(key).all.isEmpty())
                    owned += key
                    assertFalse(preferences.getProfiles().any { it.id == id })
                    draft.commitSelectedTo(sourcePreferences(key), setOf("fixture-credential"))
                }
                assertEquals("before", old.getString("fixture-credential", null))
                assertEquals("retained", old.getString("fixture-reading-state", null))
                assertEquals(
                    "after",
                    sourcePreferences("source_${replacement.id}")
                        .getString("fixture-credential", null),
                )
                assertFalse(sourcePreferences("source_${replacement.id}").contains("fixture-reading-state"))
                assertTrue(preferences.getProfiles().contains(profile))
                assertTrue(
                    ConnectionIdentityChanges(AndroidSharedConfigStorage(journalPrefs), json)
                        .isPaused(profile.id),
                )
                assertTrue(manager.requiresIdentityReplacement(profile.id))
                // An unchanged credential may reconnect only under a separate ID; no old reading state follows it.
                val reconnected = manager.replaceIdentity(profile.id, "${profile.name}-reconnected") { id ->
                    val key = "source_$id"
                    check(sourcePreferences(key).all.isEmpty())
                    owned += key
                    check(sourcePreferences(key).edit().putString("fixture-credential", "before").commit())
                }
                assertTrue(journal.isPaused(profile.id))
                assertFalse(journal.isPaused(reconnected.id))
                assertEquals("retained", old.getString("fixture-reading-state", null))
                assertFalse(sourcePreferences("source_${reconnected.id}").contains("fixture-reading-state"))
            }
            assertEquals(profiles.size * 3, preferences.getProfiles().size)
            assertTrue(journal.pending().isEmpty())
            for (profile in profiles) {
                val requested = "${profile.name}-renamed"
                manager.rename(profile.id, requested) { before, after ->
                    assertEquals(profile, before)
                    assertEquals(requested, after.name)
                    val recovered = ConnectionIdentityChanges(AndroidSharedConfigStorage(journalPrefs), json)
                    assertTrue(recovered.reservedProfiles().containsAll(listOf(before, after)))
                }
                val recovered = ConnectionIdentityChanges(AndroidSharedConfigStorage(journalPrefs), json)
                val restarted = ConnectionProfileManager(preferences, registry, Injekt.get(), recovered)
                val added = restarted.add(profile.providerId, profile.name)
                assertTrue(added.name != profile.name)
                assertTrue(recovered.isPaused(profile.id))
            }
        } finally {
            owned.forEach(context::deleteSharedPreferences)
            context.deleteSharedPreferences(prefix)
            context.deleteSharedPreferences("$prefix-journal")
        }
    }
}
