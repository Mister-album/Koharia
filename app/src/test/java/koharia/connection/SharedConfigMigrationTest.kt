package koharia.connection

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.ScopedPreferenceStore

class SharedConfigMigrationTest {
    private val profile = LibraryConnectionProfile(7, "lanraragi", "Library")
    private val defaults = mapOf<String, Any>("theme" to 1, "font" to "original", "enabled" to true)

    @Test
    fun `completed startup does not instantiate migration preference defaults`() {
        val storage = Storage(mapOf(SharedConfigMigration.COMPLETED to true, "connection_shared::theme" to 3))
        val before = storage.values()
        val migration = SharedConfigMigration(
            storage,
            { error("Startup must not build defaults") },
            { listOf(profile) },
            Json,
        )
        migration.captureUpgradeSelection()
        assertTrue(migration.initialize())
        assertFalse(migration.isPending())
        assertEquals(before, storage.values())
    }

    @Test
    fun `deferred defaults remain available once for migration and later restore`() {
        val storage = Storage(mapOf("theme" to 2))
        var builds = 0
        val migration = SharedConfigMigration(
            storage,
            {
                builds++
                defaults
            },
            { listOf(profile) },
            Json,
        )
        assertEquals(0, builds)
        assertTrue(migration.initialize())
        assertEquals(1, builds)
        assertEquals(2, storage.data["connection_shared::theme"])
        assertTrue(migration.initialize())
        assertEquals("connection_shared::theme", migration.restoreKey("theme", false))
        assertEquals(1, builds)
    }

    @Test
    fun `completed installation can still lazily classify an imported preference`() {
        val storage = Storage(mapOf(SharedConfigMigration.COMPLETED to true))
        var builds = 0
        val migration = SharedConfigMigration(
            storage,
            {
                builds++
                defaults
            },
            { listOf(profile) },
            Json,
        )
        assertTrue(migration.initialize())
        assertEquals(0, builds)
        assertEquals(
            SharedConfigMigration.RESTORE_PREFIX + "connection_7::theme",
            migration.restoreKey("connection_7::theme", true),
        )
        assertEquals(1, builds)
        assertEquals("connection_shared::font", migration.restoreKey("font", false))
        assertEquals(1, builds)
    }

    private class Storage(initial: Map<String, Any> = emptyMap()) : SharedConfigStorage {
        val data = initial.toMutableMap()
        var fail = false
        override fun values() = data.toMap()
        override fun commit(values: Map<String, Any?>): Boolean {
            if (fail) return false
            values.forEach { (key, value) -> if (value == null) data.remove(key) else data[key] = value }
            return true
        }
    }

    private fun migration(storage: Storage) = SharedConfigMigration(storage, defaults, { listOf(profile) }, Json)

    @Test
    fun `restore preserves device storage while upgrade adopts selected storage`() {
        val key = Preference.appStateKey("storage_dir")
        val storage = Storage(mapOf(SharedConfigMigration.COMPLETED to true, "connection_shared::$key" to "device"))
        val migration = SharedConfigMigration(storage, defaults + (key to "default"), { listOf(profile) }, Json)
        assertTrue(migration.beginRestore())
        storage.commit(
            mapOf(
                migration.restoreKey("connection_profiles", true)!! to
                    setOf("""{"id":7,"providerId":"lanraragi","name":"Library"}"""),
            ),
        )
        assertNull(migration.restoreKey("connection_7::$key", true))
        assertTrue(migration.finishRestore(true))
        assertTrue(migration.select(7))
        assertEquals("device", storage.data["connection_shared::$key"])

        val upgradeStorage = Storage(mapOf("connection_config_mode" to "Separate", "connection_7::$key" to "chosen"))
        val upgrade = SharedConfigMigration(upgradeStorage, defaults + (key to "default"), { listOf(profile) }, Json)
        assertTrue(upgrade.select(7))
        assertEquals("chosen", upgradeStorage.data["connection_shared::$key"])
    }

    @Test
    fun `authentication uses pre-upgrade active configuration and never a backup candidate`() {
        val storage = Storage(
            mapOf(
                "connection_config_mode" to "Separate",
                "connection_active_id" to 7L,
                "connection_7::use_biometric_lock" to true,
                "connection_shared::use_biometric_lock" to false,
            ),
        )
        val migration = migration(storage)
        migration.captureUpgradeSelection()
        storage.commit(mapOf("connection_active_id" to 8L))
        assertTrue(migration.requiresAuthentication())
        storage.commit(mapOf(SharedConfigMigration.COMPLETED to true))
        assertFalse(migration.requiresAuthentication())
        storage.commit(
            mapOf(
                "connection_shared::use_biometric_lock" to true,
                SharedConfigMigration.RESTORE_PREFIX + "connection_7::use_biometric_lock" to false,
                SharedConfigMigration.RESTORE_PENDING to true,
            ),
        )
        assertTrue(migration.requiresAuthentication())
    }

    @Test
    fun `canonical mode wins over stale legacy mode`() {
        assertFalse(
            SharedConfigMigration.legacySeparate(
                mapOf(
                    "connection_config_mode" to "Shared",
                    "komga_local_config_mode" to "Separate",
                ),
            ),
        )
        assertTrue(SharedConfigMigration.legacySeparate(mapOf("komga_local_config_mode" to "Separate")))
    }

    @Test
    fun `independent mode without active connection retains shared lock fallback`() {
        val storage = Storage(mapOf("connection_config_mode" to "Separate", "use_biometric_lock" to true))
        assertTrue(migration(storage).requiresAuthentication())
        storage.commit(mapOf("connection_active_id" to NO_ACTIVE_CONNECTION))
        assertTrue(migration(storage).requiresAuthentication())
        storage.commit(mapOf("connection_shared::use_biometric_lock" to false))
        assertFalse(migration(storage).requiresAuthentication())
    }

