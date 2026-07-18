package koharia.source.komga

import koharia.komga.api.dto.LibraryDto
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class KomgaLibraryClassificationManagerTest {

    @Test
    fun `new libraries default to comics and labels survive serialization cycle`() {
        val fixture = fixture()

        fixture.manager.updateLibraries(
            serverId = 1L,
            libraries = listOf(LibraryDto("library-1", "Library one")),
        )
        assertEquals(KomgaLibraryKind.COMIC, fixture.manager.getLibraries(1L).single().kind)
        assertTrue(fixture.manager.libraryIds(1L, KomgaLibraryKind.BOOK).isEmpty())

        fixture.manager.setKind(1L, "library-1", KomgaLibraryKind.BOOK)

        assertEquals(KomgaLibraryKind.BOOK, fixture.manager.getLibraries(1L).single().kind)
    }

    @Test
    fun `library labels are isolated by server`() {
        val fixture = fixture()
        fixture.manager.updateLibraries(1L, listOf(LibraryDto("shared-id", "First server")))
        fixture.manager.updateLibraries(2L, listOf(LibraryDto("shared-id", "Second server")))

        fixture.manager.setKind(1L, "shared-id", KomgaLibraryKind.BOOK)

        assertEquals(KomgaLibraryKind.BOOK, fixture.manager.getLibraries(1L).single().kind)
        assertEquals(KomgaLibraryKind.COMIC, fixture.manager.getLibraries(2L).single().kind)

        fixture.manager.clearServer(1L)

        assertTrue(fixture.manager.getLibraries(1L).isEmpty())
        assertEquals(KomgaLibraryKind.COMIC, fixture.manager.getLibraries(2L).single().kind)
    }

    @Test
    fun `classification mode switches local configuration atomically`() {
        val fixture = fixture()
        assertFalse(fixture.manager.enabled.get())
        assertEquals(LocalConfigMode.Shared, fixture.serverPreferences.localConfigMode.get())

        fixture.manager.enableClassification()

        assertTrue(fixture.manager.enabled.get())
        assertEquals(LocalConfigMode.Separate, fixture.serverPreferences.localConfigMode.get())

        fixture.manager.disableClassificationAndUseSharedConfig()

        assertFalse(fixture.manager.enabled.get())
        assertEquals(LocalConfigMode.Shared, fixture.serverPreferences.localConfigMode.get())
    }

    private fun fixture(): Fixture {
        val store = InMemoryPreferenceStore()
        val json = Json { ignoreUnknownKeys = true }
        val serverPreferences = KomgaServerPreferences(store, json)
        val localConfigManager = KomgaLocalConfigManager(
            preferenceStore = store,
            serverPreferences = serverPreferences,
            scopedPreferenceKeys = emptySet(),
        )
        val manager = KomgaLibraryClassificationManager(
            preferenceStore = store,
            serverPreferences = serverPreferences,
            localConfigManager = localConfigManager,
            json = json,
        )
        return Fixture(manager, serverPreferences)
    }

    private data class Fixture(
        val manager: KomgaLibraryClassificationManager,
        val serverPreferences: KomgaServerPreferences,
    )
}
