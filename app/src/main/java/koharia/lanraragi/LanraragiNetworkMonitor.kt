package koharia.lanraragi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One process-wide callback, rather than consuming an Android network request for every library. */
class LanraragiNetworkMonitor(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val connected = MutableStateFlow(connectivity.activeNetwork != null)
    val available = connected.asStateFlow()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            connected.value = true
        }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            connected.value = connectivity.activeNetwork != null
        }
        override fun onLost(network: Network) {
            connected.value = connectivity.activeNetwork?.let { it != network } == true
        }
    }

    init {
        connectivity.registerDefaultNetworkCallback(callback)
    }
}