    @Test
    fun `shared upgrade preserves precedence and leaves unset values unset`() {
        val storage = Storage(mapOf("theme" to 2, "connection_shared::theme" to 3))
        val migration = migration(storage)
        assertTrue(migration.initialize())
        assertFalse(migration.isPending())
        assertEquals(3, storage.data["connection_shared::theme"])
        assertFalse(storage.data.containsKey("connection_shared::font"))
    }

    @Test
    fun `independent selection does not mix shared or other connection values`() {
        val storage = Storage(
            mapOf(
                "connection_config_mode" to "Separate",
                "connection_active_id" to 7L,
                "connection_shared::theme" to 3,
                "font" to "legacy",
                "connection_7::font" to "chosen",
                "connection_8::theme" to 4,
                "connection_profiles" to setOf("keep"),
                "source_secret" to "keep",
            ),
        )
        val migration = migration(storage)
        assertFalse(migration.initialize())
        assertEquals(7L, migration.defaultConnectionId())
        assertEquals(3, storage.data["connection_shared::theme"])
        assertTrue(migration.select(7))
        assertNull(storage.data["connection_shared::theme"])
        assertNull(storage.data["font"])
        assertEquals("chosen", storage.data["connection_shared::font"])
        assertEquals(4, storage.data["connection_8::theme"])
        assertEquals("keep", storage.data["source_secret"])
        assertTrue(migration(storage).initialize())
        assertFalse(migration(storage).isPending())
    }

    @Test
    fun `failed migration and exit before selection leave previous data intact`() {
        val storage = Storage(mapOf("connection_config_mode" to "Separate", "theme" to 3))
        val before = storage.values()
        assertFalse(migration(storage).initialize())
        assertEquals(before, storage.values())
        storage.fail = true
        assertFalse(migration(storage).select(7))
        assertEquals(before, storage.values())
        assertTrue(migration(storage).isPending())
        storage.fail = false
        assertTrue(migration(storage).select(null))
    }

    @Test
    fun `legacy komga settings migrate and explicit new keys win`() {
        val storage = Storage(
            mapOf(
                "komga_local_config_mode" to "Separate",
                "komga_local_server_7::theme" to 2,
                "connection_7::theme" to 3,
            ),
        )
        val migration = migration(storage)
        assertFalse(migration.initialize())
        assertTrue(migration.select(7))
        assertEquals(3, storage.data["connection_shared::theme"])
    }

    @Test
    fun `restore waits for completion and does not overwrite current settings before choice`() {
        val storage = Storage(mapOf(SharedConfigMigration.COMPLETED to true, "connection_shared::theme" to 3))
        val migration = migration(storage)
        assertTrue(migration.beginRestore())
        storage.commit(
            mapOf(
                migration.restoreKey("komga_local_server_7::theme", true)!! to 2,
                migration.restoreKey("komga_server_profiles", true)!! to setOf("""{"id":7,"name":"Old"}"""),
                migration.restoreKey("komga_active_server_id", true)!! to 7L,
            ),
        )
        assertFalse(migration.isPending())
        assertEquals(3, storage.data["connection_shared::theme"])
        assertTrue(migration.finishRestore(true))
        assertEquals(7L, migration.defaultConnectionId())
        assertEquals("komga", migration.candidates().single().providerId)
        assertTrue(migration.select(7))
        assertEquals(2, storage.data["connection_shared::theme"])
        assertFalse(storage.data.keys.any { it.startsWith(SharedConfigMigration.RESTORE_PREFIX) })
    }

    @Test
    fun `backup compatibility excludes retired scopes and internal state`() {
        val migration = migration(Storage())
        assertTrue(SharedConfigMigration.isRetiredKey("connection_7::theme"))
        assertFalse(SharedConfigMigration.isRetiredKey("connection_profiles"))
        assertFalse(SharedConfigMigration.isRetiredKey("connection_shared::theme"))
        assertNull(migration.restoreKey(SharedConfigMigration.COMPLETED, false))
        assertNull(migration.restoreKey("connection_config_mode", false))
        assertEquals("connection_shared::theme", migration.restoreKey("theme", false))
        assertNull(migration.restoreKey("connection_7::theme", false))
    }

    @Test
    fun `shared preference observers do not follow connection selection`() {
        val raw = MutableTestPreferenceStore()
        val first = ScopedPreferenceStore(raw, SharedAppPreferenceScope).getInt("theme", 1)
        val second = ScopedPreferenceStore(raw, SharedAppPreferenceScope).getInt("theme", 1)
        first.set(4)
        raw.getLong("connection_active_id").set(99)
        raw.getString("connection_config_mode").set("Separate")
        assertEquals(4, second.get())
        second.set(2)
        assertEquals(2, first.get())
        assertFalse(raw.getAll().keys.any { it.startsWith("connection_99::") })
    }

    @Test
    fun `legacy shared backups and tracker credentials are recognized`() {
        val migration = migration(Storage())
        assertEquals("connection_shared::font", migration.restoreKey("komga_local_shared::font", false))
        assertEquals(
            SharedConfigMigration.RESTORE_PREFIX + "connection_7::__PRIVATE_track_token_2",
            migration.restoreKey("connection_7::__PRIVATE_track_token_2", true),
        )
    }

    @Test
    fun `server clock corrections keep their connection identity`() {
        val key = "epub_reader_komga_server_timestamp_offset_minutes_7"
        val storage = Storage(
            mapOf(
                "connection_config_mode" to "Separate",
                "connection_7::$key" to 60L,
                "connection_shared::$key" to 0L,
            ),
        )
        assertTrue(migration(storage).select(null))
        assertEquals(60L, storage.data["connection_shared::$key"])
    }
}
