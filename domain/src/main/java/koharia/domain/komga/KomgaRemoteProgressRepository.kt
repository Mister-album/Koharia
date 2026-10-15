package koharia.domain.komga

import koharia.domain.epub.model.EpubProgress
import koharia.domain.epub.model.EpubRemoteProgressCache
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History
import java.util.Date

data class KomgaRemoteChapterSnapshot(
    val chapter: Chapter,
    val epub: EpubProgress?,
    val remoteEpub: EpubRemoteProgressCache?,
    val history: History?,
    val bookId: String,
)

data class KomgaRemoteProgressSnapshot(
    val connectionId: Long,
    val ownerKey: String,
    val chapters: Map<Long, KomgaRemoteChapterSnapshot>,
    val intents: Map<String, KomgaReadStatusIntent>,
    val pages: Map<String, KomgaPageState>,
    val attempts: Map<String, KomgaReadStatusAttempt?>,
)

data class KomgaRemoteProgressChange(
    val chapterId: Long,
    val bookId: String,
    val read: Boolean?,
    val lastPageRead: Long?,
    val readAt: Date?,
    val epubCache: EpubRemoteProgressCache?,
)

interface KomgaRemoteProgressRepository {
    suspend fun captureChapter(
        key: KomgaReadStatusKey,
        chapterId: Long,
        checkCurrent: suspend () -> Unit,
    ): KomgaRemoteProgressSnapshot

    suspend fun captureBook(
        key: KomgaReadStatusKey,
        bookUrl: String,
        checkCurrent: suspend () -> Unit,
    ): KomgaRemoteProgressSnapshot

    /** Capture before the remote request; a cached response must retain its original snapshot. */
    suspend fun capture(
        connectionId: Long,
        ownerKey: String,
        mangaIds: Collection<Long>,
        checkCurrent: suspend () -> Unit,
    ): KomgaRemoteProgressSnapshot

    /** Atomically apply unchanged resources, skipping concurrent reader/explicit/history/EPUB changes. */
    suspend fun apply(
        snapshot: KomgaRemoteProgressSnapshot,
        changes: List<KomgaRemoteProgressChange>,
        checkCurrent: suspend () -> Unit,
    ): Set<Long>

    /** Validate a cached/fallback result under the same transaction rules without writing any state. */
    suspend fun isCurrent(
        snapshot: KomgaRemoteProgressSnapshot,
        chapterId: Long,
        bookId: String,
        checkCurrent: suspend () -> Unit,
    ): Boolean = chapterId in apply(
        snapshot,
        listOf(KomgaRemoteProgressChange(chapterId, bookId, null, null, null, null)),
        checkCurrent,
    )
}
