package koharia.komga.progress

import eu.kanade.tachiyomi.data.track.komga.KomgaApi
import eu.kanade.tachiyomi.network.HttpException
import koharia.connection.ConnectionIdentityChanges
import koharia.connection.ConnectionReadStatus
import koharia.connection.ConnectionRestoreState
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaReadStatusChapter
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository
import koharia.source.komga.KomgaSource
import koharia.source.komga.captureKomgaProgressSession
import kotlinx.coroutines.CancellationException
import tachiyomi.data.Database
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.source.service.SourceManager
import java.io.IOException

internal class KomgaExplicitProgress(
    private val database: Database,
    private val intents: KomgaReadStatusRepository,
    private val pages: KomgaPageEventRepository,
    private val ordering: KomgaProgressOrdering,
    private val chapters: ChapterRepository,
    private val sources: SourceManager,
    private val identities: ConnectionIdentityChanges,
    private val api: KomgaApi,
    private val journal: KomgaPageJournal,
) {
    suspend fun review(source: KomgaSource, url: String): koharia.connection.ConnectionReadStatusReview? {
        val session = captureKomgaProgressSession(source, sources)
        session.account.requireDestination(okhttp3.Request.Builder().url(url).build())
        val key = KomgaProgressOrdering.key(source.id, session.account.key, url)
        val checkOwner = {
            session.guard.requireCurrent()
            if (identities.isPaused(source.id)) throw koharia.connection.ConnectionWritesPaused()
            if (ConnectionRestoreState.isRestoring) throw IOException("Restore in progress")
        }
        val transport = KomgaApiPageTransport(api, session, url) { checkOwner() }
        val review = KomgaReadStatusReview(database, intents, pages, ordering)
        val aliases: suspend () -> List<Chapter> = { chapters.getChaptersByUrlAndSourceId(url, source.id) }
        val comparison = review.compare(key, transport, checkOwner, aliases) ?: return null
        return koharia.connection.ConnectionReadStatusReview(
            localRead = checkNotNull(comparison.local.intent).read,
            remoteRead = comparison.remote.completed,
            remoteStarted = comparison.remote.progressPresent,
            uncertainRequest = comparison.local.attempt != null || comparison.local.pages.attempt != null,
            reissue = {
                review.reissue(comparison, transport, checkOwner, aliases)
                journal.wake(key)
            },
        )
    }

    suspend fun commit(source: KomgaSource, url: String, read: Boolean, chapter: Chapter?): ConnectionReadStatus {
        val session = captureKomgaProgressSession(source, sources)
        session.account.requireDestination(okhttp3.Request.Builder().url(url).build())
        val key = KomgaProgressOrdering.key(source.id, session.account.key, url)
        val checkOwner = {
            session.guard.requireCurrent()
            if (ConnectionRestoreState.isRestoring) throw IOException("Restore in progress")
        }
        val intent = database.transactionWithResult {
            checkOwner()
            val aliases = chapters.getChaptersByUrlAndSourceId(url, source.id)
            check(chapter == null || aliases.any { it.id == chapter.id && it.mangaId == chapter.mangaId })
            val previous = intents.get(key)
            val page = pages.get(key)
            val pageRequest = pages.attempt(key)
            val explicitRequest = intents.attempt(key)
            val terminal = setOf(KomgaReadStatusPhase.HELD, KomgaReadStatusPhase.CONFLICT, KomgaReadStatusPhase.DENIED)
            val evidence = when {
                pageRequest != null -> pageRequest.baseline
                explicitRequest != null -> explicitRequest.baseline
                previous?.phase in terminal || page?.phase in terminal -> null
                page != null && page.supersededByRevision == null && page.revision == (previous?.revision ?: 0) ->
                    page.baseline
                else -> previous?.baseline
            }
            val baseline = KomgaProgressBaseline.decode(evidence)?.takeIf { it.bookId == key.bookId }?.encode()
            intents.record(
                key,
                read,
                System.currentTimeMillis(),
                baseline,
                aliases.map { KomgaReadStatusChapter(it.id, it.url) },
                checkOwner,
            )
        }
        if (identities.isPaused(source.id)) {
            intents.transition(intent, KomgaReadStatusPhase.HELD, checkOwner)
            return ConnectionReadStatus.HELD
        }
        if (intent.baseline == null) {
            val token = KomgaProgressRevision(key, intent.revision)
            try {
                ordering.coordinate(key, checkOwner) {
                    ordering.checkCurrent(token)
                    val remote = KomgaApiPageTransport(api, session, url) {
                        checkOwner()
                        ordering.checkCurrent(token)
                    }.read()
                    if (!remote.valid || !intents.prepareBaseline(intent, remote.encode(), checkOwner)) {
                        throw IOException("Komga explicit comparison changed")
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val denied = error is HttpException && error.code in setOf(401, 403)
                runCatching {
                    intents.transition(
                        intent,
                        if (denied) KomgaReadStatusPhase.DENIED else KomgaReadStatusPhase.HELD,
                        checkOwner,
                    )
                }
                return if (denied) ConnectionReadStatus.DENIED else ConnectionReadStatus.HELD
            }
        }
        journal.wake(key)
        return ConnectionReadStatus.PENDING
    }
}
