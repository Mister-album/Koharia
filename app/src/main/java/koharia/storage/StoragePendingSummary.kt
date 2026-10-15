package koharia.storage

import koharia.domain.storage.StorageRecord
import kotlinx.serialization.json.Json

internal data class StoragePendingSummary(
    val pending: Int = 0,
    val held: Int = 0,
    val unavailable: Int = 0,
    val mutations: Int = 0,
)

/** Includes unindexed resources; unknown payloads are never counted as synchronized or retryable. */
internal fun storagePendingSummary(
    rows: List<StorageRecord>,
    mutationCount: Int,
    writesAllowed: Boolean,
    json: Json,
): StoragePendingSummary {
    var pending = 0
    var held = 0
    var unavailable = 0
    for (row in rows) {
        try {
            val state = decodeStoragePendingProgress(row, json)
            when {
                state.requiresConfirmation || (state.pending && !writesAllowed) -> held++
                state.pending -> pending++
            }
        } catch (_: IllegalArgumentException) {
            unavailable++
        }
    }
    return StoragePendingSummary(pending, held, unavailable, mutationCount)
}

internal fun decodeStoragePendingProgress(row: StorageRecord, json: Json): StoragePendingProgress {
    val state = json.decodeFromString<StoragePendingProgress>(row.payload)
    require(row.key == state.record.resource)
    require(latestStorageProgress(listOf(state.record), row.key, state.record.version) != null)
    return state
}
