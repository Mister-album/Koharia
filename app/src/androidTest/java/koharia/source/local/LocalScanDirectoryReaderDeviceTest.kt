package koharia.source.local

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalScanDirectoryReaderDeviceTest {
    @Test
    fun safDirectoryUsesOneMetadataQueryAndRejectsIncompleteResults() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val authority = "${InstrumentationRegistry.getInstrumentation().context.packageName}.localscan"
        val control = Uri.parse("content://$authority")
        val root = checkNotNull(UniFile.fromUri(context, DocumentsContract.buildTreeDocumentUri(authority, "root")))
        val resolver = context.contentResolver
        resolver.query(DocumentsContract.buildRootsUri(authority), null, null, null, null)!!.use {
            assertEquals(0, it.count)
        }
        resolver.call(control, "fixture:reset", "ready", null)
        val reader = LocalScanDirectoryReader(context)
        val files = reader.list(root)
        assertEquals(200, files.size)
        assertTrue(files.all { it.sizeBytes == 4_000_000_000L && !it.directory && it.extension == "cbz" })
        assertSame(files, reader.list(root))
        val stats = resolver.call(control, "fixture:stats", null, null)!!
        assertEquals(1, stats.getInt("children"))
        // Only the root's attributes may require document queries, never one query per child.
        assertTrue(stats.getInt("documents") < 10)
        for (mode in listOf("loading", "failure")) {
            resolver.call(control, "fixture:reset", mode, null)
            assertTrue(runCatching { LocalScanDirectoryReader(context).list(root) }.isFailure)
        }
        resolver.call(control, "fixture:reset", "empty", null)
        assertTrue(LocalScanDirectoryReader(context).list(root).isEmpty())
        resolver.call(control, "fixture:reset", "ready", null)
    }
}
