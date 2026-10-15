package koharia.diagnostics

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.more.settings.screen.SettingsCommonReaderScreen
import eu.kanade.presentation.reader.settings.ComicReaderSettingsContent
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR

@RunWith(AndroidJUnit4::class)
class ReaderSettingsOrganizationDeviceTest {
    @Test
    fun comicReaderUsesTheCompactSettingsContent(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val screenModel = ReaderSettingsScreenModel(
            readerState = MutableStateFlow(ReaderViewModel.State()),
            scope = settingsScope,
            onChangeReadingMode = {},
            onChangeOrientation = {},
        )
        val restores = mutableListOf<() -> Unit>()
        fun <T> snapshot(preference: Preference<T>) {
            val wasSet = preference.isSet()
            val value = preference.get()
            restores += { if (wasSet) preference.set(value) else preference.delete() }
        }
        snapshot(screenModel.preferences.readerTheme)
        snapshot(screenModel.preferences.readerCustomBackgroundColor)
        snapshot(screenModel.preferences.readerBackgroundColors)
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme { ComicReaderSettingsContent(screenModel) }
                    }
                }
                node(instrumentation, context.stringResource(MR.strings.epub_reader_brightness))
                var color = contentDescriptionNode(instrumentation, "#FBF0D9")
                while (!color.isClickable && color.parent != null) color = color.parent
                assertTrue(color.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                withTimeout(5_000) {
                    while (
                        screenModel.preferences.readerTheme.get() != ReaderPreferences.CUSTOM_BACKGROUND_THEME
                    ) {
                        delay(50)
                    }
                }
                assertEquals(0xFFFBF0D9.toInt(), screenModel.preferences.readerCustomBackgroundColor.get())
                node(instrumentation, context.stringResource(MR.strings.epub_reader_reading_mode_settings))
                node(instrumentation, context.stringResource(MR.strings.epub_reader_more_reading_settings))
            }
        } finally {
            settingsScope.cancel()
            restores.asReversed().forEach { it() }
        }
    }

    @Test
    fun commonSettingsOpenOnlyTheSelectedToolbarEditor(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.packageName == "app.koharia.dev.devicefixture")
        val comicTitle = context.stringResource(MR.strings.comic_reader_toolbar_settings)
        val bookTitle = context.stringResource(MR.strings.book_reader_toolbar_settings)

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    TachiyomiTheme { Navigator(SettingsCommonReaderScreen) }
                }
            }
            var target = node(instrumentation, bookTitle)
            while (!target.isClickable && target.parent != null) target = target.parent
            assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            withTimeout(10_000) {
                while (find(instrumentation.uiAutomation.rootInActiveWindow, comicTitle) != null) delay(100)
            }
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, comicTitle))
        }
    }

    private suspend fun node(
        instrumentation: android.app.Instrumentation,
        text: String,
    ): AccessibilityNodeInfo = withTimeoutOrNull(10_000) {
        var attempts = 0
        while (true) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            find(root, text)?.let { return@withTimeoutOrNull it }
            if (++attempts % 5 == 0) findScrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(100)
        }
        error("Unreachable")
    } ?: error("Missing text '$text': ${describe(instrumentation.uiAutomation.rootInActiveWindow)}")

    private suspend fun contentDescriptionNode(
        instrumentation: android.app.Instrumentation,
        description: String,
    ): AccessibilityNodeInfo = withTimeoutOrNull(10_000) {
        while (true) {
            findByDescription(instrumentation.uiAutomation.rootInActiveWindow, description)?.let {
                return@withTimeoutOrNull it
            }
            delay(100)
        }
        error("Unreachable")
    } ?: error("Missing description '$description': ${describe(instrumentation.uiAutomation.rootInActiveWindow)}")

    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
        return null
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (index in 0 until node.childCount) findScrollable(node.getChild(index))?.let { return it }
        return null
    }

    private fun findByDescription(node: AccessibilityNodeInfo?, description: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString() == description) return node
        for (index in 0 until node.childCount) {
            findByDescription(node.getChild(index), description)?.let { return it }
        }
        return null
    }

    private fun describe(node: AccessibilityNodeInfo?): String {
        if (node == null) return "<no root>"
        val values = mutableListOf<String>()
        fun visit(value: AccessibilityNodeInfo?) {
            if (value == null || values.size >= 40) return
            value.text?.toString()?.takeIf(String::isNotBlank)?.let(values::add)
            value.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(values::add)
            for (index in 0 until value.childCount) visit(value.getChild(index))
        }
        visit(node)
        return values.joinToString(" | ")
    }
}
