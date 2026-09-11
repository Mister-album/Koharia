package eu.kanade.tachiyomi.ui.more

import koharia.connection.ConnectionAccount
import koharia.connection.ConnectionAccountAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class ConnectionAccountLoadingTest {
    @Test
    fun `draft connection does not construct its network client`() = runTest {
        val adapter = object : ConnectionAccountAdapter {
            override fun hasValidConnection() = false
            override suspend fun getAccount(): ConnectionAccount = error("Draft address must not be used")
        }
        assertNull(loadConnectionAccount(adapter))
        assertNull(loadConnectionAccount(null))
    }

    @Test
    fun `unreachable connection does not crash the more screen`() = runTest {
        assertNull(loadConnectionAccount(adapter { throw IOException("offline") }))
    }

    @Test
    fun `valid account is returned without changing its details`() = runTest {
        val account = ConnectionAccount("LANraragi 0.9.81")
        assertEquals(account, loadConnectionAccount(adapter { account }))
    }

    @Test
    fun `switching connections keeps account refresh cancellable`() {
        assertThrows(CancellationException::class.java) {
            runTest { loadConnectionAccount(adapter { throw CancellationException("Connection switched") }) }
        }
    }

    private fun adapter(load: suspend () -> ConnectionAccount) = object : ConnectionAccountAdapter {
        override fun hasValidConnection() = true
        override suspend fun getAccount() = load()
    }
}
