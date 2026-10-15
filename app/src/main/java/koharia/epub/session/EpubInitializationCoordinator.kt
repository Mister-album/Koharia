package koharia.epub.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class EpubInitializationCoordinator {
    private var current: Job? = null
    private var closed = false
    private val mutex = Mutex()

    suspend fun <T> runLatest(block: suspend () -> T): T = coroutineScope {
        val request = currentCoroutineContext().job
        request.ensureActive()
        val previous = synchronized(this@EpubInitializationCoordinator) {
            if (closed) throw CancellationException("EPUB reader closed")
            current.also { current = request }
        }
        previous?.cancel()
        try {
            // Cancellation alone cannot serialize a resolver's non-cancellable cleanup.
            mutex.withLock {
                request.ensureActive()
                block().also { request.ensureActive() }
            }
        } finally {
            synchronized(this@EpubInitializationCoordinator) {
                if (current === request) current = null
            }
        }
    }

    fun close() {
        val request = synchronized(this) {
            closed = true
            current
        }
        request?.cancel()
    }

    suspend fun finish(block: suspend () -> Unit) {
        close()
        mutex.withLock { block() }
    }
}
