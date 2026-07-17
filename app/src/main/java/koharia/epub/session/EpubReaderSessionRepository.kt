package koharia.epub.session

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

    @Synchronized
    fun put(session: EpubReaderSession) {
        paginationSessions.remove(session.chapterId)?.close()
        sessions.put(session.chapterId, session)?.close()
    }

    @Synchronized
    fun putForPagination(session: EpubReaderSession) {
        paginationSessions.put(session.chapterId, session)?.close()
    }

    @Synchronized
    fun remove(chapterId: Long): EpubReaderSession? {
        paginationSessions.remove(chapterId)?.close()
        return sessions.remove(chapterId)
    }
}
