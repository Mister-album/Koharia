package koharia.connection

import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ConnectionSourceSession private constructor(
    val ownerKey: String,
    private val validate: () -> Unit,
) {
    suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        validate()
    }

    companion object {
        fun capture(source: Source, sourceManager: SourceManager = Injekt.get()): ConnectionSourceSession {
            val registered = sourceManager.get(source.id)
            if (registered != null && registered !== source) throw CancellationException("Connection source replaced")
            val owner = (source as? ConnectionChapterSessionAdapter)?.captureChapterOwner()
                ?: run {
                    check(source !is ConnectionSource) { "Connection has no source session adapter" }
                    ConnectionChapterOwner("source:${source.id}") {}
                }
            val validate = {
                if (ConnectionRestoreState.isRestoring || sourceManager.get(source.id) !== registered) {
                    throw CancellationException("Connection source replaced")
                }
                owner.checkCurrent()
            }
            validate()
            return ConnectionSourceSession(owner.key, validate)
        }
    }
}
