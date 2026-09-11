package koharia.lanraragi

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.ui.main.MainActivity
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.lanraragi.ui.LanraragiLibraryScreen
import koharia.source.lanraragi.LanraragiConnectionProvider
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileInputStream

/** Run seedColdStart, force-stop only the fixture app, then coldStartShowsCacheWithoutRefresh. */
@RunWith(AndroidJUnit4::class)
class LanraragiStartupRefreshDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val saved = context.getSharedPreferences("lanraragi-startup-test", 0)

    @Test
    fun seedColdStart() = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        control(false)
        val source = newSource("Cold startup regression")
        source.refreshLibrary().getOrThrow()
        saved.edit().putLong("sourceId", source.id).putLong("lastSync", source.repository.lastSync(source.id)).commit()
        Injekt.get<ConnectionPreferences>().activeConnectionId.set(source.id)
        Injekt.get<BasePreferences>().apply {
            downloadedOnly.set(false)
            shownOnboardingFlow.set(true)
        }
        control(true)
    }

    @Test
    fun coldStartShowsCacheWithoutRefresh() = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val id = saved.getLong("sourceId", -1)
        assertTrue("Run seedColdStart first", id > 0)
        val source = source(id)
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitLabel("Fixture book 5")
            assertEquals(saved.getLong("lastSync", 0), source.repository.lastSync(id))
            capture("cold-start")
        }
    }

    @Test
    fun emptyPageRetryRefreshesServerAndPublishesLibrary() = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        control(true)
        val source = newSource("Empty retry regression")
        source.refreshLibrary()
        Injekt.get<BasePreferences>().downloadedOnly.set(false)
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme { Navigator(LanraragiLibraryScreen(source.id, null, false)) }
                    }
                }
                awaitLabel(context.stringResource(MR.strings.action_retry))
                delay(1500)
                withTimeout(10_000) { while (source.status.value.running) delay(100) }
                val retry = awaitLabel(context.stringResource(MR.strings.action_retry))
                assertTrue(source.repository.entries(source.id).isEmpty())
                capture("empty")
                control(false)
                // Explicit retry must work even when Android's connectivity hint is stale.
                source.networkAvailable.value = false
                var target: AccessibilityNodeInfo? = retry
                while (target != null && !target.isClickable) target = target.parent
                assertTrue(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                awaitLabel("Fixture book 5")
                assertTrue(source.repository.lastSync(source.id) > 0)
                capture("after-retry")
            }
        } finally {
            control(false)
        }
    }

    private suspend fun newSource(name: String): LanraragiSource {
        val profile = Injekt.get<ConnectionProfileManager>().add(LanraragiConnectionProvider.ID, name)
        LanraragiPreferences(profile.id).save("http://127.0.0.1:38709/v80/lrr/", "fixture-key")
        return source(profile.id)
    }

    private suspend fun source(id: Long): LanraragiSource = withTimeout(10_000) {
        while (Injekt.get<SourceManager>().get(id) !is LanraragiSource) delay(100)
        Injekt.get<SourceManager>().get(id) as LanraragiSource
    }

    private suspend fun awaitLabel(label: String): AccessibilityNodeInfo = withTimeout(20_000) {
        while (true) {
            find(instrumentation.uiAutomation.rootInActiveWindow, label)?.let { return@withTimeout it }
            delay(100)
        }
        error("Unreachable")
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }

    private fun control(offline: Boolean) {
        val url = "http://127.0.0.1:38709/_fixture/control?version=v80&offline=$offline"
        OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { assertTrue(it.isSuccessful) }
    }

    private fun capture(name: String) {
        instrumentation.uiAutomation.executeShellCommand(
            "screencap -p /data/local/tmp/lanraragi-startup-$name.png",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
    }
}
