package koharia.smanga

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SmangaPageManifestTest {
    @Test
    fun `numeric display order maps to full path lexical OPDS pages`() {
        val manifest = SmangaPageManifest.create(4, listOf("/a/1.jpg", "/a/2.jpg", "/a/10.jpg"))
        assertEquals(listOf(1, 3, 2), manifest.pages.map { it.opdsPage })
        assertEquals(listOf(0, 1, 2), manifest.pages.map { it.displayIndex })
        assertEquals(3, manifest.pageCount)
        assertFalse(Json.encodeToString(manifest).contains("/a/"))
    }

    @Test
    fun `nested duplicate basenames retain distinct full path indices`() {
        val manifest = SmangaPageManifest.create(1, listOf("/b/1.jpg", "/a/1.jpg", "/a/2.jpg"))
        assertEquals(listOf(3, 1, 2), manifest.pages.map { it.opdsPage })
    }

    @Test
    fun `version changes on display order and image identity but is deterministic`() {
        val first = SmangaPageManifest.create(1, listOf("a", "b"))
        assertEquals(first, SmangaPageManifest.create(1, listOf("a", "b")))
        assertNotEquals(first.version, SmangaPageManifest.create(1, listOf("b", "a")).version)
        assertNotEquals(first.version, SmangaPageManifest.create(1, listOf("a", "c")).version)
        assertNotEquals(first.version, SmangaPageManifest.create(2, listOf("a", "b")).version)
    }

    @Test
    fun `empty and duplicate image lists cannot produce readable manifests`() {
        assertThrows(SmangaException::class.java) { SmangaPageManifest.create(1, emptyList()) }
        assertThrows(SmangaException::class.java) { SmangaPageManifest.create(1, listOf("a", "a")) }
    }
}
