package koharia.connection

import java.util.concurrent.atomic.AtomicInteger

/** Restoring chapter state must not be mistaken for fresh reading by a network connection. */
object ConnectionRestoreState {
    private val restores = AtomicInteger()
    val isRestoring: Boolean get() = restores.get() > 0

    suspend fun <T> duringRestore(block: suspend () -> T): T {
        restores.incrementAndGet()
        return try {
            block()
        } finally {
            restores.decrementAndGet()
        }
    }
}

interface ConnectionBackupRestoreAdapter {
    suspend fun prepareReadingStateRestore(chapterUrls: List<String>)
}
