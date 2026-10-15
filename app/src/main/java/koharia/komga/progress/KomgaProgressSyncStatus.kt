package koharia.komga.progress

import koharia.connection.ConnectionIdentityChanges
import koharia.connection.ConnectionProgressSyncStatus
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaProgressSyncStatus
import koharia.source.komga.KomgaSource
import koharia.source.komga.captureKomgaProgressSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Request
import tachiyomi.domain.source.service.SourceManager

internal fun observeKomgaProgressSyncStatus(
    source: KomgaSource,
    chapterUrls: List<String>,
    sources: SourceManager,
    pages: KomgaPageEventRepository,
    identities: ConnectionIdentityChanges,
) = flow {
    val session = captureKomgaProgressSession(source, sources)
    val books = chapterUrls.distinct().associateWith { url ->
        session.account.requireDestination(Request.Builder().url(url).build())
        KomgaProgressOrdering.bookId(url)
    }
    emitAll(
        combine(pages.observeSyncStatus(source.id, session.account.key, books.values), identities.pausedIds) {
                states,
                paused,
            ->
            session.guard.requireCurrent()
            books.mapValues { (_, book) ->
                states[book].orEmpty().map { state ->
                    when {
                        state == KomgaProgressSyncStatus.UNVERIFIED_OWNER ->
                            ConnectionProgressSyncStatus.UNVERIFIED_OWNER
                        source.id in paused -> ConnectionProgressSyncStatus.HELD
                        else -> when (state) {
                            KomgaProgressSyncStatus.PENDING -> ConnectionProgressSyncStatus.PENDING
                            KomgaProgressSyncStatus.HELD -> ConnectionProgressSyncStatus.HELD
                            KomgaProgressSyncStatus.CONFLICT -> ConnectionProgressSyncStatus.CONFLICT
                            KomgaProgressSyncStatus.DENIED -> ConnectionProgressSyncStatus.DENIED
                            KomgaProgressSyncStatus.VERIFYING -> ConnectionProgressSyncStatus.VERIFYING
                            KomgaProgressSyncStatus.UNVERIFIED_OWNER -> ConnectionProgressSyncStatus.UNVERIFIED_OWNER
                        }
                    }
                }.toSet()
            }.filterValues { it.isNotEmpty() }
        },
    )
}.flowOn(Dispatchers.IO)
