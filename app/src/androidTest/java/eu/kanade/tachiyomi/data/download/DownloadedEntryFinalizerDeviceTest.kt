package eu.kanade.tachiyomi.data.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DownloadedEntryFinalizerDeviceTest {
    @Test
    fun crossParentFileIsActuallyCopiedToDestination() = withRoot { root ->
        val source = File(root, "source/chapter.cbz").apply {
            parentFile!!.mkdirs()
            writeText("archive payload")
        }
        val destination = File(root, "destination").apply { mkdirs() }
        val result = finalizeDownloadedFile(uni(destination), uni(source), source.name)
        assertEquals(uni(destination).uri, result.parentFile!!.uri)
        assertEquals("archive payload", File(destination, "chapter.cbz").readText())
        assertFalse(source.exists())
    }

    @Test
    fun crossParentDirectoryCopiesEveryNestedFile() = withRoot { root ->
        val source = File(root, "source/chapter").apply { mkdirs() }
        File(source, "001.jpg").writeText("first page")
        File(source, "nested/002.jpg").apply {
            parentFile!!.mkdirs()
            writeText("second page")
        }
        val destination = File(root, "destination").apply { mkdirs() }
        val result = finalizeDownloadedDirectory(uni(destination), uni(source), source.name)
        assertEquals(uni(destination).uri, result.parentFile!!.uri)
        assertEquals("first page", File(destination, "chapter/001.jpg").readText())
        assertEquals("second page", File(destination, "chapter/nested/002.jpg").readText())
        assertFalse(source.exists())
    }

    @Test
    fun sameParentFileStillRenamesInPlace() = withRoot { root ->
        val parent = uni(root)
        val source = checkNotNull(parent.createFile("chapter.tmp"))
        source.openOutputStream().use { it.write("archive payload".toByteArray()) }
        val result = finalizeDownloadedFile(parent, source, "chapter.cbz")
        assertEquals(parent.uri, result.parentFile!!.uri)
        assertEquals("archive payload", File(root, "chapter.cbz").readText())
        assertFalse(File(root, "chapter.tmp").exists())
    }

    private fun withRoot(verify: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val root = File(context.cacheDir, "download-finalizer-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        try {
            verify(root)
        } finally {
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            assertTrue(root.deleteRecursively())
        }
    }

    private fun uni(file: File): UniFile = checkNotNull(UniFile.fromFile(file))
}
