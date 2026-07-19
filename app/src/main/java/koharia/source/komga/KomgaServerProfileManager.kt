package koharia.source.komga

import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class KomgaServerProfileManager(
    private val serverPreferences: KomgaServerPreferences,
    private val downloadProvider: DownloadProvider,
    private val downloadCache: DownloadCache,
) {

    fun directoryNameFor(serverName: String): String {
        return downloadProvider.getKomgaServerDirName(serverName.trim())
    }

    fun isDirectoryNameAvailable(serverName: String, excludingServerId: Long? = null): Boolean {
        val candidate = directoryNameFor(serverName)
        return serverPreferences.getProfiles().none { profile ->
            profile.id != excludingServerId &&
                directoryNameFor(profile.name).equals(candidate, ignoreCase = true)
        }
    }

    suspend fun renameServer(serverId: Long, requestedName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val newName = requestedName.trim()
        if (newName.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Server name cannot be empty"))
        }
        if (!isDirectoryNameAvailable(newName, serverId)) {
            return@withContext Result.failure(IllegalArgumentException("Server download directory name conflicts"))
        }

        val currentProfile = serverPreferences.getProfiles().find { it.id == serverId }
            ?: return@withContext Result.failure(IllegalStateException("Server profile no longer exists"))
        if (currentProfile.name == newName) return@withContext Result.success(Unit)

        runCatching {
            val renamedDirectory = downloadProvider.renameKomgaServerDir(
                source = KomgaSource(currentProfile.id, currentProfile.name),
                newServerName = newName,
            ).getOrThrow()
            serverPreferences.rememberDirectoryAlias(serverId, currentProfile.name)

            val latestProfiles = serverPreferences.getProfiles()
            val updatedProfiles = latestProfiles.map { profile ->
                if (profile.id == serverId) profile.copy(name = newName) else profile
            }
            check(updatedProfiles.any { it.id == serverId }) { "Server profile was removed during rename" }

            downloadCache.suppressNextProfileRefresh(updatedProfiles.mapTo(mutableSetOf()) { it.id })
            serverPreferences.setProfiles(updatedProfiles)

            if (renamedDirectory != null) {
                runCatching {
                    val renamedSource = KomgaSource(serverId, newName)
                    val directories = downloadProvider.findSourceDirs(renamedSource)
                    check(directories.isNotEmpty()) { "Renamed server download directories are unavailable" }
                    downloadCache.refreshSourceDirectories(
                        sourceId = serverId,
                        directories = directories,
                    )
                }.onFailure { error ->
                    logcat(LogPriority.ERROR, error) {
                        "Failed to refresh download cache after renaming Komga server id=$serverId"
                    }
                    downloadCache.invalidateCache("komga_server_renamed:serverId=$serverId")
                }
            }

            logcat(LogPriority.INFO) {
                "Renamed Komga server id=$serverId from=${currentProfile.name} to=$newName " +
                    "directory=${directoryNameFor(newName)}"
            }
        }
    }
}
