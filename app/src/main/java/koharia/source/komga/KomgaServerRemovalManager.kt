package koharia.source.komga

import androidx.core.content.edit
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.KomgaSharedDownloadIndexManager
import koharia.epub.cache.EpubCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class KomgaServerRemovalManager(
    private val serverPreferences: KomgaServerPreferences,
    private val localConfigManager: KomgaLocalConfigManager,
    private val libraryClassificationManager: KomgaLibraryClassificationManager,
    private val downloadProvider: DownloadProvider,
    private val downloadCache: DownloadCache,
    private val komgaSharedDownloadIndexManager: KomgaSharedDownloadIndexManager,
    private val epubCacheManager: EpubCacheManager,
) {

    suspend fun removeServer(
        serverId: Long,
        options: DownloadCleanupMode = DownloadCleanupMode.Preserve,
    ) {
        val currentProfiles = serverPreferences.getProfiles()
        val profile = currentProfiles.find { it.id == serverId } ?: return

        withContext(Dispatchers.IO) {
            clearServerSettingsIfNeeded(serverId)
            cleanupDownloads(profile, options)
            epubCacheManager.clearServer(serverId)
            libraryClassificationManager.clearServer(serverId)
            localConfigManager.clearScopeForServer(serverId)
        }
        serverPreferences.setProfiles(currentProfiles - profile)

        logcat(LogPriority.DEBUG) {
            "Removed Komga server id=$serverId name=${profile.name} " +
                "(localConfigMode=${serverPreferences.localConfigMode.get()}, " +
                "downloadDirectoryMode=${serverPreferences.downloadDirectoryMode.get()}, " +
                "cleanupMode=$options)"
        }
    }

    private fun clearServerSettingsIfNeeded(serverId: Long) {
        if (serverPreferences.localConfigMode.get() == LocalConfigMode.Shared) {
            logcat(LogPriority.DEBUG) {
                "Skipping persisted source settings cleanup for Komga server id=$serverId because local config mode is shared"
            }
            return
        }

        eu.kanade.tachiyomi.source.sourcePreferences("source_$serverId")
            .edit { clear() }

        logcat(LogPriority.DEBUG) {
            "Cleared persisted source settings for Komga server id=$serverId (downloads preserved)"
        }
    }

    private suspend fun cleanupDownloads(
        profile: KomgaServerProfile,
        mode: DownloadCleanupMode,
    ) {
        when (mode) {
            DownloadCleanupMode.Preserve -> {
                komgaSharedDownloadIndexManager.removeServerIndexes(profile.id)
            }
            else -> {
                if (serverPreferences.downloadDirectoryMode.get() == DownloadDirectoryMode.Shared) {
                    komgaSharedDownloadIndexManager.cleanupServerDownloads(profile.id, mode)
                } else {
                    val source = KomgaSource(profile.id, profile.name)
                    downloadProvider.findSourceDir(source)?.delete()
                    downloadCache.removeSource(source)
                    komgaSharedDownloadIndexManager.removeServerIndexes(profile.id)
                }
            }
        }
        downloadCache.invalidateCache()
    }
}
