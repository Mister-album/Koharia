package koharia.connection

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
import kotlin.random.Random

class ConnectionPreferences(
    private val preferenceStore: PreferenceStore,
    private val json: Json,
) {

    private val serializedProfiles = preferenceStore.getStringSet(PREF_PROFILES, emptySet())
    private val knownConnectionIds = preferenceStore.getStringSet(PREF_KNOWN_CONNECTION_IDS, emptySet())
    private val serializedProviderMappings = preferenceStore.getStringSet(PREF_PROVIDER_MAPPINGS, emptySet())
    private val hasInitializedProfiles = preferenceStore.getBoolean(PREF_HAS_INITIALIZED_PROFILES, false)

    val activeConnectionId: Preference<Long> = preferenceStore.getLong(
        PREF_ACTIVE_CONNECTION_ID,
        NO_ACTIVE_CONNECTION,
    )

    val configMode: Preference<ConnectionConfigMode> = preferenceStore.getEnum(
        PREF_CONFIG_MODE,
        ConnectionConfigMode.Shared,
    )

    fun profilesChanges(): Flow<List<LibraryConnectionProfile>> {
        return serializedProfiles.changes()
            .map(::decodeProfiles)
            .map(::normalizeProfiles)
            .distinctUntilChanged()
    }

    fun getProfiles(): List<LibraryConnectionProfile> {
        return normalizeProfiles(decodeProfiles(serializedProfiles.get()))
    }

    @Synchronized
    fun setProfiles(profiles: Collection<LibraryConnectionProfile>) {
        val normalized = normalizeProfiles(profiles.toList())
        require(normalized.all { it.id > LOCAL_CONNECTION_ID }) {
            "Connection IDs must be positive"
        }
        require(normalized.all { it.providerId.isNotBlank() }) {
            "Connection provider IDs must not be blank"
        }

        val mappings = providerMappings().toMutableMap()
        normalized.forEach { profile ->
            val existingProvider = mappings[profile.id]
            require(existingProvider == null || existingProvider == profile.providerId) {
                "Connection ID ${profile.id} is permanently reserved for provider $existingProvider"
            }
            mappings[profile.id] = profile.providerId
        }

        rememberConnectionIds(normalized.map(LibraryConnectionProfile::id))
        setProviderMappings(mappings)
        serializedProfiles.set(normalized.mapTo(linkedSetOf(), json::encodeToString))
        hasInitializedProfiles.set(true)
        ensureActiveConnectionExists(normalized)
    }

    fun ensureProfilesInitialized() {
        val profiles = getProfiles()
        if (!hasInitializedProfiles.get()) {
            serializedProfiles.set(profiles.mapTo(linkedSetOf(), json::encodeToString))
            hasInitializedProfiles.set(true)
        }
        rememberConnectionIds(profiles.map(LibraryConnectionProfile::id))
        rememberProviderMappings(profiles)
        ensureActiveConnectionExists(profiles)
    }

    fun providerIdForSource(sourceId: Long): String? {
        return providerMappings()[sourceId]
    }

    fun isKnownConnectionId(connectionId: Long): Boolean {
        return connectionId.toString() in knownConnectionIds.get()
    }

    @Synchronized
    fun allocateConnectionId(): Long {
        val reservedIds = knownConnectionIds.get()
            .mapNotNullTo(mutableSetOf(), String::toLongOrNull)
            .apply {
                add(LOCAL_CONNECTION_ID)
                getProfiles().mapTo(this, LibraryConnectionProfile::id)
            }
        val nextSequentialId = reservedIds.maxOrNull()
            ?.takeIf { it < Long.MAX_VALUE }
            ?.plus(1)
            ?.takeIf { it > LOCAL_CONNECTION_ID }
        val allocatedId = nextSequentialId ?: generateSequence {
            Random.nextLong(1, Long.MAX_VALUE)
        }.first { it !in reservedIds }

        rememberConnectionIds(listOf(allocatedId))
        return allocatedId
    }

    internal fun hasPersistedProfiles(): Boolean = serializedProfiles.isSet()

    internal fun hasPersistedActiveConnection(): Boolean = activeConnectionId.isSet()

    internal fun hasPersistedConfigMode(): Boolean = configMode.isSet()

    private fun rememberConnectionIds(ids: Collection<Long>) {
        val updated = knownConnectionIds.get().toMutableSet().apply {
            ids.mapTo(this) { it.toString() }
        }
        knownConnectionIds.set(updated)
    }

    private fun rememberProviderMappings(profiles: Collection<LibraryConnectionProfile>) {
        val mappings = providerMappings().toMutableMap()
        profiles.forEach { profile -> mappings.putIfAbsent(profile.id, profile.providerId) }
        setProviderMappings(mappings)
    }

    private fun providerMappings(): Map<Long, String> {
        return serializedProviderMappings.get()
            .mapNotNull { value ->
                runCatching { json.decodeFromString<SourceProviderMapping>(value) }.getOrNull()
            }
            .associate { it.sourceId to it.providerId }
    }

    private fun setProviderMappings(mappings: Map<Long, String>) {
        serializedProviderMappings.set(
            mappings.entries.mapTo(linkedSetOf()) { (sourceId, providerId) ->
                json.encodeToString(SourceProviderMapping(sourceId, providerId))
            },
        )
    }

    private fun ensureActiveConnectionExists(profiles: List<LibraryConnectionProfile>) {
        when {
            profiles.isEmpty() -> activeConnectionId.set(NO_ACTIVE_CONNECTION)
            profiles.none { it.id == activeConnectionId.get() } -> activeConnectionId.set(profiles.first().id)
        }
    }

    private fun normalizeProfiles(profiles: List<LibraryConnectionProfile>): List<LibraryConnectionProfile> {
        return profiles
            .distinctBy(LibraryConnectionProfile::id)
            .sortedBy(LibraryConnectionProfile::id)
    }

    private fun decodeProfiles(values: Set<String>): List<LibraryConnectionProfile> {
        return values.mapNotNull { value ->
            runCatching { json.decodeFromString<LibraryConnectionProfile>(value) }.getOrNull()
        }
    }

    @Serializable
    private data class SourceProviderMapping(
        val sourceId: Long,
        val providerId: String,
    )

    companion object {
        internal const val PREF_PROFILES = "connection_profiles"
        internal const val PREF_ACTIVE_CONNECTION_ID = "connection_active_id"
        internal const val PREF_CONFIG_MODE = "connection_config_mode"
        internal const val PREF_HAS_INITIALIZED_PROFILES = "connection_has_initialized_profiles"
        internal const val PREF_KNOWN_CONNECTION_IDS = "connection_known_ids"
        internal const val PREF_PROVIDER_MAPPINGS = "connection_source_provider_mappings"

        const val LOCAL_CONNECTION_ID = 0L
    }
}
