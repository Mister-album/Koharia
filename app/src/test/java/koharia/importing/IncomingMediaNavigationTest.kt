package koharia.importing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class IncomingMediaNavigationTest {

    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `temporary import accepts only existing files inside incoming cache`() {
        val cacheRoot = File(temporaryDirectory, "incoming-media").apply { mkdirs() }
        val session = File(cacheRoot, "session").apply { mkdirs() }
        val media = File(session, "book.epub").apply { createNewFile() }
        val outside = File(temporaryDirectory, "outside.epub").apply { createNewFile() }

        assertEquals(
            media.toURI().toString(),
            validatedTemporaryMediaUri(cacheRoot, media.toURI().toString()),
        )
        assertNull(validatedTemporaryMediaUri(cacheRoot, outside.toURI().toString()))
        assertNull(validatedTemporaryMediaUri(cacheRoot, "content://other/book.epub"))
        assertNull(validatedTemporaryMediaUri(cacheRoot, File(session, "missing.epub").toURI().toString()))
    }
}
