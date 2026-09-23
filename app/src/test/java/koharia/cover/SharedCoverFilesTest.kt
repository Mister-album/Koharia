package koharia.cover

import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class SharedCoverFilesTest {
    @Test
    fun `identity survives database restore and separates sources and URLs`() {
        val original = SharedCoverFiles.key(42, "/series/one")
        assertEquals(original, SharedCoverFiles.key(42, "/series/one"))
        assertEquals(64, original.length)
        assertNotEquals(original, SharedCoverFiles.key(43, "/series/one"))
        assertNotEquals(original, SharedCoverFiles.key(42, "/series/two"))
        assertNotEquals(SharedCoverFiles.key(1, "23"), SharedCoverFiles.key(12, "3"))
    }

    @Test
    fun `replacement stays staged until validation succeeds`() {
        val directory = InMemoryCoverDirectory()
        directory.put("cover.img", "old")
        val files = SharedCoverFiles(directory.directory) { pending ->
            assertEquals("old", directory.read("cover.img"))
            assertEquals("new", pending.openInputStream().use { it.readBytes().decodeToString() })
            true
        }

        files.write("cover", "new".byteInputStream())

        assertEquals("new", directory.read("cover.img"))
        assertEquals(setOf("cover.img"), directory.names())
    }

    @Test
    fun `interrupted source retains original and removes staging`() {
        val directory = InMemoryCoverDirectory()
        directory.put("cover.img", "old")
        val files = SharedCoverFiles(directory.directory) { true }
        val interrupted = object : InputStream() {
            private var delivered = false

            override fun read(): Int = throw IOException("Interrupted source")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (delivered) throw IOException("Interrupted source")
                delivered = true
                buffer[offset] = 1
                return 1
            }
        }

        assertThrows(IOException::class.java) { files.write("cover", interrupted) }

        assertEquals("old", directory.read("cover.img"))
        assertEquals(setOf("cover.img"), directory.names())
    }

    @Test
    fun `corrupt provider readback retains original`() {
        val directory = InMemoryCoverDirectory()
        directory.put("cover.img", "old")
        directory.corruptRead = { it.endsWith(".pending") }
        val files = SharedCoverFiles(directory.directory) { true }

        assertThrows(IOException::class.java) { files.write("cover", "new".byteInputStream()) }

        assertEquals("old", directory.read("cover.img"))
        assertEquals(setOf("cover.img"), directory.names())
    }

    @Test
    fun `invalid image retains original`() {
        val directory = InMemoryCoverDirectory()
        directory.put("cover.img", "old")
        val files = SharedCoverFiles(directory.directory) { false }

        assertThrows(IOException::class.java) { files.write("cover", "invalid".byteInputStream()) }

        assertEquals("old", directory.read("cover.img"))
    }

    @Test
    fun `failed publication and rollback keep recoverable original for next write`() {
        val directory = InMemoryCoverDirectory()
        directory.put("cover.img", "old")
        directory.failRename = { _, destination -> destination == "cover.img" }
        val files = SharedCoverFiles(directory.directory) { true }

        assertThrows(IOException::class.java) { files.write("cover", "new".byteInputStream()) }
        assertEquals("old", files.find("cover")!!.openInputStream().use { it.readBytes().decodeToString() })
        assertEquals(setOf("cover.previous"), directory.names())

        directory.failRename = { _, _ -> false }
        files.write("cover", "retry".byteInputStream())

        assertEquals("retry", directory.read("cover.img"))
        assertEquals(setOf("cover.img"), directory.names())
    }

    @Test
    fun `deletion removes recovery and staging without resurrection`() {
        val directory = InMemoryCoverDirectory()
        directory.put("cover.img", "current")
        directory.put("cover.previous", "old")
        directory.put("cover.pending", "partial")
        val files = SharedCoverFiles(directory.directory) { true }

        files.delete("cover")

        assertNull(files.find("cover"))
        assertEquals(emptySet<String>(), directory.names())
        files.write("cover", "next".byteInputStream())
        assertEquals("next", directory.read("cover.img"))
        assertNull(directory.read("cover.previous"))
    }

    @Test
    fun `failed old-image cleanup preserves published image across next write`() {
        val directory = InMemoryCoverDirectory()
        directory.put("cover.img", "old")
        directory.failDelete = { it.endsWith(".previous") }
        val files = SharedCoverFiles(directory.directory) { true }

        files.write("cover", "published".byteInputStream())

        assertEquals("published", files.find("cover")!!.openInputStream().use { it.readBytes().decodeToString() })
        assertEquals("old", directory.read("cover.previous"))
        assertThrows(IOException::class.java) { files.write("cover", "retry".byteInputStream()) }
        assertEquals("published", directory.read("cover.img"))

        directory.failDelete = { false }
        files.write("cover", "retry".byteInputStream())
        assertEquals("retry", directory.read("cover.img"))
        assertEquals(setOf("cover.img"), directory.names())
    }

    @Test
    fun `failed recovery deletion leaves current image authoritative`() {
        val directory = InMemoryCoverDirectory()
        directory.put("cover.img", "current")
        directory.put("cover.previous", "old")
        directory.failDelete = { it.endsWith(".previous") }
        val files = SharedCoverFiles(directory.directory) { true }

        assertThrows(IOException::class.java) { files.delete("cover") }

        assertEquals("current", files.find("cover")!!.openInputStream().use { it.readBytes().decodeToString() })
        directory.failDelete = { false }
        files.delete("cover")
        assertNull(files.find("cover"))
    }
}

internal class InMemoryCoverDirectory {
    val directory = mockk<UniFile>()
    var corruptRead: (String) -> Boolean = { false }
    var failRename: (String, String) -> Boolean = { _, _ -> false }
    var failDelete: (String) -> Boolean = { false }
    private val entries = mutableMapOf<String, Entry>()

    init {
        every { directory.findFile(any()) } answers { entries[firstArg<String>()]?.file }
        every { directory.createFile(any()) } answers {
            val name = firstArg<String>()
            entries.getOrPut(name) { Entry(name) }.file
        }
    }

    fun put(name: String, contents: String) {
        entries[name] = Entry(name).apply { bytes = contents.toByteArray() }
    }

    fun read(name: String): String? = entries[name]?.bytes?.decodeToString()

    fun readBytes(name: String): ByteArray? = entries[name]?.bytes

    fun names(): Set<String> = entries.keys.toSet()

    private inner class Entry(var name: String) {
        var bytes = byteArrayOf()
        val file = mockk<UniFile>()

        init {
            every { file.openInputStream() } answers {
                if (corruptRead(name)) "corrupted".byteInputStream() else bytes.inputStream()
            }
            every { file.openOutputStream() } answers {
                object : ByteArrayOutputStream() {
                    override fun close() {
                        bytes = toByteArray()
                        super.close()
                    }
                }
            }
            every { file.renameTo(any()) } answers {
                val destination = firstArg<String>()
                if (failRename(name, destination) || entries.containsKey(destination)) {
                    false
                } else {
                    entries.remove(name)
                    name = destination
                    entries[name] = this@Entry
                    true
                }
            }
            every { file.delete() } answers {
                if (failDelete(name)) false else entries.remove(name) != null
            }
        }
    }
}
