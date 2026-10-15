package koharia.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.usesAutomaticChapterCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

@RunWith(AndroidJUnit4::class)
class LegacyTrackingQueueDeviceTest {
    @Test
    fun quarantinePreservesExactValuesAndTypesAndRepeatedAttemptsDoNotLoseNewEvents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val name = "legacy-tracking-fixture-${System.nanoTime()}"
        val preferences = context.getSharedPreferences(name, 0)
        check(preferences.all.isEmpty())
        try {
            assertTrue(
                preferences.edit().putFloat("41", 1.25f).putString("malformed", "preserved")
                    .putStringSet("unexpected-set", setOf("first", "second")).commit(),
            )
            val store = DelayedTrackingStore(preferences)
            assertEquals(listOf(DelayedTrackingStore.DelayedTrackingItem(41, 1.25f)), store.getItems())
            assertTrue(store.holdLegacyItems())
            assertTrue(store.getItems().isEmpty())
            val held = store.getHeldItems()
            assertEquals(3, held.size)
            assertTrue(held.all { it.reason == "unknown_owner" })
            assertEquals(1.25f, held.single { it.originalKey == "41" }.originalValue)
            assertEquals("preserved", held.single { it.originalKey == "malformed" }.originalValue)
            assertEquals(setOf("first", "second"), held.single { it.originalKey == "unexpected-set" }.originalValue)
            val disk = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").readText()
            assertTrue(disk.contains("held::unknown_owner::"))
            assertFalse(disk.contains("name=\"41\""))
            assertTrue(store.holdLegacyItems())
            assertEquals(held.toSet(), DelayedTrackingStore(preferences).getHeldItems().toSet())
            store.add(41, 2.75)
            assertEquals(3, store.getHeldItems().size)
            assertEquals(2.75f, store.getItems().single().lastChapterRead)
            assertTrue(store.holdLegacyItems())
            assertEquals(
                setOf(1.25f, 2.75f),
                store.getHeldItems().filter { it.originalKey == "41" }
                    .map { it.originalValue }.toSet(),
            )
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun productionKomgaTrackerDefersAutomaticProgressToSourceReadingUnits() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "app.koharia.dev.devicefixture")
        val manager = Injekt.get<TrackerManager>()
        assertTrue(manager.komga.usesSourceReadingProgress)
        assertFalse(manager.komga.usesAutomaticChapterCounts())
        assertTrue(manager.trackers.filterIsInstance<EnhancedTracker>().all { it.usesSourceReadingProgress })
    }
}
