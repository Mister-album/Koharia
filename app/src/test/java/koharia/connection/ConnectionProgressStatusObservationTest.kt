package koharia.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConnectionProgressStatusObservationTest {
    @Test
    fun `pausing blocks pending synchronization without relabeling local overrides or conflicts`() = runBlocking {
        val statuses = mapOf(
            "pending" to setOf(ConnectionProgressSyncStatus.PENDING),
            "conflict" to setOf(ConnectionProgressSyncStatus.CONFLICT),
            "local" to setOf(ConnectionProgressSyncStatus.LOCAL_ONLY),
        )
        val pauses = MutableStateFlow(emptySet<Long>())
        val updates = Channel<ProgressStatuses>(Channel.UNLIMITED)
        val job = launch {
            flowOf(statuses).withProgressStatusOwner(1, pauses) {}.collect { updates.send(it) }
        }
        try {
            assertEquals(statuses, withTimeout(5_000) { updates.receive() })
            pauses.value = setOf(1)
            assertEquals(
                statuses + ("pending" to setOf(ConnectionProgressSyncStatus.HELD)),
                withTimeout(5_000) { updates.receive() },
            )
            pauses.value = emptySet()
            assertEquals(statuses, withTimeout(5_000) { updates.receive() })
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `session cancellation clears stale state but still permits a later reload`() = runBlocking {
        val epochs = MutableStateFlow(0)
        val statuses = MutableStateFlow(mapOf("book" to setOf(ConnectionProgressSyncStatus.PENDING)))
        val updates = Channel<ProgressStatuses>(Channel.UNLIMITED)
        val job = launch {
            observeProgressStatusSessions(epochs, listOf("book")) {
                if (epochs.value == 0) {
                    flow { throw CancellationException("Obsolete owner") }
                } else {
                    statuses
                }
            }.collect { updates.send(it) }
        }
        try {
            assertEquals(emptyMap<String, Nothing>(), withTimeout(5_000) { updates.receive() })
            assertEquals(
                mapOf("book" to setOf(ConnectionProgressSyncStatus.UNAVAILABLE)),
                withTimeout(5_000) { updates.receive() },
            )
            epochs.value = 1
            assertEquals(emptyMap<String, Nothing>(), withTimeout(5_000) { updates.receive() })
            assertEquals(statuses.value, withTimeout(5_000) { updates.receive() })
            job.cancelAndJoin()
            statuses.value = mapOf("book" to setOf(ConnectionProgressSyncStatus.DENIED))
            assertEquals(null, updates.tryReceive().getOrNull())
        } finally {
            job.cancelAndJoin()
        }
    }
}
