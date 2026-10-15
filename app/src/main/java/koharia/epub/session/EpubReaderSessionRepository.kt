package koharia.epub.session

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.atomic.AtomicInteger

class EpubReaderSessionRepository {

    private val sessions = mutableMapOf<Long, EpubReaderSession>()
    private val paginationSessions = mutableMapOf<Long, EpubReaderSession>()

    @Synchronized
    fun get(chapterId: Long): EpubReaderSession? = sessions[chapterId]

    @Synchronized
    fun getForPagination(chapterId: Long): EpubReaderSession? =
        paginationSessions[chapterId] ?: sessions[chapterId]

    @Synchronized
    fun hasDedicatedPaginationSession(chapterId: Long): Boolean = paginationSessions.containsKey(chapterId)

    fun put(session: EpubReaderSession) {
        val replacedSessions = synchronized(this) {
            listOfNotNull(
                paginationSessions.remove(session.chapterId),
                sessions.put(session.chapterId, session),
            )
        }
        release(replacedSessions)
    }

    fun putForPagination(session: EpubReaderSession) {
        val replacedSession = synchronized(this) {
            paginationSessions.put(session.chapterId, session)
        }
        release(listOfNotNull(replacedSession))
    }

    fun remove(chapterId: Long, onReleased: () -> Unit = {}) {
        val removedSessions = synchronized(this) {
            listOfNotNull(
                paginationSessions.remove(chapterId),
                sessions.remove(chapterId),
            )
        }
        release(removedSessions, onReleased)
    }

    fun removeIfCurrent(session: EpubReaderSession, onReleased: () -> Unit = {}): Boolean {
        val removedSessions = synchronized(this) {
            if (sessions[session.chapterId] !== session) return false
            listOfNotNull(
                paginationSessions.remove(session.chapterId),
                sessions.remove(session.chapterId),
            )
        }
        release(removedSessions, onReleased)
        return true
    }

    private fun release(
        sessions: List<EpubReaderSession>,
        onReleased: () -> Unit = {},
    ) {
        val remaining = AtomicInteger(sessions.size + 1)
        val released = { if (remaining.decrementAndGet() == 0) notifyReleased(onReleased) }
        sessions.forEach { session ->
            runCatching { session.close(released) }
                .onFailure { error ->
                    logcat(LogPriority.WARN, error) {
                        "Failed to close EPUB session chapterId=${session.chapterId}"
                    }
                }
        }
        released()
    }

    private fun notifyReleased(onReleased: () -> Unit) {
        runCatching(onReleased)
            .onFailure { error ->
                logcat(LogPriority.WARN, error) { "Failed to finish EPUB session release" }
            }
    }
}
