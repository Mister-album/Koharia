package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupConnection
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class BackupConnectionInventoryTest {
    private val connection = BackupConnection(17, "komga", "Home", writesPaused = true)

    @Test
    fun `connection only backups carry provider identity and paused ownership without app preferences`() {
        val original = Backup(emptyList(), backupConnections = listOf(connection))
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), original)
        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
        assertEquals(listOf(connection), BackupConnectionInventory.read(decoded))
    }

    @Test
    fun `schema two payload without manifest still decodes legacy connection inventory`() {
        val original =
            LegacyBackup(
                emptyList(),
                listOf(
                    BackupPreference(
                        "connection_profiles",
                        StringSetPreferenceValue(
                            setOf("""{"id":17,"providerId":"komga","name":"Home"}"""),
                        ),
                    ),
                ),
            )
        val decoded = ProtoBuf.decodeFromByteArray(
            Backup.serializer(),
            ProtoBuf.encodeToByteArray(LegacyBackup.serializer(), original),
        )
        assertEquals(listOf(connection.copy(writesPaused = false)), BackupConnectionInventory.read(decoded))
    }

    @Test
    fun `old Komga profile inventory retains exact source owner`() {
        val backup = Backup(
            emptyList(),
            backupPreferences = listOf(
                BackupPreference(
                    "komga_server_profiles",
                    StringSetPreferenceValue(setOf("""{"id":17,"name":"Home"}""")),
                ),
            ),
        )
        assertEquals(listOf(connection.copy(writesPaused = false)), BackupConnectionInventory.read(backup))
    }

    @Test
    fun `contradictory provider records and duplicate ids fail before restore writes`() {
        val duplicate =
            Backup(emptyList(), backupConnections = listOf(connection, connection.copy(providerId = "kavita")))
        assertThrows(IllegalArgumentException::class.java) { BackupConnectionInventory.read(duplicate) }
        val conflicting = Backup(
            emptyList(),
            backupConnections = listOf(connection),
            backupPreferences = listOf(
                BackupPreference(
                    "connection_profiles",
                    StringSetPreferenceValue(
                        setOf(
                            """{"id":17,"providerId":"kavita","name":"Home"}""",
                        ),
                    ),
                ),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { BackupConnectionInventory.read(conflicting) }
    }

    @Test
    fun `legacy settings only backups recover all built in provider formats without live profile guesses`() {
        val signatures = mapOf(
            "komga" to "Address",
            "lanraragi" to "archive_open_mode",
            "suwayomi" to "auth_mode",
            "smanga" to "account_id",
            "kavita" to "kavita_account",
            "local-folder" to "network_storage_configuration",
        )
        signatures.forEach { (provider, key) ->
            val backup = Backup(
                emptyList(),
                backupSourcePreferences = listOf(
                    BackupSourcePreferences(
                        "source_17",
                        listOf(BackupPreference(key, StringPreferenceValue("fixture"))),
                    ),
                ),
            )
            assertEquals(listOf(BackupConnection(17, provider, provider, true)), BackupConnectionInventory.read(backup))
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://example.invalid/api/v1/series/fixture-series",
            "https://example.invalid/api/v1/books/fixture-book",
            "https://example.invalid/api/v1/readlists/fixture-list",
            "http://example.invalid/media/komga/api/v1/series/fixture-series",
            "http://example.invalid/media/komga/api/v1/books/fixture-book",
            "http://example.invalid/media/komga/api/v1/readlists/fixture-list",
        ],
    )
    fun `legacy Komga library only backup retains a paused importable owner`(url: String) {
        val backup = libraryOnly(url)
        // Legacy library-only exports have neither source preferences nor a provider inventory.
        val decoded = ProtoBuf.decodeFromByteArray(
            Backup.serializer(),
            ProtoBuf.encodeToByteArray(Backup.serializer(), backup),
        )
        assertEquals(listOf(connection), BackupConnectionInventory.read(decoded))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://example.invalid/api/v1/series/",
            "https://example.invalid/api/v1/series/fixture/thumbnail",
            "https://example.invalid/api/v1/collections/fixture",
            "https://example.invalid/api/v2/books/fixture",
            "https://example.invalid/?next=/api/v1/books/fixture",
            "https://example.invalid/api/v1/series/fixture%2Fthumbnail",
            "/api/v1/series/fixture",
            "koharia-local-v2://18/api/v1/series/fixture",
        ],
    )
    fun `unrecognized legacy URLs do not guess Komga ownership`(url: String) {
        assertEquals(emptyList<BackupConnection>(), BackupConnectionInventory.read(libraryOnly(url)))
    }

    @Test
    fun `Komga library URLs cannot override conflicting legacy provider evidence`() {
        val backup = libraryOnly("https://example.invalid/api/v1/series/fixture").copy(
            backupSourcePreferences = listOf(
                BackupSourcePreferences(
                    "source_17",
                    listOf(BackupPreference("kavita_account", StringPreferenceValue("fixture"))),
                ),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { BackupConnectionInventory.read(backup) }
        val mixed = backup.copy(
            backupSourcePreferences = emptyList(),
            backupManga = backup.backupManga + BackupManga(17, "/kavita/17/account/series/1", "Fixture"),
        )
        assertThrows(IllegalArgumentException::class.java) { BackupConnectionInventory.read(mixed) }
    }

    @Test
    fun `legacy library only inference preserves every provider resource namespace`() {
        val resources = listOf(
            "komga" to "https://example.invalid/api/v1/books/fixture",
            "lanraragi" to "/lanraragi/17/archive/fixture",
            "suwayomi" to "/suwayomi/17/account/manga/1",
            "kavita" to "/kavita/17/account/series/1",
            "smanga" to "/smanga/17/account/manga/1",
            "local-folder" to "koharia-local-v1://17/library/fixture",
            "local-folder" to "koharia-local-v2://17/library/fixture",
        )
        resources.forEach { (provider, url) ->
            assertEquals(
                listOf(connection.copy(providerId = provider)),
                BackupConnectionInventory.read(libraryOnly(url)),
            )
        }
    }

    private fun libraryOnly(url: String) = Backup(
        backupManga = listOf(BackupManga(17, url, "Fixture")),
        backupSources = listOf(BackupSource("Home", 17)),
    )

    @Serializable
    private data class LegacyBackup(
        @ProtoNumber(1) val backupManga: List<eu.kanade.tachiyomi.data.backup.models.BackupManga>,
        @ProtoNumber(104) val backupPreferences: List<BackupPreference>,
    )
}
