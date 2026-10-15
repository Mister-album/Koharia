package koharia.komga.progress

import koharia.komga.download.KomgaChapterMemo
import koharia.source.komga.KomgaProgressSession
import koharia.source.komga.KomgaSource
import koharia.source.komga.captureKomgaProgressSession
import uy.kohesive.injekt.api.get

internal suspend fun captureKomgaPageProgressSession(
    source: KomgaSource,
    chapterUrl: String,
    sourceManager: tachiyomi.domain.source.service.SourceManager,
    service: eu.kanade.tachiyomi.data.track.komga.KomgaProgressSyncService,
    ordering: KomgaProgressOrdering,
    journal: KomgaPageJournal,
): koharia.connection.ConnectionProgressSession<koharia.connection.ConnectionReaderPageProgressAdapter> {
    val captured = capturePageProgress(source, chapterUrl, sourceManager, service, ordering, journal)
    return koharia.connection.ConnectionProgressSession(
        koharia.connection.CapturedPageProgress(chapterUrl, captured.adapter, captured.checkCurrent),
        captured.checkCurrent,
    )
}

internal suspend fun captureKomgaReflowProgressSession(
    source: KomgaSource,
    chapter: tachiyomi.domain.chapter.model.Chapter,
    sourceManager: tachiyomi.domain.source.service.SourceManager,
    service: eu.kanade.tachiyomi.data.track.komga.KomgaProgressSyncService,
    ordering: KomgaProgressOrdering,
    journal: KomgaPageJournal,
): koharia.connection.ConnectionProgressSession<koharia.connection.ConnectionReflowProgressAdapter> {
    val captured = capturePageProgress(source, chapter.url, sourceManager, service, ordering, journal)
    return koharia.connection.ConnectionProgressSession(
        PdfProgress(chapter.url, captured.adapter, captured.checkCurrent),
        captured.checkCurrent,
    )
}

