package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.track.TrackerManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class BackupFileValidator(
    private val context: Context,

    private val trackerManager: TrackerManager = Injekt.get(),
) {

    /**
     * Checks for critical backup file data.
     *
     * @return Trackers that still need an account after restore.
     */
    fun validate(uri: Uri, password: CharArray? = null): Results {
        val backup = try {
            BackupDecoder(context).decode(uri, password)
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }

        val trackers = backup.backupManga
            .flatMap { it.tracking }
            .map { it.syncId }
            .distinct()
        val missingTrackers = trackers
            .mapNotNull { trackerManager.get(it.toLong()) }
            .filter { !it.isLoggedIn }
            .map { it.name }
            .sorted()

        return Results(missingTrackers)
    }

    data class Results(
        val missingTrackers: List<String>,
    )
}
