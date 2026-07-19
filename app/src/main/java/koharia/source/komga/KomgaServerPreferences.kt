package koharia.source.komga

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

@Serializable
data class KomgaServerProfile(
    val id: Long,
    val name: String,
)

@Serializable
private data class KomgaServerDirectoryAlias(
    val serverId: Long,
    val serverName: String,
)

enum class LocalConfigMode {
    Shared,
    Separate,
}

enum class DownloadDirectoryMode {
    PerServer,
    Shared,
}

class KomgaServerPreferences(
    private val context: Context,
    preferenceStore: PreferenceStore,
    private val json: Json,
) {

    private val serializedProfiles: Preference<Set<String>> = preferenceStore.getStringSet(
        PREF_PROFILES,
        emptySet(),
    )

    val activeServerId: Preference<Long> = preferenceStore.getLong(
        PREF_ACTIVE_SERVER_ID,
        NO_ACTIVE_SERVER,
    )

    val localConfigMode: Preference<LocalConfigMode> = preferenceStore.getEnum(
        PREF_LOCAL_CONFIG_MODE,
        LocalConfigMode.Shared,
    )

    val downloadDirectoryMode: Preference<DownloadDirectoryMode> = preferenceStore.getEnum(
        PREF_DOWNLOAD_DIRECTORY_MODE,
        DownloadDirectoryMode.PerServer,
    )

    private val hasInitializedProfiles: Preference<Boolean> = preferenceStore.getBoolean(
        PREF_HAS_INITIALIZED_PROFILES,
        false,
    )

    private val knownServerIds: Preference<Set<String>> = preferenceStore.getStringSet(
        PREF_KNOWN_SERVER_IDS,
        emptySet(),
    )

    private val downloadDirectoryLayoutVersion: Preference<Int> = preferenceStore.getInt(
        PREF_DOWNLOAD_DIRECTORY_LAYOUT_VERSION,
        0,
    )

    private val serializedDirectoryAliases: Preference<Set<String>> = preferenceStore.getStringSet(
        PREF_DIRECTORY_ALIASES,
        emptySet(),
    )

    fun profilesChanges(): Flow<List<KomgaServerProfile>> {
        return serializedProfiles.changes()
            .map(::decodeProfiles)
            .map(::normalizeProfiles)
            .distinctUntilChanged()
    }

    fun getProfiles(): List<KomgaServerProfile> {
        return normalizeProfiles(decodeProfiles(serializedProfiles.get()))
    }

    fun setProfiles(profiles: Collection<KomgaServerProfile>) {
        val normalizedProfiles = normalizeProfiles(profiles.toList())
        rememberServerIds(normalizedProfiles)
        serializedProfiles.set(normalizedProfiles.mapTo(linkedSetOf(), json::encodeToString))
        // An explicit profile update (including deleting the last profile) is no longer
        // an initialization attempt. This prevents legacy recovery from recreating a
        // profile the user deliberately removed.
        hasInitializedProfiles.set(true)
        ensureActiveServerExists(normalizedProfiles)
    }

    fun ensureProfilesInitialized() {
        val persistedProfiles = decodeProfiles(serializedProfiles.get())
        val isFirstInitialization = !hasInitializedProfiles.get()
        val profiles = buildList {
            addAll(persistedProfiles)

            // 0.1.x stored the only Komga server in source_<KomgaSource.ID>.
            // Keep that stable ID as the first profile when an interrupted or
            // partial upgrade left the new profile list incomplete. The
            // source preference file is intentionally left untouched: the
            // existing address and credentials are already keyed by this ID.
            if (isFirstInitialization && hasLegacySourcePreferences() && none { it.id == KomgaSource.ID }) {
                add(defaultProfile())
            }

            // A fresh install has no legacy source preference to inspect.
            if (isFirstInitialization && isEmpty()) add(defaultProfile())
        }

        if (isFirstInitialization || profiles != persistedProfiles) {
            serializedProfiles.set(profiles.mapTo(linkedSetOf(), json::encodeToString))
            hasInitializedProfiles.set(true)
        }
        rememberServerIds(profiles)
        ensureActiveServerExists(profiles)
    }

    fun isKnownServerId(serverId: Long): Boolean {
        return serverId == KomgaSource.ID || serverId.toString() in knownServerIds.get()
    }

    fun needsDownloadDirectoryLayoutMigration(): Boolean {
        return downloadDirectoryLayoutVersion.get() < DOWNLOAD_DIRECTORY_LAYOUT_VERSION
    }

    fun markDownloadDirectoryLayoutMigrated() {
        downloadDirectoryLayoutVersion.set(DOWNLOAD_DIRECTORY_LAYOUT_VERSION)
    }

    fun getDirectoryAliases(serverId: Long): Set<String> {
        return decodeDirectoryAliases(serializedDirectoryAliases.get())
            .asSequence()
            .filter { it.serverId == serverId }
            .mapTo(linkedSetOf(), KomgaServerDirectoryAlias::serverName)
    }

    fun rememberDirectoryAlias(serverId: Long, serverName: String) {
        val alias = KomgaServerDirectoryAlias(serverId, serverName)
        val updated = decodeDirectoryAliases(serializedDirectoryAliases.get())
            .filterNot { it.serverId == serverId && it.serverName == serverName }
            .plus(alias)
        serializedDirectoryAliases.set(updated.mapTo(linkedSetOf(), json::encodeToString))
    }

    private fun rememberServerIds(profiles: Collection<KomgaServerProfile>) {
        val updated = knownServerIds.get().toMutableSet().apply {
            add(KomgaSource.ID.toString())
            profiles.mapTo(this) { it.id.toString() }
        }
        knownServerIds.set(updated)
    }

    private fun hasLegacySourcePreferences(): Boolean {
        val preferences = context.getSharedPreferences(
            "source_${KomgaSource.ID}",
            Context.MODE_PRIVATE,
        )
        return preferences.contains(PREF_LEGACY_ADDRESS)
    }

    private fun ensureActiveServerExists(profiles: List<KomgaServerProfile>) {
        when {
            profiles.isEmpty() -> activeServerId.set(NO_ACTIVE_SERVER)
            profiles.none { it.id == activeServerId.get() } -> activeServerId.set(profiles.first().id)
        }
    }

    private fun normalizeProfiles(profiles: List<KomgaServerProfile>): List<KomgaServerProfile> {
        return profiles
            .distinctBy(KomgaServerProfile::id)
            .sortedWith(
                compareByDescending<KomgaServerProfile> { it.id == KomgaSource.ID }
                    .thenBy(KomgaServerProfile::id),
            )
    }

    private fun decodeProfiles(values: Set<String>): List<KomgaServerProfile> {
        return values
            .mapNotNull { value ->
                runCatching { json.decodeFromString<KomgaServerProfile>(value) }.getOrNull()
            }
            .distinctBy(KomgaServerProfile::id)
    }

    private fun decodeDirectoryAliases(values: Set<String>): List<KomgaServerDirectoryAlias> {
        return values.mapNotNull { value ->
            runCatching { json.decodeFromString<KomgaServerDirectoryAlias>(value) }.getOrNull()
        }
    }

    private fun defaultProfile(): KomgaServerProfile {
        return KomgaServerProfile(
            id = KomgaSource.ID,
            name = KomgaSource.SOURCE_NAME,
        )
    }

    companion object {
        private const val PREF_PROFILES = "komga_server_profiles"
        private const val PREF_ACTIVE_SERVER_ID = "komga_active_server_id"
        private const val PREF_LOCAL_CONFIG_MODE = "komga_local_config_mode"
        private const val PREF_DOWNLOAD_DIRECTORY_MODE = "komga_download_directory_mode"
        private const val PREF_HAS_INITIALIZED_PROFILES = "komga_has_initialized_profiles"
        private const val PREF_KNOWN_SERVER_IDS = "komga_known_server_ids"
        private const val PREF_DOWNLOAD_DIRECTORY_LAYOUT_VERSION = "komga_download_directory_layout_version"
        private const val PREF_DIRECTORY_ALIASES = "komga_server_directory_aliases"
        private const val PREF_LEGACY_ADDRESS = "Address"
        private const val DOWNLOAD_DIRECTORY_LAYOUT_VERSION = 1

        const val NO_ACTIVE_SERVER = -1L
    }
}
