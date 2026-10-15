package koharia.diagnostics

import android.app.Activity
import android.os.FileObserver
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.core.util.Consumer
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@SdkSuppress(minSdkVersion = 34)
class ExternalBackupBoundaryDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun mainRejectsPrivateBackupBeforeReadingIt() = assertPrivateBackupRejected(MAIN)

    @Test
    fun deepLinkRejectsPrivateBackupBeforeReadingIt() =
        assertPrivateBackupRejected("eu.kanade.tachiyomi.ui.deeplink.DeepLinkActivity")

    @Test
    fun bothRoutesRejectOwnProviderIncludingUserQualifiedAuthority() {
        for (entry in listOf(MAIN, "eu.kanade.tachiyomi.ui.deeplink.DeepLinkActivity")) {
            for (prefix in listOf("", "0@")) {
                assertPrivateBackupRejected(entry, providerPrefix = prefix)
            }
        }
    }

    @Test
    fun resumedMainRejectsPrivateBackupOnNewIntent() = assertPrivateBackupRejected(MAIN, warm = true)

    private fun assertPrivateBackupRejected(entry: String, providerPrefix: String? = null, warm: Boolean = false) {
        check(context.packageName == "app.koharia.dev.devicefixture")
        val canary = File.createTempFile("external-backup-boundary-", ".kohariabackup", context.cacheDir)
        canary.writeText("Non-sensitive private backup boundary fixture")
        val opened = CountDownLatch(1)
        val observer = object : FileObserver(canary, OPEN) {
            override fun onEvent(event: Int, path: String?) {
                if (event and OPEN != 0) opened.countDown()
            }
        }
        val resumed = CountDownLatch(1)
        val activity = AtomicReference<Activity>()
        val received = AtomicReference<String>()
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { current, stage ->
            if (current.javaClass.name == MAIN && stage == Stage.RESUMED && activity.get() == null) {
                activity.set(current)
                received.set(current.intent.dataString)
                resumed.countDown()
            }
        }
        monitor.addLifecycleCallback(callback)
        observer.startWatching()
        try {
            val uri = if (providerPrefix == null) {
                canary.toURI().toString()
            } else {
                "content://$providerPrefix${context.packageName}.provider/cache_files/${canary.name}"
            }
            check(uri.none { it.isWhitespace() || it in "'\"" })
            val newIntent = CountDownLatch(1)
            if (warm) {
                assertTrue(
                    shell("am start -W -f 0x10008000 -n ${context.packageName}/$MAIN -a android.intent.action.MAIN")
                        .contains("Status: ok"),
                )
                assertTrue("Initial Main was not resumed", resumed.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    (activity.get() as ComponentActivity).addOnNewIntentListener(
                        Consumer { intent ->
                            received.set(intent.dataString)
                            newIntent.countDown()
                        },
                    )
                }
            }
            val flags = if (warm) "0x20000000" else "0x10008000"
            val result = shell(
                "am start -W -f $flags " +
                    "-n ${context.packageName}/$entry -a android.intent.action.VIEW -d $uri",
            )
            assertTrue(result, result.contains("Status: ok"))
            assertTrue("Main was not resumed", resumed.await(10, TimeUnit.SECONDS))
            if (warm) assertTrue("Existing Main must receive onNewIntent", newIntent.await(10, TimeUnit.SECONDS))
            assertEquals("Exact external URI must reach Main", uri, received.get())
            assertFalse("External intent opened an app-private backup", opened.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertTrue("Main must remain usable", activity.get().hasWindowFocus())
                assertFalse(activity.get().isFinishing)
            }
        } finally {
            observer.stopWatching()
            instrumentation.runOnMainSync { activity.get()?.finish() }
            monitor.removeLifecycleCallback(callback)
            check(canary.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(canary.delete())
        }
    }

    private fun shell(command: String): String {
        val descriptors = instrumentation.uiAutomation.executeShellCommandRwe(command)
        try {
            descriptors[1].close()
            return listOf(descriptors[0], descriptors[2]).joinToString("\n") { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            }
        } finally {
            descriptors.forEach { runCatching { it.close() } }
        }
    }

    private companion object {
        const val MAIN = "eu.kanade.tachiyomi.ui.main.MainActivity"
    }
}
