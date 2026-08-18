package koharia.komga.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomgaSseProgressSyncQueueTest {

    @Test
    fun `book events remain bound to their originating connection`() {
        val queue = KomgaSseProgressSyncQueue()
        val serverA = KomgaSseConnectionTarget(1L, "https://a.test", generation = 1L)
        val serverB = KomgaSseConnectionTarget(2L, "https://b.test", generation = 2L)

        queue.add(PendingKomgaProgressSync(serverA, "shared-book-id"))
        queue.add(PendingKomgaProgressSync(serverA, "shared-book-id"))
        queue.add(PendingKomgaProgressSync(serverB, "shared-book-id"))

        assertEquals(
            listOf(
                PendingKomgaProgressSync(serverA, "shared-book-id"),
                PendingKomgaProgressSync(serverB, "shared-book-id"),
            ),
            queue.drain(),
        )
    }

    @Test
    fun `clearing queue drops pending full and incremental syncs`() {
        val queue = KomgaSseProgressSyncQueue()
        val target = KomgaSseConnectionTarget(1L, "https://komga.test", generation = 1L)
        queue.add(PendingKomgaProgressSync(target, null))
        queue.add(PendingKomgaProgressSync(target, "book-1"))

        queue.clear()

        assertTrue(queue.isEmpty())
        assertTrue(queue.drain().isEmpty())
    }
}
