package koharia.importing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `external file boundary uses canonical shared roots and excludes private directories`() {
        val shared = File(temporaryDirectory, "shared").apply { mkdirs() }
        val private = File(shared, "Android/data/app").apply { mkdirs() }
        val permitted = File(shared, "Downloads/book.cbz")
        assertTrue(isExternalMediaFile(permitted, listOf(shared), listOf(private)))
        assertFalse(isExternalMediaFile(File(private, "private.txt"), listOf(shared), listOf(private)))
        assertFalse(isExternalMediaFile(File(shared, "../secret.txt"), listOf(shared), listOf(private)))
        assertFalse(
            isExternalMediaFile(File(temporaryDirectory, "shared-other/book.txt"), listOf(shared), listOf(private)),
        )
        assertFalse(
            isExternalMediaFile(
                File(shared, "Downloads/../Android/data/app/private.txt"),
                listOf(shared),
                listOf(private),
            ),
        )
    }
}
