package koharia.smanga

import koharia.connection.ConnectionProgressSyncStatus
import koharia.domain.smanga.SmangaCacheEntry
import koharia.domain.smanga.SmangaReadState
import kotlinx.serialization.json.Json

internal fun smangaProgressSyncStatus(
    state: SmangaReadState?,
    confirmation: SmangaCacheEntry?,
): Set<ConnectionProgressSyncStatus> = try {
    when {
        confirmation != null && Json.decodeFromString<SmangaUploadConfirmation>(confirmation.payload).required ->
            setOf(ConnectionProgressSyncStatus.CONFLICT)
        state?.pending == true -> setOf(ConnectionProgressSyncStatus.PENDING)
        else -> emptySet()
    }
} catch (_: IllegalArgumentException) {
    setOf(ConnectionProgressSyncStatus.UNAVAILABLE)
}
