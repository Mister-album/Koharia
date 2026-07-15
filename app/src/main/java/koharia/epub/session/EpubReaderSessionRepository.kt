package koharia.epub.session

class EpubReaderSessionRepository {

    private val sessions = mutableMapOf<Long, EpubReaderSession>()

    @Synchronized
    fun get(chapterId: Long): EpubReaderSession? = sessions[chapterId]

    @Synchronized
    fun put(session: EpubReaderSession) {
        sessions.put(session.chapterId, session)?.close()
    }

    @Synchronized
    fun remove(chapterId: Long): EpubReaderSession? = sessions.remove(chapterId)
}
