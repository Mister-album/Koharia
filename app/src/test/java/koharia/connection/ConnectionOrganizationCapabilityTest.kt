package koharia.connection

import koharia.source.kavita.KavitaSource
import koharia.source.komga.KomgaSource
import koharia.source.lanraragi.LanraragiSource
import koharia.source.local.LocalFolderSource
import koharia.source.smanga.SmangaSource
import koharia.source.suwayomi.SuwayomiSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ConnectionOrganizationCapabilityTest {
    @Test
    fun `organization opt in inventory stays aligned with production registrations`() {
        val inventory =
            mapOf(
                "KomgaConnectionProvider" to KomgaSource::class.java,
                "KavitaConnectionProvider" to KavitaSource::class.java,
                "LanraragiConnectionProvider" to LanraragiSource::class.java,
                "LocalFolderConnectionProvider" to LocalFolderSource::class.java,
                "SmangaConnectionProvider" to SmangaSource::class.java,
                "SuwayomiConnectionProvider" to SuwayomiSource::class.java,
            )
        val registration =
            File("src/main/java/eu/kanade/tachiyomi/di/AppModule.kt")
                .readText()
                .substringAfter("ConnectionRegistry(")
                .substringBefore("addSingletonFactory")
        val providers =
            Regex("(\\w+ConnectionProvider)\\(")
                .findAll(registration)
                .map { it.groupValues[1] }
                .toSet()
        assertEquals(
            inventory.keys,
            providers,
            "Review organization applicability when registering a provider",
        )
        inventory.forEach { (provider, source) ->
            val supportsKomgaOrganization = provider == "KomgaConnectionProvider"
            assertEquals(
                supportsKomgaOrganization,
                ConnectionOrganizationAdapter::class.java.isAssignableFrom(source),
                provider,
            )
            assertEquals(
                supportsKomgaOrganization,
                ConnectionOrganizationActionsAdapter::class.java.isAssignableFrom(source),
                provider,
            )
        }
        assertTrue(
            ConnectionReadingQueueAdapter::class.java.isAssignableFrom(KomgaSource::class.java),
        )
        assertTrue(
            ConnectionDownloadAliasAdapter::class.java.isAssignableFrom(KomgaSource::class.java),
        )
    }
}
