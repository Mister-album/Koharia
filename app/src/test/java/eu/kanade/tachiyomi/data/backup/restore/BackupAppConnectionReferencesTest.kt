package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import koharia.connection.LibraryConnectionProfile
import koharia.connection.SharedConfigMigration
import koharia.connection.SharedConfigStorage
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupAppConnectionReferencesTest {
    @Test
    fun `account-bound learned clock evidence is never restored into another installation`() {
        val prefix = "epub_reader_komga_owner_timestamp_offset_minutes_"
        val backup = listOf("", "connection_shared::", "connection_7::").map {
            BackupPreference("${it}${prefix}7_fixture-owner", LongPreferenceValue(60))
        }
        assertTrue(BackupAppConnectionReferences.remap(backup, mapOf(7L to target)).isEmpty())
    }
    private val target = LibraryConnectionProfile(71, "komga", "Imported (2)")
    private val clock = "epub_reader_komga_server_timestamp_offset_minutes_"

    @Test
    fun `legacy selection resolves remapped candidate and independent settings`() {
        val backup = listOf(
            BackupPreference("komga_local_config_mode", StringPreferenceValue("Separate")),
            BackupPreference("komga_active_server_id", LongPreferenceValue(7)),
            BackupPreference("komga_server_profiles", StringSetPreferenceValue(setOf("""{"id":7,"name":"Old"}"""))),
            BackupPreference("komga_local_server_7::font", StringPreferenceValue("chosen")),
            BackupPreference("komga_local_server_7::${clock}7", LongPreferenceValue(12)),
        )
        val remapped = BackupAppConnectionReferences.remap(backup, mapOf(7L to target))
        val values = mutableMapOf<String, Any>()
        val migration = SharedConfigMigration(
            object : SharedConfigStorage {
                override fun values() = values.toMap()
                override fun commit(updates: Map<String, Any?>): Boolean {
                    updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                    return true
                }
            },
            mapOf("font" to "default"),
            { emptyList() },
            Json,
        )
        remapped.forEach { entry ->
            val key = migration.restoreKey(entry.key, true) ?: return@forEach
            values[key] = when (val value = entry.value) {
                is LongPreferenceValue -> value.value
                is StringPreferenceValue -> value.value
                is StringSetPreferenceValue -> value.value
                else -> error("Unexpected fixture value")
            }
        }
        assertTrue(migration.finishRestore(true))
        assertEquals(listOf(target), migration.candidates())
        assertEquals(target.id, migration.defaultConnectionId())
        assertTrue(migration.select(target.id))
        assertEquals("chosen", values["connection_shared::font"])
        assertEquals(12L, values["connection_shared::${clock}71"])
        assertTrue("connection_shared::${clock}7" !in values)
    }

    @Test
    fun `clock corrections never overwrite unrelated live owners or cross-owner legacy scopes`() {
        val preferences = listOf(
            BackupPreference("connection_shared::${clock}7", LongPreferenceValue(10)),
            BackupPreference("connection_shared::${clock}8", LongPreferenceValue(20)),
            BackupPreference("connection_8::${clock}7", LongPreferenceValue(30)),
            BackupPreference("connection_shared::font", StringPreferenceValue("unchanged")),
        )
        assertEquals(
            listOf(
                BackupPreference("connection_shared::${clock}71", LongPreferenceValue(10)),
                preferences.last(),
            ),
            BackupAppConnectionReferences.remap(preferences, mapOf(7L to target)),
        )
        assertEquals(listOf(preferences.last()), BackupAppConnectionReferences.remap(preferences, emptyMap()))
    }
}