private suspend fun capturePageProgress(
    source: KomgaSource,
    chapterUrl: String,
    sourceManager: tachiyomi.domain.source.service.SourceManager,
    service: eu.kanade.tachiyomi.data.track.komga.KomgaProgressSyncService,
    ordering: KomgaProgressOrdering,
    journal: KomgaPageJournal,
): koharia.connection.ConnectionProgressSession<PageProgress> {
    val session = captureKomgaProgressSession(source, sourceManager)
    session.account.requireDestination(okhttp3.Request.Builder().url(chapterUrl).build())
    val checkOwner = {
        session.guard.requireCurrent()
        if (koharia.connection.ConnectionRestoreState.isRestoring) throw java.io.IOException("Restore in progress")
    }
    val token = ordering.capture(
        koharia.komga.progress.KomgaProgressOrdering.key(source.id, session.account.key, chapterUrl),
    )
    val checkCurrent: suspend () -> Unit = {
        checkOwner()
        ordering.checkCurrent(token)
    }
    checkCurrent()
    return koharia.connection.ConnectionProgressSession(
        PageProgress(session, ordering, token, checkCurrent, service, journal, checkOwner),
        checkCurrent,
    )
}
private class PageProgress(
    private val session: KomgaProgressSession,
    private val ordering: koharia.komga.progress.KomgaProgressOrdering,
    private val token: koharia.komga.progress.KomgaProgressRevision,
    private val checkCurrent: suspend () -> Unit,
    private val service: eu.kanade.tachiyomi.data.track.komga.KomgaProgressSyncService,
    private val journal: KomgaPageJournal,
    private val checkOwner: () -> Unit,
) : koharia.connection.ConnectionReaderPageProgressAdapter {
    @Volatile private var acceptedBaseline: String? = null

    @Volatile private var comparison: Comparison? = null

    private data class Comparison(
        val snapshot: koharia.connection.ConnectionPageProgressSnapshot,
        val local: koharia.domain.komga.KomgaPageState,
        val baseline: KomgaProgressBaseline,
    )

    override suspend fun pullPageProgress(
        chapterUrl: String,
        chapterMemo: kotlinx.serialization.json.JsonObject,
    ): koharia.connection.ConnectionPageProgressSnapshot? {
        val (local, remote) = ordering.remote(token, checkOwner, allowPageAttemptRecovery = true) {
            journal.state(token.key) to service.pullBookProgress(session, chapterUrl, checkCurrent)
        }
        val previousPublicationVersion = KomgaChapterMemo.publicationVersion(chapterMemo)
        val updatedMemo = KomgaChapterMemo.mergePublicationMetadata(
            existing = chapterMemo,
            bookUrl = remote.url,
            fileHash = remote.fileHash,
            fileLastModified = remote.fileLastModified,
            sizeBytes = remote.sizeBytes,
            fileName = remote.fileName,
            isEpub = remote.isEpub,
            epubDivinaCompatible = remote.isDivinaCompatibleEpub.takeIf { remote.isEpub },
            pagesCount = remote.totalPages,
        )
        val snapshot = koharia.connection.ConnectionPageProgressSnapshot(
            resourceId = remote.url,
            pageIndex = remote.pageIndex,
            totalPages = remote.totalPages,
            completed = remote.completed,
            readDate = remote.readDate,
            isEpub = remote.isEpub,
            canOpenAsPages = remote.isEpub && KomgaChapterMemo.canOpenEpubAsPages(updatedMemo),
            updatedChapterMemo = updatedMemo,
            previousPublicationVersion = previousPublicationVersion,
            publicationVersion = KomgaChapterMemo.publicationVersion(updatedMemo),
            blocksAutomaticSelection = !local.settled,
            uncertainRequest = local.attempt != null,
            comparisonVersion = java.util.UUID.randomUUID().toString(),
        )
        checkCurrent()
        comparison = Comparison(snapshot, local, KomgaProgressBaseline.from(remote))
        return snapshot
    }

    suspend fun isPageConfirmed(page: Int, total: Int, readAt: Long): Boolean =
        try {
            ordering.remote(token, checkOwner) {
                val local = journal.state(token.key)
                val event = local.event
                local.attempt == null && event != null &&
                    event.phase == koharia.domain.komga.KomgaReadStatusPhase.SYNCED &&
                    event.supersededByRevision == null && event.revision == token.revision &&
                    event.page == page.toLong() && event.total == total.toLong() && event.readAt == readAt
            }
        } catch (_: KomgaProgressOnHold) {
            false
        }

    override suspend fun pushPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
    ) {
        checkCurrent()
        journal.wake(token.key)
    }

    override suspend fun acceptRemotePageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
    ) {
        throw java.io.IOException("Komga requires the compared progress snapshot")
    }

    override suspend fun confirmLocalPageProgress(chapterUrl: String, pageIndex: Int, totalPages: Int, readAt: Long) {
        throw java.io.IOException("Komga requires the compared progress snapshot")
    }

    override suspend fun confirmPageComparison(
        chapterUrl: String,
        snapshot: koharia.connection.ConnectionPageProgressSnapshot,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
        decision: koharia.connection.ConnectionPageProgressDecision,
        commitChapter: suspend () -> Unit,
    ) {
        checkCurrent()
        val captured = comparison?.takeIf { it.snapshot === snapshot }
            ?: throw java.io.IOException("Komga progress comparison expired")
        val checkReviewOwner = {
            checkOwner()
            if (uy.kohesive.injekt.Injekt.get<koharia.connection.ConnectionIdentityChanges>()
                    .isPaused(session.sourceId)
            ) {
                throw koharia.connection.ConnectionWritesPaused()
            }
        }
        journal.compare(
            token, captured.local, captured.baseline, pageIndex, totalPages, readAt, decision,
            checkReviewOwner,
            verifyRemote = {
                if (decision != koharia.connection.ConnectionPageProgressDecision.COMPARED) {
                    val current = service.pullBookProgress(session, chapterUrl, checkCurrent)
                    if (KomgaProgressBaseline.from(current) != captured.baseline) {
                        throw java.io.IOException("Komga server progress changed; review again")
                    }
                }
            },
            commitChapter = commitChapter,
        )
        acceptedBaseline = captured.baseline.encode()
    }

    override suspend fun commitLocalPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
        initialPage: Boolean,
        commitChapter: suspend () -> Unit,
    ) {
        checkCurrent()
        journal.record(
            token,
            acceptedBaseline,
            pageIndex,
            totalPages,
            readAt,
            initialPage,
            checkOwner,
            commitChapter,
        )
    }

    override suspend fun recordLocalPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
        initialPage: Boolean,
    ) {
        commitLocalPageProgress(chapterUrl, pageIndex, totalPages, readAt, initialPage) {}
    }
}

