package koharia.connection

import tachiyomi.domain.chapter.model.Chapter

enum class ConnectionPageProgressDecision { COMPARED, KEEP_LOCAL, USE_REMOTE }

/** The adapter must keep the captured account/runtime; checking after a write alone is insufficient. */
data class ConnectionProgressSession<out T>(val adapter: T, val checkCurrent: suspend () -> Unit)

interface ConnectionPageProgressSessionAdapter : ConnectionPageProgressAdapter, ConnectionLocalPageProgressAdapter {
    suspend fun capturePageProgressSession(
        chapter: Chapter,
    ): ConnectionProgressSession<ConnectionReaderPageProgressAdapter>
}

interface ConnectionReaderPageProgressAdapter : ConnectionPageProgressAdapter, ConnectionLocalPageProgressAdapter {
    suspend fun confirmPageComparison(
        chapterUrl: String,
        snapshot: ConnectionPageProgressSnapshot,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
        decision: ConnectionPageProgressDecision,
        commitChapter: suspend () -> Unit,
    ) {
        when (decision) {
            ConnectionPageProgressDecision.COMPARED -> Unit
            ConnectionPageProgressDecision.KEEP_LOCAL -> confirmLocalPageProgress(
                chapterUrl,
                pageIndex,
                totalPages,
                readAt,
            )
            ConnectionPageProgressDecision.USE_REMOTE -> acceptRemotePageProgress(
                chapterUrl,
                pageIndex,
                totalPages,
                readAt,
            )
        }
        commitChapter()
    }

    /** The callback contains chapter writes only; completion effects run after this returns. */
    suspend fun commitLocalPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
        initialPage: Boolean,
        commitChapter: suspend () -> Unit,
    ) {
        recordLocalPageProgress(chapterUrl, pageIndex, totalPages, readAt, initialPage)
        commitChapter()
    }
}

interface ConnectionEpubProgressSessionAdapter : ConnectionEpubProgressAdapter {
    suspend fun captureEpubProgressSession(chapter: Chapter): ConnectionProgressSession<ConnectionEpubProgressAdapter>
}

/** Null only when this resource has no remote progress capability (for example a native file). */
interface ConnectionReflowProgressSessionAdapter : ConnectionReflowProgressAdapter {
    suspend fun captureReflowProgressSession(
        chapter: Chapter,
    ): ConnectionProgressSession<ConnectionReflowProgressAdapter>?
}
