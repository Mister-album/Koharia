package koharia.epub.session

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

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

    private fun release(
        sessions: List<EpubReaderSession>,
        onReleased: () -> Unit = {},
    ) {
        sessions.forEach { session ->
            runCatching(session::close)
                .onFailure { error ->
                    logcat(LogPriority.WARN, error) {
                        "Failed to close EPUB session chapterId=${session.chapterId}"
                    }
                }
        }
        notifyReleased(onReleased)
    }

    private fun notifyReleased(onReleased: () -> Unit) {
        runCatching(onReleased)
            .onFailure { error ->
                logcat(LogPriority.WARN, error) { "Failed to finish EPUB session release" }
            }
    }
}
