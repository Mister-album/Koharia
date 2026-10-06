package koharia.source.local

import android.content.Context
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import koharia.connection.ConnectionProvider
import koharia.connection.ConnectionSource
import koharia.connection.LibraryConnectionProfile
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LocalFolderConnectionProvider(
    private val context: Context,
) : ConnectionProvider {
    override val id: String = ID
    override val displayName: String = context.stringResource(MR.strings.label_local)
    override val iconRes: Int = R.mipmap.ic_local_source
    override val configuresConnectionNameInSettings: Boolean = true

    override fun createSource(profile: LibraryConnectionProfile): ConnectionSource {
        require(profile.providerId == id) { "Unsupported provider ${profile.providerId}" }
        return LocalFolderSource(
            context = context,
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
    ): Screen = LocalFolderSettingsScreen(
        sourceId = profile.id,
        profileName = profile.name,
        titleOverride = titleOverride,
        isNew = isNew,
        completeOnboardingOnSave = completeOnboardingOnSave,
    )

    override fun directoryNameFor(name: String): String = name.trim()

    override fun isConnectionNameAvailable(name: String): Boolean {
        return name.isNotBlank()
    }

    override suspend fun removeConnection(profile: LibraryConnectionProfile): Result<Boolean> = runCatching {
        Injekt.get<LocalLibraryRefreshTasks>().cancel(profile.id)
        val downloads = Injekt.get<DownloadManager>()
        downloads.cancelQueuedDownloads(downloads.queueState.value.filter { it.source.id == profile.id })
        val mangaRepository = Injekt.get<MangaRepository>()
        val coverCache = Injekt.get<CoverCache>()
        mangaRepository.getMangaBySourceId(profile.id).forEach { manga ->
            coverCache.deleteFromCache(manga)
        }
        mangaRepository.deleteMangaBySourceId(profile.id)
        koharia.storage.NetworkStorageRuntime.removeCachedContent(context, profile.id)
        Injekt.get<koharia.domain.storage.LibraryStorageRepository>().removeConnection(profile.id)

        // Let the generic connection cleanup remove the profile and its source-scoped index/configuration.
        false
    }

    companion object {
        const val ID = "local-folder"
    }
}
