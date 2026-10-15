package koharia.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionNetworkMonitorTest {
    @Test
    fun foregroundUnblockRestoresAvailabilityWithoutNetworkReplacement() {
        val connectivity = mockk<ConnectivityManager>()
        val context = mockk<Context>()
        val network = mockk<Network>()
        val callback = slot<ConnectivityManager.NetworkCallback>()
        every { context.applicationContext } returns context
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivity
        every { connectivity.activeNetwork } returns null
        every { connectivity.registerDefaultNetworkCallback(capture(callback)) } just runs
        val monitor = ConnectionNetworkMonitor(context)
        assertFalse(monitor.available.value)

        every { connectivity.activeNetwork } returns network
        callback.captured.onBlockedStatusChanged(network, false)

        assertTrue(monitor.available.value)
    }

    @Test
    fun blockAndObsoleteUnblockUseCurrentUidNetworkAvailability() {
        val connectivity = mockk<ConnectivityManager>()
        val context = mockk<Context>()
        val network = mockk<Network>()
        val callback = slot<ConnectivityManager.NetworkCallback>()
        every { context.applicationContext } returns context
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivity
        every { connectivity.activeNetwork } returns network
        every { connectivity.registerDefaultNetworkCallback(capture(callback)) } just runs
        val monitor = ConnectionNetworkMonitor(context)
        assertTrue(monitor.available.value)

        every { connectivity.activeNetwork } returns null
        callback.captured.onBlockedStatusChanged(network, true)
        assertFalse(monitor.available.value)
        callback.captured.onBlockedStatusChanged(network, false)
        assertFalse(monitor.available.value)

        every { connectivity.activeNetwork } returns mockk()
        callback.captured.onBlockedStatusChanged(network, true)
        assertTrue(monitor.available.value)
    }
}
