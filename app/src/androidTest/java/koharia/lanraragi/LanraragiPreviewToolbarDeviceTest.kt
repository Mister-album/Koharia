package koharia.lanraragi

import android.graphics.Color
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.Page
import koharia.connection.ConnectionPageList
import koharia.connection.ConnectionProfileManager
import koharia.lanraragi.ui.LanraragiArchivePreviewScreen
import koharia.source.lanraragi.LanraragiConnectionProvider
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileInputStream
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class LanraragiPreviewToolbarDeviceTest {
    @Test
    fun toolbarBackdropReturnsAfterScrollingDownAndBackToTop() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.packageName == "app.koharia.dev.devicefixture")
        val profile = Injekt.get<ConnectionProfileManager>().add(
            LanraragiConnectionProvider.ID,
            "Toolbar scroll fixture",
        )
        LanraragiPreferences(profile.id).save("http://127.0.0.1:38709/v80_test_toolbar/lrr/", "fixture-key")
        val source = withTimeout(10_000) {
            while (Injekt.get<SourceManager>().get(profile.id) !is LanraragiSource) delay(100)
            Injekt.get<SourceManager>().get(profile.id) as LanraragiSource
        }
        source.refreshLibrary().getOrThrow()
        val archive = source.repository.entries(source.id).first { it.title == "Fixture book 5" }
        val manga = source.materialize(archive)
        val chapter = LanraragiEntryOpenManager().prepareChapter(source, manga)
        val imageUrl = source.api.pages(archive.id).first()
        Injekt.get<ChapterCache>().putPageListToCache(
            chapter,
            ConnectionPageList(
                List(60) {
                    Page(it, imageUrl = imageUrl)
                },
            ),
        )

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { TachiyomiTheme { Navigator(LanraragiArchivePreviewScreen(manga.id, source.id)) } }
            }
            suspend fun node(
                predicate: (AccessibilityNodeInfo) -> Boolean,
            ): AccessibilityNodeInfo = withTimeout(15_000) {
                while (true) {
                    find(instrumentation.uiAutomation.rootInActiveWindow, predicate)?.let { return@withTimeout it }
                    delay(100)
                }
                error("Unreachable")
            }
            val downloadLabel = context.stringResource(MR.strings.action_download)
            val bounds = Rect()
            node { it.contentDescription?.toString() == downloadLabel }.getBoundsInScreen(bounds)
            fun toolbarTint(): Int {
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                return try {
                    // Sample above the navigation icons, outside the title and status-bar text.
                    val pixel = screenshot.getPixel(screenshot.width / 2, bounds.top + 2)
                    Color.red(pixel) - Color.blue(pixel)
                } finally {
                    screenshot.recycle()
                }
            }
            suspend fun scrollToTop() {
                repeat(4) {
                    val grid = node { it.isScrollable }
                    if (!grid.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                        delay(1000)
                        return
                    }
                    delay(600)
                }
                delay(1000)
            }
            suspend fun capture(name: String) {
                instrumentation.uiAutomation.executeShellCommand(
                    "screencap -p /data/local/tmp/lanraragi-toolbar-$name.png",
                ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
            }
            scrollToTop()
            withTimeout(15_000) { while (toolbarTint() < 12) delay(200) }
            delay(500)
            val initialTint = toolbarTint()
            capture("initial")
            repeat(2) { iteration ->
                val grid = node { it.isScrollable }
                assertTrue(grid.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                delay(1000)
                assertTrue("Scrolled toolbar should obscure the red backdrop", toolbarTint() < initialTint - 8)
                capture("scrolled-$iteration")
                scrollToTop()
                withTimeout(10_000) { while (abs(toolbarTint() - initialTint) > 5) delay(100) }
                capture("restored-$iteration")
            }
        }
    }

    private fun find(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }
}
