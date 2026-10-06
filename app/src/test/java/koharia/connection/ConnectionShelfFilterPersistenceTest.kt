package koharia.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rule every shelf shares. Each provider's preference store delegates here, so this is the one
 * place the opt-in behaviour is defined and where the contract is pinned.
 */
class ConnectionShelfFilterPersistenceTest {
    @Test
    fun `a stored order only applies while persistence is on`() {
        assertEquals("author desc", ConnectionShelfFilterPersistence.initialOrder(true, "author desc", "title asc"))
        assertEquals("title asc", ConnectionShelfFilterPersistence.initialOrder(false, "author desc", "title asc"))
    }

    @Test
    fun `an empty stored order never replaces the default`() {
        assertEquals("title asc", ConnectionShelfFilterPersistence.initialOrder(true, "", "title asc"))
        assertEquals("title asc", ConnectionShelfFilterPersistence.initialOrder(true, "   ", "title asc"))
        assertEquals("title asc", ConnectionShelfFilterPersistence.initialOrder(true, "title asc", "title asc"))
    }

    @Test
    fun `an opted-in submission stores and an opted-out one clears`() {
        assertTrue(ConnectionShelfFilterPersistence.shouldStore(true))
        assertFalse(ConnectionShelfFilterPersistence.shouldClear(true))

        // Off means both: nothing is stored, and what was stored is dropped.
        assertFalse(ConnectionShelfFilterPersistence.shouldStore(false))
        assertTrue(ConnectionShelfFilterPersistence.shouldClear(false))
    }
}