/** Keep the provider's compared snapshot and journal CAS when the UI uses reflowable locators. */
private class PdfProgress(
    private val resource: String,
    private val pages: PageProgress,
    private val checkCurrent: suspend () -> Unit,
) : koharia.connection.ConnectionReflowProgressAdapter,
    koharia.connection.ConnectionReaderPageProgressAdapter by koharia.connection.CapturedPageProgress(
        resource,
        pages,
        checkCurrent,
    ) {
    private data class Review(
        val original: koharia.connection.ConnectionPageProgressSnapshot,
        val presented: koharia.connection.ConnectionPageProgressSnapshot,
    )
    private var pulled: Review? = null
    private var reviewed: Review? = null
    private var baselineReady = false

    private suspend fun <T> checked(url: String, action: suspend () -> T): T {
        require(url == resource) { "Reading resource changed" }
        checkCurrent()
        return action().also { checkCurrent() }
    }

    private suspend fun read(memo: kotlinx.serialization.json.JsonObject): Review {
        val snapshot = checkNotNull(pages.pullPageProgress(resource, memo))
        val oldTotal = KomgaChapterMemo.pagesCount(memo)
        val changed = snapshot.isEpub ||
            (oldTotal != null && oldTotal > 0 && oldTotal != snapshot.totalPages) ||
            (
                snapshot.previousPublicationVersion != null &&
                    snapshot.previousPublicationVersion != snapshot.publicationVersion
                )
        return Review(snapshot, snapshot.copy(requiresPageMappingConfirmation = changed))
    }

    override suspend fun pullPageProgress(chapterUrl: String, chapterMemo: kotlinx.serialization.json.JsonObject) =
        checked(chapterUrl) { read(chapterMemo).also { pulled = it }.presented }

    override suspend fun reviewPageProgress(chapterUrl: String, chapterMemo: kotlinx.serialization.json.JsonObject) =
        checked(chapterUrl) { read(chapterMemo).also { reviewed = it }.presented }

    private fun requireMapping(review: Review, total: Int) {
        check(!review.presented.requiresPageMappingConfirmation && review.presented.totalPages == total) {
            "PDF page mapping changed"
        }
    }

    private fun snapshotReadAt(snapshot: koharia.connection.ConnectionPageProgressSnapshot) = snapshot.readDate?.let {
        runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
    } ?: 0L

    override suspend fun recordLocalPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
        initialPage: Boolean,
    ) = checked(chapterUrl) {
        val comparison = pulled
        if (!baselineReady && comparison != null &&
            !comparison.presented.blocksAutomaticSelection && !comparison.presented.uncertainRequest
        ) {
            requireMapping(comparison, totalPages)
            val snapshot = comparison.original
            pages.confirmPageComparison(
                resource,
                snapshot,
                snapshot.pageIndex ?: if (snapshot.completed) totalPages - 1 else 0,
                totalPages,
                snapshotReadAt(snapshot),
                koharia.connection.ConnectionPageProgressDecision.COMPARED,
            ) {}
            baselineReady = true
        }
        // Without a compared baseline, ordinary journal recording retains a held offline intention.
        pages.recordLocalPageProgress(resource, pageIndex, totalPages, readAt, initialPage)
    }

    override suspend fun acceptRemotePageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
    ) = resolve(chapterUrl, pageIndex, totalPages, readAt, useRemote = true)

    override suspend fun confirmLocalPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
    ) = resolve(chapterUrl, pageIndex, totalPages, readAt, useRemote = false)

    private suspend fun resolve(url: String, page: Int, total: Int, time: Long, useRemote: Boolean) = checked(url) {
        val comparison = checkNotNull(reviewed) { "PDF progress was not reviewed" }
        requireMapping(comparison, total)
        if (useRemote) {
            require(page == (comparison.original.pageIndex ?: if (comparison.original.completed) total - 1 else 0))
            require(time == snapshotReadAt(comparison.original))
        }
        pages.confirmPageComparison(
            resource,
            comparison.original,
            page,
            total,
            time,
            if (useRemote) {
                koharia.connection.ConnectionPageProgressDecision.USE_REMOTE
            } else {
                koharia.connection.ConnectionPageProgressDecision.KEEP_LOCAL
            },
        ) {}
        baselineReady = true
        reviewed = null
    }

    override suspend fun isPageProgressConfirmed(chapterUrl: String, page: Int, total: Int, modifiedAt: Long) =
        checked(chapterUrl) { pages.isPageConfirmed(page, total, modifiedAt) }
}
