package koharia.source.komga

import androidx.core.content.edit
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.KomgaSharedDownloadIndexManager
import koharia.epub.cache.EpubCacheManager
import kotlinx.coroutines.CancellationException
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
    ): Result<Unit> {
        val currentProfiles = serverPreferences.getProfiles()
        val profile = currentProfiles.find { it.id == serverId } ?: return Result.success(Unit)

        logcat(LogPriority.DEBUG) {
            "KomgaServerRemoval: requested serverId=$serverId name=${profile.name} " +
                "cleanupMode=$options directoryMode=${serverPreferences.downloadDirectoryMode.get()}"
        }

        return try {
            withContext(Dispatchers.IO) {
                // Keep credentials and scoped settings until every retryable
                // cleanup step succeeds, so a failure cannot orphan the profile.
                cleanupDownloads(profile, options)
                logcat(LogPriority.DEBUG) {
                    "KomgaServerRemoval: download cleanup completed serverId=$serverId " +
                        "cleanupMode=$options"
                }
                epubCacheManager.clearServer(serverId)
                libraryClassificationManager.clearServer(serverId)
                localConfigManager.clearScopeForServer(serverId)
                clearServerSettingsIfNeeded(serverId)
            }
            // Read the profiles again after cleanup. The settings screen can edit or add
            // another profile while disk/network cleanup is in progress; filtering by ID
            // preserves those concurrent changes instead of writing the stale snapshot.
            val latestProfiles = serverPreferences.getProfiles()
            val remainingProfiles = latestProfiles.filterNot { it.id == serverId }
            downloadCache.suppressNextProfileRefresh(remainingProfiles.mapTo(mutableSetOf()) { it.id })
            serverPreferences.setProfiles(remainingProfiles)
            logcat(LogPriority.DEBUG) {
                "KomgaServerRemoval: profile removed serverId=$serverId " +
                    "remainingProfiles=${remainingProfiles.size}"
            }

            logcat(LogPriority.DEBUG) {
                "Removed Komga server id=$serverId name=${profile.name} " +
                    "(localConfigMode=${serverPreferences.localConfigMode.get()}, " +
                    "downloadDirectoryMode=${serverPreferences.downloadDirectoryMode.get()}, " +
                    "cleanupMode=$options)"
            }
            Result.success(Unit)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            logcat(LogPriority.ERROR, exception) {
                "Failed to remove Komga server id=$serverId; profile and scoped settings were retained"
            }
            Result.failure(exception)
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
        val directoryMode = serverPreferences.downloadDirectoryMode.get()
        var requiresFullScan = false
        when (mode) {
            DownloadCleanupMode.Preserve -> {
                logcat(LogPriority.DEBUG) {
                    "KomgaServerRemoval: preserving files and removing shared indexes " +
                        "serverId=${profile.id}"
                }
                komgaSharedDownloadIndexManager.removeServerIndexes(profile.id)
                // Keep the existing source entry as well as its files. Manga records can still
                // resolve this source through the persisted stub after the server is removed.
            }
            else -> {
                if (directoryMode == DownloadDirectoryMode.Shared) {
                    logcat(LogPriority.DEBUG) {
                        "KomgaServerRemoval: cleaning shared download files serverId=${profile.id} " +
                            "cleanupMode=$mode"
                    }
                    komgaSharedDownloadIndexManager.cleanupServerDownloads(profile.id, mode)
                    // Shared files can belong to more than one server, so deleting them
                    // requires rebuilding the filesystem-backed index.
                    requiresFullScan = true
                } else {
                    val source = KomgaSource(profile.id, profile.name)
                    logcat(LogPriority.DEBUG) {
                        "KomgaServerRemoval: deleting per-server download directory " +
                            "serverId=${profile.id} source=${source.name} cleanupMode=$mode"
                    }
                    downloadProvider.findSourceDir(source)?.delete()
                    downloadProvider.invalidateSourceDirCache()
                    downloadCache.removeSource(source)
                    komgaSharedDownloadIndexManager.removeServerIndexes(profile.id)
                }
            }
        }
        if (requiresFullScan) {
            downloadCache.invalidateCache(
                "server_removal:serverId=${profile.id},mode=$mode,directoryMode=$directoryMode",
            )
        } else {
            logcat(LogPriority.DEBUG) {
                "KomgaServerRemoval: skipped full download scan serverId=${profile.id} " +
                    "mode=$mode directoryMode=$directoryMode"
            }
        }
    }
}
