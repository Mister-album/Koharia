package koharia.source.komga

import android.content.Context
import koharia.connection.ConnectionConfigMode
import koharia.connection.ConnectionPreferences
import koharia.connection.LibraryConnectionProfile
import koharia.connection.NO_ACTIVE_CONNECTION
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

typealias LocalConfigMode = ConnectionConfigMode

enum class DownloadDirectoryMode {
    PerServer,
    Shared,
}

class KomgaServerPreferences(
    @Suppress("UNUSED_PARAMETER") context: Context,
    preferenceStore: PreferenceStore,
    private val json: Json,
    private val connectionPreferences: ConnectionPreferences = ConnectionPreferences(preferenceStore, json),
) {

    val activeServerId: Preference<Long> = connectionPreferences.activeConnectionId

    val localConfigMode: Preference<LocalConfigMode> = connectionPreferences.configMode

    val downloadDirectoryMode: Preference<DownloadDirectoryMode> = preferenceStore.getEnum(
        PREF_DOWNLOAD_DIRECTORY_MODE,
        DownloadDirectoryMode.PerServer,
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
        return connectionPreferences.profilesChanges()
            .map { profiles -> profiles.filter { it.providerId == KomgaConnectionProvider.ID } }
            .map { profiles -> profiles.map { KomgaServerProfile(it.id, it.name) } }
            .distinctUntilChanged()
    }

    fun getProfiles(): List<KomgaServerProfile> {
        return connectionPreferences.getProfiles()
            .filter { it.providerId == KomgaConnectionProvider.ID }
            .map { KomgaServerProfile(it.id, it.name) }
    }

    fun setProfiles(profiles: Collection<KomgaServerProfile>) {
        val otherProviders = connectionPreferences.getProfiles()
            .filterNot { it.providerId == KomgaConnectionProvider.ID }
        val komgaProfiles = profiles.map { profile ->
            LibraryConnectionProfile(
                id = profile.id,
                providerId = KomgaConnectionProvider.ID,
                name = profile.name,
            )
        }
        connectionPreferences.setProfiles(otherProviders + komgaProfiles)
    }

    fun ensureProfilesInitialized() {
        connectionPreferences.ensureProfilesInitialized()
    }

    fun isKnownServerId(serverId: Long): Boolean {
        return serverId == KomgaSource.ID || connectionPreferences.isKnownConnectionId(serverId)
    }

    fun allocateServerId(): Long {
        return connectionPreferences.allocateConnectionId()
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

    private fun decodeDirectoryAliases(values: Set<String>): List<KomgaServerDirectoryAlias> {
        return values.mapNotNull { value ->
            runCatching { json.decodeFromString<KomgaServerDirectoryAlias>(value) }.getOrNull()
        }
    }

    companion object {
        private const val PREF_DOWNLOAD_DIRECTORY_MODE = "komga_download_directory_mode"
        private const val PREF_DOWNLOAD_DIRECTORY_LAYOUT_VERSION = "komga_download_directory_layout_version"
        private const val PREF_DIRECTORY_ALIASES = "komga_server_directory_aliases"
        private const val DOWNLOAD_DIRECTORY_LAYOUT_VERSION = 1

        const val NO_ACTIVE_SERVER = NO_ACTIVE_CONNECTION
    }
}
