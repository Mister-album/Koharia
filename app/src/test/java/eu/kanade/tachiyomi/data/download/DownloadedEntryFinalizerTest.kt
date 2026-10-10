package eu.kanade.tachiyomi.data.download

import android.net.Uri
import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class DownloadedEntryFinalizerTest {
    @Test
    fun `same parent rename retains the fast path for files and directories`() {
        for (directory in listOf(false, true)) {
            val parent = Entry("root", directory = true)
            val source = parent.add("chapter.tmp", directory)
            val result = if (directory) {
                finalizeDownloadedDirectory(parent.file, source.file, "chapter")
            } else {
                finalizeDownloadedFile(parent.file, source.file, "chapter")
            }
            assertSame(source.file, result)
            assertSame(source, parent.children["chapter"])
            verify(exactly = 0) { source.file.openInputStream() }
            verify(exactly = 0) { source.file.delete() }
        }
    }

    @Test
    fun `directory fallback copies nested children even when their renames succeed`() {
        val parent = Entry("root", directory = true)
        val source = parent.add("chapter.tmp", directory = true).apply { renameAllowed = false }
        val page = source.add("001.jpg", content = "first page")
        val nested = source.add("nested", directory = true)
        nested.add("002.jpg", content = "second page")

        val result = finalizeDownloadedDirectory(parent.file, source.file, "chapter")

        val target = checkNotNull(parent.children["chapter"])
        assertSame(target.file, result)
        assertEquals("first page", target.children["001.jpg"]?.content)
        assertEquals("second page", target.children["nested"]?.children?.get("002.jpg")?.content)
        assertFalse(source.file.exists())
        verify(exactly = 0) { page.file.renameTo(any()) }
        verify(exactly = 0) { nested.file.renameTo(any()) }
    }

    @Test
    fun `cross parent finalization cannot treat an in place rename as a move`() {
        for (directory in listOf(false, true)) {
            val sourceParent = Entry("source", directory = true)
            val targetParent = Entry("target", directory = true)
            val source = sourceParent.add("chapter", directory, "payload")
            if (directory) source.add("001.jpg", content = "payload")
            val result = if (directory) {
                finalizeDownloadedDirectory(targetParent.file, source.file, "chapter")
            } else {
                finalizeDownloadedFile(targetParent.file, source.file, "chapter")
            }
            val target = checkNotNull(targetParent.children["chapter"])
            assertSame(target.file, result)
            assertEquals("payload", if (directory) target.children["001.jpg"]?.content else target.content)
            assertFalse(source.file.exists())
            verify(exactly = 0) { source.file.renameTo(any()) }
        }
    }

    @Test
    fun `failure after copying an earlier child retains every original child`() {
        val parent = Entry("root", directory = true)
        val source = parent.add("chapter.tmp", directory = true).apply { renameAllowed = false }
        source.add("001.jpg", content = "first page")
        source.add("nested", directory = true).add("002.jpg", content = "second page")
        parent.failWrites += "002.jpg"

        assertThrows(IOException::class.java) {
            finalizeDownloadedDirectory(parent.file, source.file, "chapter")
        }

        assertTrue(source.file.exists())
        assertEquals("first page", source.children["001.jpg"]?.content)
        assertEquals("second page", source.children["nested"]?.children?.get("002.jpg")?.content)
        assertFalse(parent.children.containsKey("chapter"))
        verify(exactly = 0) { source.file.delete() }
    }

    @Test
    fun `failed file copy retains its temporary input`() {
        val parent = Entry("root", directory = true)
        val source = parent.add("chapter.tmp", content = "archive").apply { renameAllowed = false }
        parent.failWrites += "chapter.cbz"
        assertThrows(IOException::class.java) {
            finalizeDownloadedFile(parent.file, source.file, "chapter.cbz")
        }
        assertTrue(source.file.exists())
        assertEquals("archive", source.content)
        assertFalse(parent.children.containsKey("chapter.cbz"))
    }

    @Test
    fun `unreadable directory cannot be finalized as an empty download`() {
        val parent = Entry("root", directory = true)
        val source = parent.add("chapter.tmp", directory = true).apply {
            renameAllowed = false
            readable = false
        }
        source.add("001.jpg", content = "page")
        assertThrows(IOException::class.java) {
            finalizeDownloadedDirectory(parent.file, source.file, "chapter")
        }
        assertTrue(source.file.exists())
        assertFalse(parent.children.containsKey("chapter"))
    }

    @Test
    fun `source cleanup failure retains a fully copied target`() {
        for (directory in listOf(false, true)) {
            val parent = Entry("root", directory = true)
            val source = parent.add("chapter.tmp", directory, "archive").apply {
                renameAllowed = false
                deleteAllowed = false
            }
            if (directory) source.add("001.jpg", content = "page")
            assertThrows(IOException::class.java) {
                if (directory) {
                    finalizeDownloadedDirectory(parent.file, source.file, "chapter")
                } else {
                    finalizeDownloadedFile(parent.file, source.file, "chapter")
                }
            }
            val target = checkNotNull(parent.children["chapter"])
            assertEquals(
                if (directory) "page" else "archive",
                if (directory) target.children["001.jpg"]?.content else target.content,
            )
            assertTrue(source.file.exists())
        }
    }

    private class Entry(
        var name: String,
        val directory: Boolean = false,
        var content: String = "",
        private val parent: Entry? = null,
        val failWrites: MutableSet<String> = parent?.failWrites ?: mutableSetOf(),
    ) {
        val file = mockk<UniFile>()
        val children = linkedMapOf<String, Entry>()
        var renameAllowed = true
        var deleteAllowed = true
        var readable = true
        private var deleted = false

        init {
            every { file.uri } returns mockk<Uri>()
            every { file.parentFile } answers { parent?.file }
            every { file.name } answers { name }
            every { file.isDirectory } returns directory
            every { file.exists() } answers { !deleted }
            every { file.listFiles() } answers
                { if (readable) children.values.map { it.file }.toTypedArray() else null }
            every { file.findFile(any()) } answers { children[firstArg<String>()]?.file }
            every { file.createFile(any()) } answers { add(firstArg()).file }
            every { file.createDirectory(any()) } answers { add(firstArg(), directory = true).file }
            every { file.openInputStream() } answers { ByteArrayInputStream(content.toByteArray()) }
            every { file.openOutputStream() } answers {
                object : ByteArrayOutputStream() {
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        if (name in failWrites) throw IOException("Fixture write failed")
                        super.write(buffer, offset, length)
                    }
                    override fun close() {
                        content = toString(Charsets.UTF_8.name())
                        super.close()
                    }
                }
            }
            every { file.renameTo(any()) } answers {
                if (!renameAllowed) {
                    false
                } else {
                    parent?.children?.remove(name)
                    name = firstArg()
                    parent?.children?.put(name, this@Entry)
                    true
                }
            }
            every { file.delete() } answers {
                if (!deleteAllowed) {
                    false
                } else {
                    children.values.toList().forEach { it.file.delete() }
                    deleted = true
                    parent?.children?.remove(name)
                    true
                }
            }
        }

        fun add(name: String, directory: Boolean = false, content: String = ""): Entry =
            children.getOrPut(name) { Entry(name, directory, content, this) }
    }
}
