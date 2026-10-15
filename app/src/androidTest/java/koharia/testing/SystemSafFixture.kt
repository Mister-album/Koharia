package koharia.testing

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.FileInputStream

/** A new external-storage tree, granted through the normal system picker and removed after use. */
internal object SystemSafFixture {
    fun withTree(block: suspend (UniFile) -> Unit): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val name = "Koharia-SAF-${System.nanoTime()}"
        val path = "/sdcard/Documents/$name"
        val tree = DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Documents/$name",
        )
        val resolver = context.contentResolver
        fun grants() = resolver.persistedUriPermissions.map {
            Triple(it.uri, it.isReadPermission, it.isWritePermission)
        }
            .toSet()
        val priorGrants = grants()
        check(priorGrants.none { it.first == tree })
        instrumentation.sendStatus(2, Bundle().apply { putString("saf_fixture_tree", tree.toString()) })
        fun shell(
            command: String,
        ) = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes().decodeToString().trim() }
        }
        check(shell("ls -d $path") != path)
        check(shell("mkdir $path").isEmpty())
        check(shell("ls -d $path") == path)
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        var directory: UniFile? = null
        var granted = false
        try {
            FixtureActivityLauncher.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            putExtra(
                                DocumentsContract.EXTRA_INITIAL_URI,
                                DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Documents/$name"),
                            )
                        },
                        9127,
                    )
                }
                withTimeout(30_000) {
                    while (true) {
                        val root = automation.rootInActiveWindow
                        val selectedFolder = find(root) { it.text?.toString() == name }
                        val select = find(root) { it.viewIdResourceName?.endsWith(":id/action_menu_select") == true }
                            ?: find(
                                find(root) { it.viewIdResourceName?.endsWith(":id/container_save") == true },
                            ) { it.viewIdResourceName == "android:id/button1" }
                        if (root?.packageName?.contains("documentsui") == true && selectedFolder != null &&
                            select?.isEnabled == true
                        ) {
                            check(select.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                            break
                        }
                        delay(100)
                    }
                }
                withTimeout(30_000) {
                    while (true) {
                        if (runCatching { resolver.takePersistableUriPermission(tree, flags) }.isSuccess) break
                        val root = automation.rootInActiveWindow
                        if (root?.packageName?.contains("documentsui") == true &&
                            find(root) { it.text?.contains(name) == true } != null
                        ) {
                            find(root) { it.viewIdResourceName == "android:id/button1" && it.isEnabled }
                                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        delay(100)
                    }
                }
                granted = true
                val grantedDirectory = checkNotNull(UniFile.fromUri(context, tree))
                directory = grantedDirectory
                assertEquals(name, grantedDirectory.name)
                assertTrue(grantedDirectory.isDirectory)
                assertEquals(priorGrants, grants().filterNot { it.first == tree }.toSet())
                block(grantedDirectory)
            }
        } finally {
            try {
                val owned = directory
                if (owned != null) {
                    check(DocumentsContract.getTreeDocumentId(owned.uri) == "primary:Documents/$name")
                    check(DocumentsContract.getDocumentId(owned.uri) == "primary:Documents/$name")
                    check(owned.delete())
                } else {
                    // Only an empty, newly-created directory is eligible for this fallback.
                    shell("rmdir $path")
                }
            } finally {
                if (granted && grants().any { it.first == tree }) {
                    resolver.releasePersistableUriPermission(tree, flags)
                }
                automation.serviceInfo = automation.serviceInfo.apply { this.flags = previousFlags }
                assertEquals(priorGrants, grants())
            }
        }
    }

    private fun find(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        repeat(node.childCount) { index -> find(node.getChild(index), predicate)?.let { return it } }
        return null
    }
}
