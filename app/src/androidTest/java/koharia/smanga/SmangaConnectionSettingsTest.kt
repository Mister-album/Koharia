package koharia.smanga

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ui.ConnectionAddressSetting
import koharia.connection.ui.ConnectionProviderIcon
import koharia.source.smanga.SmangaConnectionProvider
import koharia.source.smanga.SmangaPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SmangaConnectionSettingsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun sharedAddressDialogDiscardsCancelledDraftsAndPreservesAccountScope(): Unit = runBlocking(Dispatchers.IO) {
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val connectionId = -System.nanoTime()
        val preferenceName = "source_$connectionId"
        check(sourcePreferences(preferenceName).all.isEmpty())
        val preferences = SmangaPreferences(connectionId)
        try {
            preferences.save("https://public.invalid/reader", "fixture", "fixture-password", 3)
            preferences.mediaId = 7
            preferences.order = "updateTime desc"
            val accountKey = preferences.accountKey
            val confirmations = AtomicInteger()
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme {
                            var publicAddress by remember { mutableStateOf(preferences.address) }
                            var internalAddress by remember { mutableStateOf(preferences.internalAddress) }
                            Column(Modifier.padding(top = 48.dp)) {
                                ConnectionProviderIcon(SmangaConnectionProvider.ID, Modifier.size(32.dp))
                                ConnectionAddressSetting(publicAddress, internalAddress, true) { public, internal ->
                                    preferences.save(public, "fixture", "fixture-password", 3, internal)
                                    publicAddress = preferences.address
                                    internalAddress = preferences.internalAddress
                                    confirmations.incrementAndGet()
                                }
                            }
                        }
                    }
                }
                click(awaitLabel(context.stringResource(MR.strings.lanraragi_address)))
                click(awaitLabel(context.stringResource(MR.strings.connection_address_advanced)))
                val inputs = awaitInputs(2)
                setText(inputs.last(), "http://cancelled.invalid")
                click(awaitLabel(context.stringResource(MR.strings.action_cancel)))
                awaitInputs(0)
                assertEquals(0, confirmations.get())
                assertEquals("", preferences.internalAddress)

                click(awaitLabel(context.stringResource(MR.strings.lanraragi_address)))
                click(awaitLabel(context.stringResource(MR.strings.connection_address_advanced)))
                setText(awaitInputs(2).last(), "http://lan.invalid:9797/reader")
                awaitLabel(context.stringResource(MR.strings.connection_internal_address_summary))
                click(awaitLabel(context.stringResource(MR.strings.action_ok)))
                withTimeout(5_000) { while (confirmations.get() != 1) delay(25) }
                val reopened = SmangaPreferences(connectionId)
                assertEquals("https://public.invalid/reader/api/", reopened.address)
                assertEquals("http://lan.invalid:9797/reader/api/", reopened.internalAddress)
                assertEquals(accountKey, reopened.accountKey)
                assertEquals(7L, reopened.mediaId)
                assertEquals("updateTime desc", reopened.order)
            }
        } finally {
            context.deleteSharedPreferences(preferenceName)
        }
    }

    private suspend fun awaitLabel(label: String): AccessibilityNodeInfo = withTimeout(10_000) {
        while (true) {
            nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull { it.text?.toString() == label }
                ?.let { return@withTimeout it }
            delay(100)
        }
        error("Unreachable")
    }

    private suspend fun awaitInputs(count: Int): List<AccessibilityNodeInfo> = withTimeout(10_000) {
        while (true) {
            val inputs = nodes(instrumentation.uiAutomation.rootInActiveWindow).filter { it.isEditable }.toList()
            if (inputs.size == count) return@withTimeout inputs
            delay(100)
        }
        error("Unreachable")
    }

    private fun nodes(root: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (root != null) {
            yield(root)
            for (index in 0 until root.childCount) yieldAll(nodes(root.getChild(index)))
        }
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        assertTrue(
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
            ),
        )
    }
}
