package koharia.connection

import kotlinx.serialization.json.JsonObject

/** Delegates must already own a fixed account/runtime; this gate cannot make a dynamic Source safe. */
internal class CapturedPageProgress(
    private val chapterUrl: String,
    private val progress: ConnectionReaderPageProgressAdapter,
    private val checkCurrent: suspend () -> Unit,
) : ConnectionReaderPageProgressAdapter {
    private suspend fun <T> checked(url: String, operation: suspend () -> T): T {
        require(url == chapterUrl) { "Reading resource changed" }
        checkCurrent()
        return operation().also { checkCurrent() }
    }

    override suspend fun beginReadingSession(chapterUrl: String) = checked(chapterUrl) {
        progress.beginReadingSession(chapterUrl)
    }

    override suspend fun pullPageProgress(chapterUrl: String, chapterMemo: JsonObject) = checked(chapterUrl) {
        progress.pullPageProgress(chapterUrl, chapterMemo)
    }

    override suspend fun pushPageProgress(chapterUrl: String, pageIndex: Int, totalPages: Int) = checked(chapterUrl) {
        progress.pushPageProgress(chapterUrl, pageIndex, totalPages)
    }

    override suspend fun recordLocalPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
        initialPage: Boolean,
    ) = checked(chapterUrl) {
        progress.recordLocalPageProgress(chapterUrl, pageIndex, totalPages, readAt, initialPage)
    }

    override suspend fun confirmLocalPageProgress(chapterUrl: String, pageIndex: Int, totalPages: Int, readAt: Long) =
        checked(chapterUrl) { progress.confirmLocalPageProgress(chapterUrl, pageIndex, totalPages, readAt) }

    override suspend fun commitLocalPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
        initialPage: Boolean,
        commitChapter: suspend () -> Unit,
    ) = checked(chapterUrl) {
        progress.commitLocalPageProgress(chapterUrl, pageIndex, totalPages, readAt, initialPage) {
            checkCurrent()
            commitChapter()
            checkCurrent()
        }
    }

    override suspend fun confirmPageComparison(
        chapterUrl: String,
        snapshot: ConnectionPageProgressSnapshot,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
        decision: ConnectionPageProgressDecision,
        commitChapter: suspend () -> Unit,
    ) = checked(chapterUrl) {
        progress.confirmPageComparison(chapterUrl, snapshot, pageIndex, totalPages, readAt, decision) {
            checkCurrent()
            commitChapter()
            checkCurrent()
        }
    }

    override suspend fun acceptRemotePageProgress(chapterUrl: String, pageIndex: Int, totalPages: Int, readAt: Long) =
        checked(chapterUrl) { progress.acceptRemotePageProgress(chapterUrl, pageIndex, totalPages, readAt) }
}
