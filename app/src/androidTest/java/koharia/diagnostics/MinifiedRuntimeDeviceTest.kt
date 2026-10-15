package koharia.diagnostics

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Cross-APK tests must use platform entry points, not methods R8 can inline inside the app. */
class MinifiedRuntimeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun requireIsolatedMinifiedTarget() {
        check(context.packageName == "app.koharia.dev.devicefixture")
        assumeTrue(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
    }

    @Test
    fun launcherStartsAndExposesAnAccessibleWindow() {
        var activity: Activity? = null
        try {
            activity = instrumentation.startActivitySync(
                Intent(Intent.ACTION_MAIN)
                    .setClassName(context.packageName, "eu.kanade.tachiyomi.ui.main.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            val deadline = SystemClock.uptimeMillis() + 20_000
            var visible = false
            while (!visible && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    visible = activity.hasWindowFocus() && activity.window.decorView.width > 0 &&
                        activity.window.decorView.height > 0 && !activity.isFinishing
                }
                if (!visible) SystemClock.sleep(50)
            }
            assertTrue("Main activity did not display a window", visible)
            var root = instrumentation.uiAutomation.rootInActiveWindow
            while ((root?.packageName?.toString() != context.packageName || root?.childCount == 0) &&
                SystemClock.uptimeMillis() < deadline
            ) {
                SystemClock.sleep(50)
                root = instrumentation.uiAutomation.rootInActiveWindow
            }
            assertEquals(context.packageName, root?.packageName?.toString())
            assertTrue("Main activity has no accessible content", checkNotNull(root).childCount > 0)
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
        }
    }

    @Test
    fun fileProviderRoundTripKeepsTheDeclaredCacheBoundary() {
        val file = File.createTempFile("minified-provider-", ".txt", context.cacheDir)
        try {
            val expected = "Koharia minified provider fixture".toByteArray()
            file.writeBytes(expected)
            val uri = cacheUri(file.name)
            assertArrayEquals(expected, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            val provider = context.packageManager.resolveContentProvider("${context.packageName}.provider", 0)
            assertFalse(checkNotNull(provider).exported)
            assertTrue(provider.grantUriPermissions)
        } finally {
            check(file.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(file.delete())
        }
    }

    @Test
    fun externalComicBookAndPdfIntentsRetainBothExplicitRoutes() {
        for (mime in listOf("application/vnd.comicbook+zip", "application/epub+zip", "application/pdf")) {
            val intent = Intent(Intent.ACTION_VIEW)
                .setPackage(context.packageName)
                .setDataAndType(cacheUri("minified-route-fixture"), mime)
                .addCategory(Intent.CATEGORY_DEFAULT)
            val activities = context.packageManager.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.exported }
                .map { it.activityInfo.name }
            assertTrue("Missing open route for $mime", "koharia.importing.ExternalMediaOpenActivity" in activities)
            assertTrue("Missing import route for $mime", "koharia.importing.ExternalMediaImportActivity" in activities)
        }
    }

    private fun cacheUri(name: String): Uri = Uri.Builder()
        .scheme("content")
        .authority("${context.packageName}.provider")
        .appendPath("cache_files")
        .appendPath(name)
        .build()
}
