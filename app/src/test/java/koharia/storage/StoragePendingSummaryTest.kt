package koharia.storage

import koharia.domain.storage.StorageRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StoragePendingSummaryTest {
    private val event = StorageProgressRecord("book", "version", "writer", 1, 100)
    private fun row(state: StoragePendingProgress) = StorageRecord("book", Json.encodeToString(state), 1)

    @Test
    fun heldRecordsAreNotRetryableEvenWithLegacyPendingFalse() {
        assertEquals(
            StoragePendingSummary(held = 2, mutations = 3),
            storagePendingSummary(
                listOf(
                    row(StoragePendingProgress(event, pending = true, requiresConfirmation = true)),
                    row(StoragePendingProgress(event, pending = false, requiresConfirmation = true)),
                ),
                3,
                true,
                Json,
            ),
        )
    }

    @Test
    fun connectionHoldDoesNotInventPendingForLocallyCompletedRecords() {
        val rows = listOf(row(StoragePendingProgress(event, true)), row(StoragePendingProgress(event, false)))
        assertEquals(StoragePendingSummary(held = 1), storagePendingSummary(rows, 0, false, Json))
        assertEquals(StoragePendingSummary(pending = 1), storagePendingSummary(rows, 0, true, Json))
    }

    @Test
    fun corruptAndMisownedRecordsRemainVisibleWithoutHidingValidPending() {
        val invalid = listOf(
            StorageRecord("book", "broken", 1),
            row(StoragePendingProgress(event.copy(resource = "other"), true)),
            row(StoragePendingProgress(event.copy(device = ""), true)),
            row(StoragePendingProgress(event.copy(sequence = -1), true)),
            row(StoragePendingProgress(event.copy(schema = 2), true)),
        )
        assertEquals(
            StoragePendingSummary(pending = 1, unavailable = invalid.size),
            storagePendingSummary(invalid + row(StoragePendingProgress(event, true)), 0, true, Json),
        )
    }
}
