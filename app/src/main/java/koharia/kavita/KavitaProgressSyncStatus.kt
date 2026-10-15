package koharia.kavita

import koharia.connection.ConnectionProgressSyncStatus
import koharia.domain.kavita.KavitaOperation
import kotlinx.serialization.json.Json

internal fun KavitaOperation.progressSyncStatus(json: Json): Set<ConnectionProgressSyncStatus> = try {
    val state = json.decodeFromString<KavitaReadingState>(payload)
    require(key == "progress/${state.ref.chapterId}")
    when {
        state.conflict -> setOf(ConnectionProgressSyncStatus.CONFLICT)
        pending -> setOf(ConnectionProgressSyncStatus.PENDING)
        else -> emptySet()
    }
} catch (_: IllegalArgumentException) {
    setOf(ConnectionProgressSyncStatus.UNAVAILABLE)
}
