package koharia.source.komga

import android.content.Context
import koharia.connection.ConnectionConfigMode
import koharia.connection.ConnectionPreferences
import koharia.connection.LibraryConnectionProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.PreferenceStore

class KomgaConnectionMigration(
    private val context: Context,
    private val preferenceStore: PreferenceStore,
    private val connectionPreferences: ConnectionPreferences,
    private val json: Json,
) {

    @Synchronized
    fun migrate(forceLegacyInventory: Boolean = false) {
        val migrationCompleted = preferenceStore.getBoolean(PREF_MIGRATION_COMPLETED, false)
        if (migrationCompleted.get() && !forceLegacyInventory) return

        migrateProfiles(forceLegacyInventory)
        migrateActiveConnection(forceLegacyInventory)
        migrateConfigMode(forceLegacyInventory)
        migrateScopedPreferences()
        connectionPreferences.ensureProfilesInitialized()

        // This must remain the final write so an interrupted migration is retried.
        migrationCompleted.set(true)
    }

    private fun migrateProfiles(forceLegacyInventory: Boolean) {
        if (connectionPreferences.hasPersistedProfiles() && !forceLegacyInventory) return

        val legacyProfiles = preferenceStore.getStringSet(PREF_LEGACY_PROFILES, emptySet()).get()
            .mapNotNull { value ->
                runCatching { json.decodeFromString<LegacyKomgaServerProfile>(value) }.getOrNull()
            }
            .distinctBy(LegacyKomgaServerProfile::id)
            .map { profile ->
                LibraryConnectionProfile(
                    id = profile.id,
                    providerId = KomgaConnectionProvider.ID,
                    name = profile.name,
                )
            }
            .toMutableList()

        val wasLegacyInventoryInitialized = preferenceStore
            .getBoolean(PREF_LEGACY_HAS_INITIALIZED_PROFILES, false)
            .get()
        if (
            !wasLegacyInventoryInitialized &&
            legacyProfiles.none { it.id == KomgaSource.ID } &&
            hasLegacySourcePreferences()
        ) {
            legacyProfiles += LibraryConnectionProfile(
                id = KomgaSource.ID,
                providerId = KomgaConnectionProvider.ID,
                name = KomgaSource.SOURCE_NAME,
            )
        }

        connectionPreferences.setProfiles(legacyProfiles)
    }

    private fun migrateActiveConnection(forceLegacyInventory: Boolean) {
        if (connectionPreferences.hasPersistedActiveConnection() && !forceLegacyInventory) return
        val legacyActive = preferenceStore.getLong(PREF_LEGACY_ACTIVE_SERVER_ID, NO_LEGACY_ACTIVE_SERVER)
        if (legacyActive.isSet()) {
            connectionPreferences.activeConnectionId.set(legacyActive.get())
        }
    }

    private fun migrateConfigMode(forceLegacyInventory: Boolean) {
        if (connectionPreferences.hasPersistedConfigMode() && !forceLegacyInventory) return
        val legacyMode = preferenceStore.getString(PREF_LEGACY_LOCAL_CONFIG_MODE)
        if (legacyMode.isSet()) {
            val mode = runCatching { ConnectionConfigMode.valueOf(legacyMode.get()) }
                .getOrDefault(ConnectionConfigMode.Shared)
            connectionPreferences.configMode.set(mode)
        }
    }

    private fun migrateScopedPreferences() {
        val values = preferenceStore.getAll()
        values.forEach { (key, value) ->
            val targetKey = when {
                key.startsWith(LEGACY_SHARED_SCOPE_PREFIX) ->
                    CONNECTION_SHARED_SCOPE_PREFIX + key.removePrefix(LEGACY_SHARED_SCOPE_PREFIX)
                key.startsWith(LEGACY_SERVER_SCOPE_PREFIX) ->
                    CONNECTION_SCOPE_PREFIX + key.removePrefix(LEGACY_SERVER_SCOPE_PREFIX)
                else -> null
            } ?: return@forEach

            if (targetKey in values) return@forEach
            copyPreference(targetKey, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun copyPreference(targetKey: String, value: Any?) {
        when (value) {
            is String -> preferenceStore.getString(targetKey).set(value)
            is Long -> preferenceStore.getLong(targetKey).set(value)
            is Int -> preferenceStore.getInt(targetKey).set(value)
            is Float -> preferenceStore.getFloat(targetKey).set(value)
            is Boolean -> preferenceStore.getBoolean(targetKey).set(value)
            is Set<*> -> if (value.all { it is String }) {
                preferenceStore.getStringSet(targetKey).set(value as Set<String>)
            }
        }
    }

    private fun hasLegacySourcePreferences(): Boolean {
        return context.getSharedPreferences(
            "source_${KomgaSource.ID}",
            Context.MODE_PRIVATE,
        ).contains(PREF_LEGACY_ADDRESS)
    }

    @Serializable
    private data class LegacyKomgaServerProfile(
        val id: Long,
        val name: String,
    )

    companion object {
        internal const val PREF_MIGRATION_COMPLETED = "connection_komga_migration_completed"
        internal const val PREF_LEGACY_PROFILES = "komga_server_profiles"
        internal const val PREF_LEGACY_ACTIVE_SERVER_ID = "komga_active_server_id"
        internal const val PREF_LEGACY_LOCAL_CONFIG_MODE = "komga_local_config_mode"
        internal const val PREF_LEGACY_HAS_INITIALIZED_PROFILES = "komga_has_initialized_profiles"

        private const val PREF_LEGACY_ADDRESS = "Address"
        private const val NO_LEGACY_ACTIVE_SERVER = -1L
        private const val LEGACY_SHARED_SCOPE_PREFIX = "komga_local_shared::"
        private const val LEGACY_SERVER_SCOPE_PREFIX = "komga_local_server_"
        private const val CONNECTION_SHARED_SCOPE_PREFIX = "connection_shared::"
        private const val CONNECTION_SCOPE_PREFIX = "connection_"
    }
}
