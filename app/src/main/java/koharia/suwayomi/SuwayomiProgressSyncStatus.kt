package koharia.suwayomi

import koharia.connection.ConnectionProgressSyncStatus
import koharia.domain.suwayomi.SuwayomiOperation
import kotlinx.serialization.json.Json

internal fun SuwayomiOperation.progressSyncStatus(json: Json): Set<ConnectionProgressSyncStatus> = try {
    val state = json.decodeFromString<SuwayomiReadState>(payload)
    require(key == state.chapterId.toString())
    when {
        state.conflict -> setOf(ConnectionProgressSyncStatus.CONFLICT)
        pending && state.initial -> setOf(ConnectionProgressSyncStatus.HELD)
        pending -> setOf(ConnectionProgressSyncStatus.PENDING)
        else -> emptySet()
    }
} catch (_: IllegalArgumentException) {
    setOf(ConnectionProgressSyncStatus.UNAVAILABLE)
}
