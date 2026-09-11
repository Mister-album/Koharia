package koharia.data.lanraragi

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.domain.lanraragi.LanraragiEntry
import koharia.domain.lanraragi.LanraragiReadState
import koharia.domain.lanraragi.LanraragiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

class LanraragiRepositoryImpl(private val database: Database, private val json: Json) : LanraragiRepository {
    private val queries get() = database.lanraragiQueries

    override fun observeEntries(connectionId: Long) = queries.getEntries(connectionId).subscribeToList()
        .map { rows -> rows.map { json.decodeFromString<LanraragiEntry>(it) } }
        .flowOn(Dispatchers.IO)

    override suspend fun entries(connectionId: Long) = queries.getEntries(connectionId).awaitAsList()
        .map { json.decodeFromString<LanraragiEntry>(it) }

    override suspend fun lastSync(connectionId: Long) = queries.getSync(connectionId).awaitAsOneOrNull() ?: 0

    override suspend fun begin(connectionId: Long, generation: Long) {
        abort(connectionId, generation)
    }

    override suspend fun stage(connectionId: Long, generation: Long, entries: List<LanraragiEntry>) {
        database.transaction {
            entries.forEach { entry ->
                queries.stageEntry(connectionId, generation, entry.id, json.encodeToString(entry))
                entry.members.forEachIndexed { index, member ->
                    queries.stageMember(connectionId, generation, entry.id, member, index.toLong())
                }
            }
        }
    }

    override suspend fun publish(connectionId: Long, generation: Long, completedAt: Long) {
        database.transaction {
            queries.publish(connectionId, generation, completedAt)
            queries.removeOtherEntries(connectionId, generation)
            queries.removeOtherMembers(connectionId, generation)
        }
    }

    override suspend fun abort(connectionId: Long, generation: Long) {
        database.transaction {
            queries.abortEntries(connectionId, generation)
            queries.abortMembers(connectionId, generation)
        }
    }

    override fun observeReadStates(connectionId: Long) = queries.getReadStates(
        connectionId,
        ::mapState,
    ).subscribeToList()
    override suspend fun readStates(connectionId: Long) = queries.getReadStates(connectionId, ::mapState).awaitAsList()

    override suspend fun record(connectionId: Long, state: LanraragiReadState) {
        queries.record(
            connectionId,
            state.archiveId,
            state.pageIndex.toLong(),
            state.totalPages.toLong(),
            state.readAt,
            if (state.localUnread) 1 else 0,
            if (state.pending) 1 else 0,
            if (state.initialPage) 1 else 0,
        )
    }

    override suspend fun acknowledge(connectionId: Long, state: LanraragiReadState) {
        queries.acknowledge(connectionId, state.archiveId, state.revision)
    }

    override suspend fun remove(connectionId: Long) {
        database.transaction {
            queries.removeCatalog(connectionId)
            queries.removeMembers(connectionId)
            queries.removeSync(connectionId)
            queries.removeReading(connectionId)
        }
    }

    override suspend fun resetReadStates(connectionId: Long, archiveIds: List<String>) {
        if (archiveIds.isNotEmpty()) queries.removeRestoredReading(connectionId, archiveIds)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapState(
        connectionId: Long,
        archiveId: String,
        pageIndex: Long,
        totalPages: Long,
        readAt: Long,
        localUnread: Long,
        pending: Long,
        revision: Long,
        initialPage: Long,
    ) = LanraragiReadState(
        archiveId,
        pageIndex.toInt(),
        totalPages.toInt(),
        readAt,
        localUnread != 0L,
        pending != 0L,
        revision,
        initialPage != 0L,
    )
}
