package koharia.lanraragi

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/** All instances representing one connection must serialize the whole stage/publish operation. */
class LanraragiCatalogSyncCoordinator {
    private val locks = ConcurrentHashMap<Long, Mutex>()
    fun mutexFor(connectionId: Long): Mutex = locks.computeIfAbsent(connectionId) { Mutex() }
}
