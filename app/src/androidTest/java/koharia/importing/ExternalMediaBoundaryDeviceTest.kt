package koharia.importing

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
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
class ExternalMediaBoundaryDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun externalImportRejectsPrivateFileBeforeDisplayingImportConfiguration() {
        check(context.packageName == "app.koharia.dev.devicefixture")
        val canary = File.createTempFile("external-boundary-", ".txt", context.filesDir)
        try {
            canary.writeText("Non-sensitive private file fixture")
            assertRejected("koharia.importing.ExternalMediaImportActivity", canary.toURI().toString())
        } finally {
            check(canary.canonicalFile.parentFile == context.filesDir.canonicalFile)
            check(canary.delete())
        }
    }

    @Test
    fun bothMediaEntrypointsRejectPrivateFilesAndOwnProviderAliases() {
        check(context.packageName == "app.koharia.dev.devicefixture")
        val canary = File.createTempFile("external-boundary-", ".txt", context.cacheDir)
        try {
            canary.writeText("Non-sensitive private cache fixture")
            val uris = listOf(
                canary.toURI().toString(),
                "content://${context.packageName}.provider/cache_files/${canary.name}",
                "content://0@${context.packageName}.provider/cache_files/${canary.name}",
            )
            for (activity in listOf(ExternalMediaOpenActivity::class.java, ExternalMediaImportActivity::class.java)) {
                for (uri in uris) assertRejected(activity.name, uri)
            }
        } finally {
            check(canary.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(canary.delete())
        }
    }

    @Test
    fun temporaryImportRequiresInternalActivityAndPreservesReaderImportFlow() {
        check(context.packageName == "app.koharia.dev.devicefixture")
        val root = IncomingMediaSessionLocator.cacheRoot(context).apply { mkdirs() }
        val canary = File.createTempFile("external-boundary-", ".txt", root)
        try {
            canary.writeText("Non-sensitive temporary reading fixture")
            val uri = canary.toURI().toString()
            for (activity in listOf(ExternalMediaOpenActivity::class.java, ExternalMediaImportActivity::class.java)) {
                assertRejected(activity.name, uri, temporaryAction = true)
            }
            val internal = IncomingMediaNavigation.importIntent(context, uri)
            val component = checkNotNull(internal.component)
            assertFalse(context.packageManager.getActivityInfo(component, 0).exported)
            assertTrue(shell("am start -W -n ${component.flattenToString()}").contains("not exported"))
            ActivityScenario.launch<TemporaryMediaImportActivity>(internal).use { scenario ->
                scenario.onActivity { activity ->
                    assertFalse(activity.isFinishing)
                    assertEquals(uri, IncomingMediaNavigation.validatedImportUri(activity, activity.intent))
                }
            }
        } finally {
            check(canary.canonicalFile.parentFile == root.canonicalFile)
            check(canary.delete())
        }
    }

    @Test
    fun sharedFilesAndForeignProviderRemainUsableAcrossShareForms() {
        check(context.packageName == "app.koharia.dev.devicefixture")
        val publicUri = Uri.fromFile(File(Environment.getExternalStorageDirectory(), "Download/external-fixture.cbz"))
        val foreignUri = Uri.parse("content://${instrumentation.context.packageName}.localscan/document/root")
        for (uri in listOf(publicUri, foreignUri)) {
            for (intent in listOf(
                Intent(Intent.ACTION_VIEW, uri),
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri),
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri)),
                Intent(Intent.ACTION_SEND).apply { clipData = ClipData.newRawUri("fixture", uri) },
            )) {
                assertEquals(listOf(uri.toString()), IncomingMediaNavigation.mediaUriValues(context, intent))
            }
        }
        val invalid = Uri.fromFile(File(context.filesDir, "external-boundary-private.txt"))
        val mixed = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, publicUri).apply {
            clipData = ClipData.newRawUri("fixture", invalid)
        }
        assertTrue(IncomingMediaNavigation.mediaUriValues(context, mixed).isEmpty())
        val unknownAction = Intent("fixture.UNKNOWN").apply { clipData = ClipData.newRawUri("fixture", publicUri) }
        assertTrue(IncomingMediaNavigation.mediaUriValues(context, unknownAction).isEmpty())
    }

    private fun assertRejected(activityName: String, uri: String, temporaryAction: Boolean = false) {
        val destroyed = CountDownLatch(1)
        val launched = AtomicReference<Activity>()
        val receivedUri = AtomicReference<String>()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity.javaClass.name == activityName) {
                if (stage == Stage.CREATED) {
                    launched.set(activity)
                    receivedUri.set(
                        if (temporaryAction) {
                            activity.intent.getStringExtra(IncomingMediaNavigation.EXTRA_TEMPORARY_MEDIA_URI)
                        } else {
                            activity.intent.dataString
                        },
                    )
                }
                if (stage == Stage.DESTROYED && activity === launched.get() && activity.isFinishing) {
                    destroyed.countDown()
                }
            }
        }
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        monitor.addLifecycleCallback(callback)
        try {
            // Shell is a different UID; no URI permission or app identity is adopted.
            check(uri.none { it.isWhitespace() || it in "'\"" })
            val arguments = if (temporaryAction) {
                "-a ${IncomingMediaNavigation.ACTION_IMPORT_TEMPORARY_MEDIA} " +
                    "--es ${IncomingMediaNavigation.EXTRA_TEMPORARY_MEDIA_URI} $uri"
            } else {
                "-a android.intent.action.VIEW -d $uri"
            }
            val result = shell("am start -W -n ${context.packageName}/$activityName $arguments")
            assertTrue("External launch was not executed", result.contains("Status: ok"))
            assertTrue("Untrusted private URI reached the media UI", destroyed.await(10, TimeUnit.SECONDS))
            assertEquals("The external request must deliver the exact canary URI", uri, receivedUri.get())
        } finally {
            instrumentation.runOnMainSync { launched.get()?.let { if (!it.isDestroyed) it.finish() } }
            monitor.removeLifecycleCallback(callback)
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
}
