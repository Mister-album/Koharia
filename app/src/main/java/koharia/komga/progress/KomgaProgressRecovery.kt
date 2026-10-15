package koharia.komga.progress

import koharia.connection.ConnectionIdentityChanges
import koharia.connection.ConnectionRestoreState
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaReadStatusKey
import koharia.source.komga.KomgaSource
import koharia.source.komga.captureKomgaProgressSession
import kotlinx.coroutines.flow.first
import tachiyomi.domain.source.service.SourceManager
import java.io.IOException

/** Recover the gap between a committed journal and a persisted WorkManager wakeup. */
internal class KomgaProgressRecovery(
    private val pages: KomgaPageEventRepository,
    private val sources: SourceManager,
    private val identities: ConnectionIdentityChanges,
    private val wake: suspend (KomgaReadStatusKey) -> Unit,
) {
    suspend fun recover(): Int {
        checkRestore()
        sources.isInitialized.first { it }
        val keys = pages.recoveryKeys()
        var count = 0
        for (key in keys) {
            checkRestore()
            if (key.ownerKey.startsWith("restored:") || identities.isPaused(key.connectionId)) continue
            val source = sources.get(key.connectionId) as? KomgaSource ?: continue
            val session = captureKomgaProgressSession(source, sources)
            if (session.account.key != key.ownerKey) continue
            session.guard.requireCurrent()
            wake(key)
            count++
        }
        return count
    }

    private fun checkRestore() {
        if (ConnectionRestoreState.isRestoring) throw IOException("Connection restore in progress")
    }
}
