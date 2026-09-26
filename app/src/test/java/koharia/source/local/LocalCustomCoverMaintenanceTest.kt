package koharia.source.local

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class LocalCustomCoverMaintenanceTest {
    @TempDir
    lateinit var directory: Path

    private val root = LocalLibraryRootConfig(id = "root", treeUri = "file:///books")
    private val config = LocalLibraryConfig(roots = listOf(root), setupCompleted = true)
    private val url = LocalLibraryLocator.entryUrl(42, root.id, "Book.epub")

    @Test
    fun `only confirmed absence permits cleanup`() {
        assertTrue(isRemovedLocalCover(42, url, config) { _, _ -> false })
        assertFalse(isRemovedLocalCover(42, url, config) { _, _ -> true })
        assertFalse(isRemovedLocalCover(42, url, config) { _, _ -> null })
    }

    @Test
    fun `detached root is removable but restored root is protected`() {
        val removed = config.withoutRoot(root.id)
        assertTrue(isRemovedLocalCover(42, url, removed) { _, _ -> error("Must not access a detached directory") })
        assertFalse(isRemovedLocalCover(42, url, config) { _, _ -> true })
    }

    @Test
    fun `uninitialized configuration invalid locator and other connections are protected`() {
        assertFalse(isRemovedLocalCover(42, url, LocalLibraryConfig()) { _, _ -> false })
        assertFalse(isRemovedLocalCover(43, url, config) { _, _ -> false })
        assertFalse(isRemovedLocalCover(42, "/unrecognized", config) { _, _ -> false })
    }

    @Test
    fun `file probing distinguishes missing files from unavailable directories`() {
        val context = mockk<Context>()
        val uri = mockk<Uri>()
        every { uri.scheme } returns "file"
        every { uri.path } returns directory.toString()
        val folder = mockk<UniFile>()
        every { folder.uri } returns uri
        every { folder.isDirectory } returns true
        every { folder.canRead() } returns true
        directory.resolve("book.epub").writeText("book")
        every { folder.findFile("book.epub") } returns mockk()

        assertEquals(true, localCoverEntryExists(context, folder, "book.epub"))
        assertEquals(false, localCoverEntryExists(context, folder, "missing.epub"))
        every { folder.canRead() } returns false
        assertNull(localCoverEntryExists(context, folder, "missing.epub"))
        every { folder.canRead() } returns true
        every { uri.path } returns directory.resolve("unmounted").toString()
        assertNull(localCoverEntryExists(context, folder, "missing.epub"))
    }
}
