package tachiyomi.macrobenchmark

import android.content.ClipData
import android.content.Intent
import android.os.FileObserver
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/** A separate UID owns the file; only the explicit grant allows the target to inspect it. */
class ExternalBackupNavigationTest {
    @Test
    fun backupRoutesReadOnlyGrantedForeignFilesWithoutRestoring() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        BenchmarkTarget.verify(context)
        val device = UiDevice.getInstance(instrumentation)
        val root = File(context.cacheDir, "reader-profile/${UUID.randomUUID()}")
        check(root.mkdirs())
        try {
            for ((entry, cold) in listOf(
                "eu.kanade.tachiyomi.ui.main.MainActivity",
                "eu.kanade.tachiyomi.ui.deeplink.DeepLinkActivity",
            ).flatMap { entry -> listOf(entry to true, entry to false) }) {
                for (granted in listOf(false, true)) {
                    val file = File.createTempFile("backup-", ".kohariabackup", root)
                    file.writeText("Deliberately invalid, non-sensitive backup input")
                    val uri = FileProvider.getUriForFile(context, "tachiyomi.macrobenchmark.readerfiles", file)
                    val opened = CountDownLatch(1)
                    val observer = object : FileObserver(file, OPEN) {
                        override fun onEvent(event: Int, path: String?) {
                            if (event and OPEN != 0) opened.countDown()
                        }
                    }
                    observer.startWatching()
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setClassName(BenchmarkTarget.PACKAGE, entry)
                            setDataAndType(uri, "application/vnd.koharia.backup")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(if (cold) Intent.FLAG_ACTIVITY_CLEAR_TASK else Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            if (granted) {
                                clipData = ClipData.newRawUri("Backup boundary fixture", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        }
                        context.startActivity(intent)
                        if (granted) {
                            assertTrue(
                                "Granted backup must reach the parser error UI: $entry (cold=$cold)",
                                device.wait(
                                    Until.hasObject(By.text(Pattern.compile("(?s).*(Invalid backup file|备份文件无效).*"))),
                                    15_000,
                                ),
                            )
                            assertTrue("Granted provider was not read", opened.await(5, TimeUnit.SECONDS))
                            device.pressBack()
                        } else {
                            // Without a grant the provider may be invisible, so rejection can precede the preview.
                            val error = device.wait(
                                Until.hasObject(By.text(Pattern.compile("(?s).*(Invalid backup file|备份文件无效).*"))),
                                2_000,
                            )
                            assertFalse("File was read without a grant", opened.await(5, TimeUnit.SECONDS))
                            if (error) device.pressBack()
                        }
                        assertTrue(
                            "Main must remain usable after leaving the backup preview",
                            device.wait(Until.hasObject(By.text(Pattern.compile("History|历史"))), 10_000),
                        )
                    } finally {
                        observer.stopWatching()
                        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        check(file.delete())
                    }
                }
            }
        } finally {
            check(root.canonicalFile.parentFile == File(context.cacheDir, "reader-profile").canonicalFile)
            check(root.delete())
        }
    }
}
