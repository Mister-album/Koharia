package koharia.data.suwayomi

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.domain.suwayomi.SuwayomiCacheEntry
import koharia.domain.suwayomi.SuwayomiOperation
import koharia.domain.suwayomi.SuwayomiRepository
import tachiyomi.data.Database

class SuwayomiRepositoryImpl(private val database: Database) : SuwayomiRepository {
    private val queries get() = database.suwayomiQueries

    override suspend fun cache(connectionId: Long, account: String, group: String, key: String) =
        queries.getCache(connectionId, account, group, key, ::SuwayomiCacheEntry).awaitAsOneOrNull()

    override suspend fun putCache(connectionId: Long, account: String, group: String, entry: SuwayomiCacheEntry) {
        queries.putCache(connectionId, account, group, entry.key, entry.payload, entry.generation)
    }

    override suspend fun operations(connectionId: Long, account: String) =
        queries.getOperations(connectionId, account, ::SuwayomiOperation).awaitAsList()

    override suspend fun putOperation(connectionId: Long, account: String, operation: SuwayomiOperation) {
        queries.putOperation(
            connectionId,
            account,
            operation.key,
            operation.payload,
            operation.revision,
            operation.pending,
        )
    }

    override suspend fun acknowledge(connectionId: Long, account: String, operation: SuwayomiOperation) {
        queries.acknowledge(operation.payload, connectionId, account, operation.key, operation.revision)
    }

    override suspend fun removeConnection(connectionId: Long) {
        database.transaction {
            queries.removeConnectionCache(connectionId)
            queries.removeConnectionOperations(connectionId)
        }
    }
}
