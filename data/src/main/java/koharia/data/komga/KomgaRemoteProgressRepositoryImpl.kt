package koharia.data.komga

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.domain.epub.repository.EpubProgressRepository
import koharia.domain.epub.repository.EpubRemoteProgressCacheRepository
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository
import koharia.domain.komga.KomgaRemoteChapterSnapshot
import koharia.domain.komga.KomgaRemoteProgressChange
import koharia.domain.komga.KomgaRemoteProgressRepository
import koharia.domain.komga.KomgaRemoteProgressSnapshot
import tachiyomi.data.Database
import tachiyomi.data.history.HistoryMapper
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository

class KomgaRemoteProgressRepositoryImpl(
    private val database: Database,
    private val chapters: ChapterRepository,
    private val epub: EpubProgressRepository,
    private val remoteEpub: EpubRemoteProgressCacheRepository,
    private val history: HistoryRepository,
    private val intents: KomgaReadStatusRepository,
    private val pages: KomgaPageEventRepository,
    private val bookId: (String) -> String?,
) : KomgaRemoteProgressRepository {
    override suspend fun captureChapter(
        key: KomgaReadStatusKey,
        chapterId: Long,
        checkCurrent: suspend () -> Unit,
    ): KomgaRemoteProgressSnapshot = database.transactionWithResult {
        checkCurrent()
        val chapter = chapters.getChapterById(chapterId)?.takeIf {
            database.mangasQueries.getMangaById(it.mangaId).awaitAsOneOrNull()?.source == key.connectionId &&
                bookId(it.url) == key.bookId
        }
        val state = chapter?.let { mapOf(chapterId to captureState(it, key.bookId)) }.orEmpty()
        val ownerIntents = listOfNotNull(intents.get(key)).associateBy { it.key.bookId }
        val pageStates = pages.states(key.connectionId, key.ownerKey, listOf(key.bookId))
        val attempts = intents.attemptsForBooks(key.connectionId, key.ownerKey, listOf(key.bookId))
        checkCurrent()
        KomgaRemoteProgressSnapshot(key.connectionId, key.ownerKey, state, ownerIntents, pageStates, attempts)
    }

    private suspend fun captureState(chapter: Chapter, bookId: String) = KomgaRemoteChapterSnapshot(
        chapter,
        epub.getProgress(chapter.id),
        remoteEpub.getByChapterId(chapter.id),
        database.historyQueries.getByChapterId(chapter.id, HistoryMapper::mapHistory).awaitAsOneOrNull(),
        bookId,
    )

    override suspend fun captureBook(
        key: KomgaReadStatusKey,
        bookUrl: String,
        checkCurrent: suspend () -> Unit,
    ): KomgaRemoteProgressSnapshot = database.transactionWithResult {
        checkCurrent()
        val aliases = chapters.getChaptersByUrlAndSourceId(bookUrl, key.connectionId)
            .filter { bookId(it.url) == key.bookId }
        val snapshots = aliases.associate { chapter -> chapter.id to captureState(chapter, key.bookId) }
        val ownerIntents = listOfNotNull(intents.get(key)).associateBy { it.key.bookId }
        val pageStates = pages.states(key.connectionId, key.ownerKey, listOf(key.bookId))
        val attempts = intents.attemptsForBooks(key.connectionId, key.ownerKey, listOf(key.bookId))
        checkCurrent()
        KomgaRemoteProgressSnapshot(key.connectionId, key.ownerKey, snapshots, ownerIntents, pageStates, attempts)
    }

    override suspend fun capture(
        connectionId: Long,
        ownerKey: String,
        mangaIds: Collection<Long>,
        checkCurrent: suspend () -> Unit,
    ): KomgaRemoteProgressSnapshot = database.transactionWithResult {
        checkCurrent()
        val snapshots = buildMap {
            mangaIds.distinct().forEach { mangaId ->
                if (database.mangasQueries.getMangaById(mangaId).awaitAsOneOrNull()?.source != connectionId) {
                    return@forEach
                }
                val localBooks = epub.getProgressesByMangaId(mangaId).associateBy { it.chapterId }
                val remoteBooks = remoteEpub.getByMangaId(mangaId).associateBy { it.chapterId }
                val histories = history.getHistoryByMangaId(mangaId).associateBy { it.chapterId }
                chapters.getChapterByMangaId(mangaId).forEach chapter@{ chapter ->
                    val stableBookId = bookId(chapter.url) ?: return@chapter
                    put(
                        chapter.id,
                        KomgaRemoteChapterSnapshot(
                            chapter,
                            localBooks[chapter.id],
                            remoteBooks[chapter.id],
                            histories[chapter.id],
                            stableBookId,
                        ),
                    )
                }
            }
        }
        val ownerIntents = intents.forOwner(connectionId, ownerKey).associateBy { it.key.bookId }
        val pageStates = pages.states(connectionId, ownerKey, snapshots.values.map { it.bookId })
        val attempts = intents.attemptsForBooks(connectionId, ownerKey, snapshots.values.map { it.bookId })
        checkCurrent()
        KomgaRemoteProgressSnapshot(connectionId, ownerKey, snapshots, ownerIntents, pageStates, attempts)
    }

    override suspend fun apply(
        snapshot: KomgaRemoteProgressSnapshot,
        changes: List<KomgaRemoteProgressChange>,
        checkCurrent: suspend () -> Unit,
    ): Set<Long> = database.transactionWithResult {
        checkCurrent()
        require(changes.map { it.chapterId }.distinct().size == changes.size)
        val currentPages = pages.states(snapshot.connectionId, snapshot.ownerKey, changes.map { it.bookId })
        val currentAttempts = intents.attemptsForBooks(
            snapshot.connectionId,
            snapshot.ownerKey,
            changes.map {
                it.bookId
            },
        )
        val applied = mutableSetOf<Long>()
        changes.forEach { change ->
            val expected = snapshot.chapters[change.chapterId] ?: return@forEach
            val chapter = expected.chapter
            if (change.bookId != expected.bookId) return@forEach
            val key = KomgaReadStatusKey(snapshot.connectionId, snapshot.ownerKey, change.bookId)
            val expectedPages = snapshot.pages[change.bookId] ?: return@forEach
            if (!expectedPages.settled || currentPages[change.bookId] != expectedPages) return@forEach
            if (!snapshot.attempts.containsKey(change.bookId) || snapshot.attempts[change.bookId] != null ||
                currentAttempts[change.bookId] != null
            ) {
                return@forEach
            }
            val expectedIntent = snapshot.intents[change.bookId]
            if (expectedIntent != null && expectedIntent.phase != KomgaReadStatusPhase.SYNCED) return@forEach
            if (intents.get(key) != expectedIntent) return@forEach
            if (database.mangasQueries.getMangaById(chapter.mangaId).awaitAsOneOrNull()?.source !=
                snapshot.connectionId
            ) {
                return@forEach
            }
            if (chapters.getChapterById(chapter.id) != chapter ||
                epub.getProgress(chapter.id) != expected.epub ||
                remoteEpub.getByChapterId(chapter.id) != expected.remoteEpub ||
                database.historyQueries.getByChapterId(chapter.id, HistoryMapper::mapHistory).awaitAsOneOrNull() !=
                expected.history
            ) {
                return@forEach
            }

            change.epubCache?.let { cache ->
                require(cache.chapterId == chapter.id && cache.mangaId == chapter.mangaId)
                require(cache.bookUrl == chapter.url.substringBefore('#').removeSuffix("/"))
            }
            if (change.read != null || change.lastPageRead != null) {
                chapters.update(ChapterUpdate(chapter.id, read = change.read, lastPageRead = change.lastPageRead))
            }
            change.readAt?.let { history.upsertRemoteHistory(chapter.id, it) }
            change.epubCache?.let { remoteEpub.upsert(it) }
            applied.add(chapter.id)
        }
        checkCurrent()
        applied
    }
}
