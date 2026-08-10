package koharia.connection

import androidx.compose.runtime.Composable
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge

interface ConnectionSource : Source {
    val connectionProfile: LibraryConnectionProfile

    val providerId: String
        get() = connectionProfile.providerId
}

interface ConnectionProvider {
    val id: String
    val displayName: String

    fun createSource(profile: LibraryConnectionProfile): ConnectionSource

    fun createSettingsScreen(
        profile: LibraryConnectionProfile,
        titleOverride: String? = null,
        isNew: Boolean = false,
    ): Screen? = null

    fun directoryNameFor(name: String): String = name.trim()

    fun isConnectionNameAvailable(name: String): Boolean = name.isNotBlank()

    suspend fun removeConnection(profile: LibraryConnectionProfile): Result<Boolean> = Result.success(false)

    fun configurationChanges(): Flow<Unit> = emptyFlow()
}

interface ConnectionManagementAdapter {
    @Composable
    fun ConnectionManagementPreferences()

    @Composable
    fun ConnectionManagementHelpContent() = Unit
}

interface ConnectionLibrarySettingsAdapter {
    @Composable
    fun connectionLibrarySettings(): List<Preference.PreferenceGroup>
}

interface ConnectionConfigModeInterceptor {
    fun warningForConfigMode(mode: ConnectionConfigMode): ConnectionConfigModeWarning?

    fun prepareConfigModeChange(mode: ConnectionConfigMode)
}

data class ConnectionConfigModeWarning(
    val title: StringResource,
    val message: StringResource,
)

class ConnectionRegistry(providers: Collection<ConnectionProvider>) {
    private val providersById = providers.associateBy(ConnectionProvider::id)

    init {
        require(providersById.size == providers.size) { "Connection provider IDs must be unique" }
        require(providersById.keys.none(String::isBlank)) { "Connection provider IDs must not be blank" }
    }

    fun provider(providerId: String): ConnectionProvider? = providersById[providerId]

    fun availableProviders(): List<ConnectionProvider> = providersById.values.toList()

    fun configurationChanges(): Flow<Unit> = merge(
        *providersById.values.map {
            it.configurationChanges()
        }.toTypedArray(),
    )

    fun createSource(profile: LibraryConnectionProfile): ConnectionSource? {
        return provider(profile.providerId)?.createSource(profile)
    }
}
