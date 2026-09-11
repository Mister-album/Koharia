package koharia.connection

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionRestoreStateTest {
    @Test
    fun `nested restoration remains paused until outer restore finishes`() = runTest {
        assertFalse(ConnectionRestoreState.isRestoring)
        ConnectionRestoreState.duringRestore {
            ConnectionRestoreState.duringRestore { assertTrue(ConnectionRestoreState.isRestoring) }
            assertTrue(ConnectionRestoreState.isRestoring)
        }
        assertFalse(ConnectionRestoreState.isRestoring)
    }

    @Test
    fun `failed restore releases progress pause`() = runTest {
        runCatching { ConnectionRestoreState.duringRestore { error("restore failed") } }
        assertFalse(ConnectionRestoreState.isRestoring)
    }
}
