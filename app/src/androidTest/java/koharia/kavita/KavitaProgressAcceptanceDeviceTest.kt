package koharia.kavita

import androidx.test.platform.app.InstrumentationRegistry
import koharia.domain.kavita.KavitaOperation
import koharia.domain.kavita.KavitaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KavitaProgressAcceptanceDeviceTest {
    @Test fun acceptanceIsAccountScopedAndRollsBackIfPausedBeforeCommit(): Unit = runBlocking(Dispatchers.IO) {
        assertEquals(
            "app.koharia.dev.devicefixture",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
        val repository = Injekt.get<KavitaRepository>()
        val id = -System.nanoTime()
        assertTrue(repository.operations(id, "a").isEmpty())
        assertTrue(repository.operations(id, "b").isEmpty())
        val pending = KavitaOperation("progress/4", "pending", 1, true)
        val accepted = pending.copy(payload = "remote", revision = 2, pending = false)
        try {
            repository.putOperation(id, "a", pending)
            repository.putOperation(id, "b", pending)
            var checks = 0
            val failure = runCatching {
                repository.compareAndSetOperation(id, "a", pending, accepted) {
                    checks++
                    check(checks != 2) { "Connection paused before transaction commit" }
                }
            }
            assertTrue(failure.isFailure)
            assertEquals(2, checks)
            assertEquals(pending, repository.operation(id, "a", pending.key))
            assertTrue(repository.compareAndSetOperation(id, "a", pending, accepted) {})
            assertEquals(accepted, repository.operation(id, "a", pending.key))
            assertEquals(pending, repository.operation(id, "b", pending.key))
            assertFalse(repository.compareAndSetOperation(id, "a", pending, accepted) {})
            // A same-revision payload change (for example a restored hold) must also prevent adoption.
            val held = pending.copy(payload = "held")
            repository.putOperation(id, "a", held)
            assertFalse(repository.compareAndSetOperation(id, "a", pending, accepted) {})
            assertEquals(held, repository.operation(id, "a", pending.key))
            repository.removeConnection(id)
            assertFalse(repository.compareAndSetOperation(id, "a", pending, accepted) {})
            assertEquals(null, repository.operation(id, "a", pending.key))
        } finally {
            repository.removeConnection(id)
        }
    }
}
