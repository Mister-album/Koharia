package koharia.storage

import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class SmbAuthenticationTest {
    @Test fun `missing SMB3 session key is a protocol failure without retry or identity fallback`() {
        val failure = NullPointerException().apply {
            stackTrace =
                arrayOf(
                    StackTraceElement(
                        "com.hierynomus.smbj.connection.SMBSessionBuilder",
                        "deriveKey",
                        "SMBSessionBuilder.java",
                        324,
                    ),
                )
        }
        val connection = mockk<Connection>()
        every { connection.negotiatedProtocol } returns mockk {
            every { dialect } returns SMB2Dialect.SMB_3_1_1
        }
        val context = slot<AuthenticationContext>()
        every { connection.authenticate(capture(context)) } throws failure
        every { connection.close() } returns Unit
        val result = assertThrows(StorageFailure::class.java) { connection.authenticateStorage("", "", "") }
        assertEquals(StorageFailure.Reason.PROTOCOL, result.reason)
        assertSame(failure, result.cause)
        assertTrue(context.captured.isAnonymous)
        assertFalse(context.captured.isGuest)
        verify(exactly = 1) { connection.authenticate(any()) }
        verify(exactly = 1) { connection.close() }
    }

    @Test fun `unrelated null pointer is not reclassified as the SMBJ compatibility defect`() {
        val connection = mockk<Connection>()
        val failure = NullPointerException("unrelated")
        every { connection.authenticate(any()) } throws failure
        assertSame(
            failure,
            assertThrows(NullPointerException::class.java) {
                connection.authenticateStorage("user", "canary", "domain")
            },
        )
        verify(exactly = 0) { connection.close() }
    }

    @Test fun `explicit credentials retain their identity and successful connection`() {
        val connection = mockk<Connection>()
        val session = mockk<Session>()
        val context = slot<AuthenticationContext>()
        every { connection.authenticate(capture(context)) } returns session
        assertSame(session, connection.authenticateStorage("user", "canary", "domain"))
        assertEquals("user", context.captured.username)
        assertEquals("domain", context.captured.domain)
        assertEquals("canary", String(context.captured.password))
        assertSame(session, connection.authenticateStorage("Guest", "", ""))
        assertTrue(context.captured.isGuest)
        verify(exactly = 0) { connection.close() }
    }

    @Test fun `Samba anonymous SMB3 reports protocol failure across entry points`() = runBlocking {
        assumeTrue(System.getenv("KOHARIA_SMB_ANONYMOUS_FIXTURE") == "1")
        val address = "smb://127.0.0.1:18449"
        val actions: List<suspend () -> Unit> = listOf(
            { authenticateSmbServer(address, "", "", "") },
            { browseSmbServer(address, "", "", "", "") },
            { SmbStorageBackend("$address/library", "", "", "").use { it.list("") } },
        )
        actions.forEach { action ->
            val error = assertThrows(StorageFailure::class.java) { runBlocking { action() } }
            assertEquals(StorageFailure.Reason.PROTOCOL, error.reason)
        }
        authenticateSmbServer(address, "Guest", "", "")
        SmbStorageBackend("$address/library", "Guest", "", "").use {
            assertTrue(it.list("").any { entry -> entry.path == "Unicode folder" && entry.directory })
        }
    }
}
