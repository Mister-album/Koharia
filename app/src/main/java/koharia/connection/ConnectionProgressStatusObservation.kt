package koharia.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal typealias ProgressStatuses = Map<String, Set<ConnectionProgressSyncStatus>>

/** Reloads also replace sessions inside the same Source instance. Never retain its previous account's result. */
internal fun <T> observeProgressStatusSessions(
    changes: Flow<T>,
    urls: List<String>,
    open: suspend () -> Flow<ProgressStatuses>,
): Flow<ProgressStatuses> = changes.flatMapLatest {
    flow {
        emit(emptyMap())
        emitAll(open())
    }.catch {
        // Session invalidation can throw CancellationException without cancelling this collector.
        currentCoroutineContext().ensureActive()
        emit(urls.associateWith { setOf(ConnectionProgressSyncStatus.UNAVAILABLE) })
    }
}.flowOn(Dispatchers.IO)

internal fun Flow<ProgressStatuses>.withProgressStatusOwner(
    connectionId: Long,
    paused: Flow<Set<Long>>,
    checkOwner: () -> Unit,
): Flow<ProgressStatuses> = combine(this, paused) { statuses, held ->
    checkOwner()
    statuses.mapValues { (_, values) ->
        if (connectionId in held && values.any { it == ConnectionProgressSyncStatus.PENDING }) {
            values - ConnectionProgressSyncStatus.PENDING + ConnectionProgressSyncStatus.HELD
        } else {
            values
        }
    }.filterValues { it.isNotEmpty() }
}

/** URL ownership is checked by provider parsers; invalid or obsolete URLs cannot select current-account rows. */
internal class ProgressStatusTargets<K>(urls: List<String>, parse: (String) -> K) {
    val keys = urls.distinct().associateWith {
        try {
            parse(it)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun project(states: Map<K, Set<ConnectionProgressSyncStatus>>): ProgressStatuses = keys.mapValues { (_, key) ->
        if (key == null) setOf(ConnectionProgressSyncStatus.UNVERIFIED_OWNER) else states[key].orEmpty()
    }.filterValues { it.isNotEmpty() }
}
