package koharia.komga.progress

import eu.kanade.tachiyomi.data.track.komga.KomgaApi
import koharia.source.komga.KomgaProgressSession

internal class KomgaApiPageTransport(
    private val api: KomgaApi,
    private val session: KomgaProgressSession,
    private val chapterUrl: String,
    private val checkCurrent: suspend () -> Unit,
) : KomgaReadStatusTransport {
    override suspend fun read() = KomgaProgressBaseline.from(api.getBookProgress(chapterUrl, session, checkCurrent))

    override suspend fun write(page: Int, completed: Boolean) =
        api.updateBookProgress(session, chapterUrl, page, completed, checkCurrent)

    override suspend fun mark(read: Boolean) = api.setBookReadStatus(session, chapterUrl, read, checkCurrent)
}
