package koharia.source.komga

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

enum class LocalConfigMode {
    Shared,
    Separate,
}

enum class DownloadDirectoryMode {
    PerServer,
    Shared,
}

class KomgaServerPreferences(
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
        serializedProfiles.set(normalizedProfiles.mapTo(linkedSetOf(), json::encodeToString))
        ensureActiveServerExists(normalizedProfiles)
    }

    fun ensureProfilesInitialized() {
        if (hasInitializedProfiles.get()) {
            ensureActiveServerExists(getProfiles())
            return
        }

        val profiles = decodeProfiles(serializedProfiles.get())
            .ifEmpty { listOf(defaultProfile()) }
        serializedProfiles.set(profiles.mapTo(linkedSetOf(), json::encodeToString))
        hasInitializedProfiles.set(true)
        ensureActiveServerExists(profiles)
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

        const val NO_ACTIVE_SERVER = -1L
    }
}
