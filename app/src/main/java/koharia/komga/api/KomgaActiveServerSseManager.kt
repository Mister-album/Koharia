package koharia.komga.api

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.track.komga.KomgaProgressSyncService
import eu.kanade.tachiyomi.network.NetworkHelper
import koharia.source.komga.KomgaServerPreferences
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.Headers
import tachiyomi.domain.source.service.SourceManager

class KomgaActiveServerSseManager(
    application: Application,
    networkHelper: NetworkHelper,
    private val sourceManager: SourceManager,
    private val komgaServerPreferences: KomgaServerPreferences,
    komgaProgressSyncService: Lazy<KomgaProgressSyncService>,
) {

    private val lifecycleScope = ProcessLifecycleOwner.get().lifecycleScope
    private val sseClient = KomgaSseClient(
        context = application,
        networkHelper = networkHelper,
        komgaProgressSyncService = komgaProgressSyncService,
        baseUrlProvider = { currentSource()?.baseUrl.orEmpty() },
        headersProvider = { currentSource()?.currentHeaders() ?: Headers.Builder().build() },
    )

    init {
        sseClient.start(lifecycleScope)
        lifecycleScope.launch {
            komgaServerPreferences.activeServerId.changes().drop(1).collectLatest {
                sseClient.reconnect()
            }
        }
    }

    private fun currentSource(): KomgaSource? {
        return sourceManager.get(komgaServerPreferences.activeServerId.get()) as? KomgaSource
    }
}
