package koharia.storage

import koharia.source.local.NetworkStorageDraft
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class SmbShareDiscoveryTest {
    @Test fun `server address accepts host port and encodes chosen folders`() {
        val address = SmbAddress.parse("192.168.1.10:1445")
        assertEquals("", address.root)
        assertEquals("smb://192.168.1.10:1445", address.server)
        val selected = address.at("中文 共享/Books #1")
        assertEquals("中文 共享/Books #1", SmbAddress.parse(selected).root)
        assertEquals("smb://[::1]:445", SmbAddress.parse("[::1]:445").server)
        assertThrows(IllegalArgumentException::class.java) { SmbAddress.parse("smb://user:pass@host/share") }
        assertThrows(IllegalArgumentException::class.java) { address.at("../outside") }
    }

    @Test fun `choosing a share enables next and resolves bare LAN server with the selected root`() {
        val draft = NetworkStorageDraft(
            NetworkStorageConfiguration(
                mode = LibraryStorageMode.SMB,
                address = "server:445",
                internalAddress = "192.168.1.10",
            ),
            "user",
            "password",
        )
        assertFalse(draft.valid)
        val selected = draft.copy(
            configuration = draft.configuration.copy(address = SmbAddress.parse("server:445").at("Books/Comics")),
        )
        assertTrue(selected.valid)
        assertEquals("smb://192.168.1.10/Books/Comics", selected.resolvedConfiguration().internalAddress)
    }

    @Test fun `authenticated server enumerates shares then folders without a share in the input`() = runBlocking {
        assumeTrue(System.getenv("KOHARIA_SMB_DISCOVERY_FIXTURE") == "1")
        val address = "127.0.0.1:18446"
        authenticateSmbServer(address, "fixture", "fixture-password", "")
        val shares = browseSmbServer(address, "fixture", "fixture-password", "", "")
        assertTrue(shares.any { it.path == "LIBRARY" })
        assertFalse(shares.any { it.name == "IPC$" })
        val folders = browseSmbServer(address, "fixture", "fixture-password", "", "LIBRARY")
        assertTrue(folders.any { it.path == "LIBRARY/中文 空格" })
        assertTrue(browseSmbServer(address, "fixture", "fixture-password", "", "LIBRARY/中文 空格").isEmpty())
        val error = assertThrows(StorageFailure::class.java) {
            runBlocking { browseSmbServer(address, "fixture", "incorrect", "", "") }
        }
        assertEquals(StorageFailure.Reason.AUTH, error.reason)
    }
}
