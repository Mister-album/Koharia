package koharia.source.komga

import androidx.compose.runtime.Composable
import eu.kanade.tachiyomi.R
import koharia.connection.ConnectionConfigMode
import koharia.connection.ConnectionConfigModeInterceptor
import koharia.connection.ConnectionConfigModeWarning
import koharia.connection.ConnectionLibrarySettingsAdapter
import koharia.connection.ConnectionManagementAdapter
import koharia.connection.ConnectionProvider
import koharia.connection.ConnectionSource
import koharia.connection.LibraryConnectionProfile
import kotlinx.coroutines.flow.map
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KomgaConnectionProvider :
    ConnectionProvider,
    ConnectionManagementAdapter,
    ConnectionLibrarySettingsAdapter,
    ConnectionConfigModeInterceptor {
    override val id: String = ID
    override val displayName: String = KomgaSource.SOURCE_NAME
    override val iconRes: Int = R.drawable.brand_komga
    override val configuresConnectionNameInSettings: Boolean = true

    override fun createSource(profile: LibraryConnectionProfile): ConnectionSource {
        require(profile.providerId == id) { "Unsupported provider ${profile.providerId}" }
        return KomgaSource(
            id = profile.id,
            customName = profile.name,
            connectionProfile = profile,
        )
    }

    override fun createSettingsScreen(
        profile: LibraryConnectionProfile,
        titleOverride: String?,
        isNew: Boolean,
        completeOnboardingOnSave: Boolean,
    ) = KomgaServerSettingsScreen(
        sourceId = profile.id,
        titleOverride = titleOverride,
        isNew = isNew,
        completeOnboardingOnSave = completeOnboardingOnSave,
    )

    override fun directoryNameFor(name: String): String {
        return Injekt.get<KomgaServerProfileManager>().directoryNameFor(name)
    }

    override fun isConnectionNameAvailable(name: String): Boolean {
        return Injekt.get<KomgaServerProfileManager>().isDirectoryNameAvailable(name)
    }

    override suspend fun removeConnection(profile: LibraryConnectionProfile): Result<Boolean> {
        return Injekt.get<KomgaServerRemovalManager>()
            .removeServer(profile.id)
            .map { true }
    }

    override fun configurationChanges() = Injekt.get<KomgaServerPreferences>()
        .downloadDirectoryMode
        .changes()
        .map { Unit }

    @Composable
    override fun ConnectionManagementPreferences() {
        KomgaManagementPreferencesContent()
    }

    @Composable
    override fun ConnectionManagementHelpContent() {
        KomgaManagementHelpContent()
    }

    @Composable
    override fun connectionLibrarySettings() = listOf(KomgaContentClassificationPreferenceGroup())

    override fun warningForConfigMode(mode: ConnectionConfigMode): ConnectionConfigModeWarning? {
        val classificationManager = Injekt.get<KomgaLibraryClassificationManager>()
        if (mode != ConnectionConfigMode.Shared || !classificationManager.enabled.get()) return null
        return ConnectionConfigModeWarning(
            title = tachiyomi.i18n.MR.strings.komga_library_classification_disable_title,
            message = tachiyomi.i18n.MR.strings.komga_library_classification_disable_message,
        )
    }

    override fun prepareConfigModeChange(mode: ConnectionConfigMode) {
        if (mode == ConnectionConfigMode.Shared) {
            Injekt.get<KomgaLibraryClassificationManager>().disableClassification()
        }
    }

    companion object {
        const val ID = "komga"
    }
}
