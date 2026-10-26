package koharia.kavita

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionPreferences
import koharia.connection.LibraryConnectionProfile
import koharia.kavita.ui.KavitaFilterDialog
import koharia.source.kavita.KavitaPreferences
import koharia.source.kavita.KavitaSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KavitaFilterPersistenceDeviceTest {
    @Test
    fun actualCheckboxPersistsBothDirectionsAndRestoresConfirmedSort(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val id = System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val store = sourcePreferences("source_$id")
        check(store.all.isEmpty())
        val preferences = KavitaPreferences(id)
        val source = KavitaSource(context, LibraryConnectionProfile(id, "kavita", "Filter fixture"))
        val persistenceLabel = context.stringResource(MR.strings.shelf_persistent_filters)
        val applyLabel = context.stringResource(MR.strings.action_filter)
        var applied: KavitaFilter? = null
        fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) {
            emptyList()
        } else {
            listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
        }
        suspend fun click(label: String) {
            instrumentation.sendStatus(
                2,
                android.os.Bundle().apply {
                    putString("stream", "Click $label\n")
                },
            )
            fun target(): AccessibilityNodeInfo? {
                // The sheet heading and Apply button share a label. Do not climb from the
                // heading to the sheet's dismiss surface and mistake it for the button.
                return nodes(instrumentation.uiAutomation.rootInActiveWindow)
                    .filter { it.text?.toString() == label }
                    .firstNotNullOfOrNull { node ->
                        node.takeIf { it.isClickable } ?: node.parent?.takeIf { it.isClickable }
                    }
            }
            withTimeout(10_000) {
                while (true) {
                    if (target()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) break
                    delay(100)
                }
            }
            instrumentation.waitForIdleSync()
            // Accessibility actions return before Compose has refreshed the next click's callback.
            val rendered = kotlinx.coroutines.CompletableDeferred<Unit>()
            instrumentation.runOnMainSync {
                val frames = android.view.Choreographer.getInstance()
                frames.postFrameCallback {
                    frames.postFrameCallback { rendered.complete(Unit) }
                }
            }
            withTimeout(5_000) { rendered.await() }
        }
        try {
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                fun show(initial: KavitaFilter) {
                    scenario.onActivity { activity ->
                        activity.setContent {
                            TachiyomiTheme {
                                KavitaFilterDialog(
                                    source = source,
                                    initial = initial,
                                    libraries = emptyList(),
                                    cachedOnly = true,
                                    persistentFilters = preferences.persistentFilters,
                                    onPersistentFiltersChange = { preferences.persistentFilters = it },
                                    onDismissRequest = {},
                                    onApply = { value ->
                                        applied = value
                                        preferences.commitFilter(
                                            Json.encodeToString(value),
                                            "${value.sortOptions.sortField} ${if (value.sortOptions.isAscending) "asc" else "desc"}",
                                            preferences.persistentFilters,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                assertFalse(preferences.persistentFilters)
                show(KavitaFilter())
                click(persistenceLabel)
                assertTrue(preferences.persistentFilters)
                click(context.stringResource(MR.strings.kavita_descending))
                click(applyLabel)
                withTimeout(5_000) { while (applied == null) delay(100) }
                val restored = KavitaPreferences(id)
                assertEquals("1 desc", restored.initialOrder())
                assertEquals(applied, restored.initialFilter(null))
                assertFalse(checkNotNull(restored.initialFilter(null)).sortOptions.isAscending)
                scenario.recreate()
                show(checkNotNull(restored.initialFilter(null)))
                click(persistenceLabel)
                assertFalse(preferences.persistentFilters)
                applied = null
                click(applyLabel)
                withTimeout(5_000) { while (applied == null) delay(100) }
                assertEquals(KavitaPreferences.DEFAULT_ORDER, KavitaPreferences(id).initialOrder())
                assertNull(KavitaPreferences(id).initialFilter(null))
            }
        } finally {
            source.close()
            store.edit().clear().commit()
        }
    }
}
