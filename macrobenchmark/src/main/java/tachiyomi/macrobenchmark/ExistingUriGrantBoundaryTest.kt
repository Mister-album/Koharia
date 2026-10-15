package tachiyomi.macrobenchmark

import android.content.Intent
import android.content.pm.PackageManager
import android.os.FileObserver
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The provider grants the target access; a separate, ungranted shell UID then sends the URI. */
class ExistingUriGrantBoundaryTest {
    @Test
    fun mainDoesNotLendAnExistingProviderGrant() =
        assertNoBorrowedGrant("eu.kanade.tachiyomi.ui.main.MainActivity", ".kohariabackup")

    @Test
    fun deepLinkDoesNotLendAnExistingProviderGrant() =
        assertNoBorrowedGrant("eu.kanade.tachiyomi.ui.deeplink.DeepLinkActivity", ".kohariabackup")

    @Test
    fun mediaOpenDoesNotLendAnExistingProviderGrant() =
        assertNoBorrowedGrant("koharia.importing.ExternalMediaOpenActivity", ".txt")

    @Test
    fun mediaImportDoesNotLendAnExistingProviderGrant() =
        assertNoBorrowedGrant("koharia.importing.ExternalMediaImportActivity", ".txt")

    private fun assertNoBorrowedGrant(entry: String, suffix: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        BenchmarkTarget.verify(context)
        val root = File(context.cacheDir, "reader-profile/${UUID.randomUUID()}")
        check(root.mkdirs())
        val file = File.createTempFile("existing-grant-", suffix, root)
        file.writeText("Non-sensitive fixture for existing URI grant isolation")
        val uri = FileProvider.getUriForFile(context, "tachiyomi.macrobenchmark.readerfiles", file)
        val opened = CountDownLatch(1)
        val observer = object : FileObserver(file, OPEN) {
            override fun onEvent(event: Int, path: String?) {
                if (event and OPEN != 0) opened.countDown()
            }
        }
        try {
            context.grantUriPermission(BenchmarkTarget.PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val target = context.packageManager.getApplicationInfo(BenchmarkTarget.PACKAGE, 0)
            assertEquals(
                "Target must already hold the provider grant",
                PackageManager.PERMISSION_GRANTED,
                context.checkUriPermission(uri, -1, target.uid, Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            val denied = shell("content read --uri $uri")
            assertTrue("The sending UID must not be able to read this URI", denied.contains("Permission Denial"))
            observer.startWatching()
            val result = shell(
                "am start -W -f 0x10008000 -n ${BenchmarkTarget.PACKAGE}/$entry " +
                    "-a android.intent.action.VIEW -d $uri",
            )
            assertTrue("The external request was not launched: $result", result.contains("Status: ok"))
            assertFalse(
                "External sender borrowed the target's existing provider grant",
                opened.await(5, TimeUnit.SECONDS),
            )
        } finally {
            observer.stopWatching()
            context.revokeUriPermission(BenchmarkTarget.PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            check(file.canonicalFile.parentFile == root.canonicalFile)
            check(file.delete())
            check(root.delete())
        }
    }

    private fun shell(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
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
