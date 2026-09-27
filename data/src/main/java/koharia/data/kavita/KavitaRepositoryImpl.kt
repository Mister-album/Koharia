package koharia.data.kavita

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.domain.kavita.KavitaAnnotationEntry
import koharia.domain.kavita.KavitaCacheEntry
import koharia.domain.kavita.KavitaOperation
import koharia.domain.kavita.KavitaRepository
import tachiyomi.data.Database

class KavitaRepositoryImpl(private val database: Database) : KavitaRepository {
    private val queries get() = database.kavitaQueries

    override suspend fun cache(connectionId: Long, account: String, group: String, key: String) =
        queries.getCache(connectionId, account, group, key, ::KavitaCacheEntry).awaitAsOneOrNull()

    override suspend fun entries(connectionId: Long, account: String, group: String) =
        queries.getEntries(connectionId, account, group, ::KavitaCacheEntry).awaitAsList()

    override suspend fun putCache(connectionId: Long, account: String, group: String, entry: KavitaCacheEntry) {
        queries.putCache(connectionId, account, group, entry.key, entry.payload, entry.generation, entry.stale)
    }

    override suspend fun replaceCache(
        connectionId: Long,
        account: String,
        group: String,
        entries: List<KavitaCacheEntry>,
    ) {
        database.transaction {
            queries.removeGroup(connectionId, account, group)
            entries.forEach { putCache(connectionId, account, group, it) }
        }
    }

    override suspend fun invalidate(connectionId: Long, account: String) {
        queries.invalidate(connectionId, account)
    }
    override suspend fun invalidateGroup(connectionId: Long, account: String, groupPrefix: String) {
        val pattern = groupPrefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        queries.invalidateGroup(connectionId, account, pattern)
    }

    override suspend fun operations(connectionId: Long, account: String) =
        queries.getOperations(connectionId, account, ::KavitaOperation).awaitAsList()

    override suspend fun putOperation(connectionId: Long, account: String, operation: KavitaOperation) {
        queries.putOperation(
            connectionId,
            account,
            operation.key,
            operation.payload,
            operation.revision,
            operation.pending,
        )
    }

    override suspend fun acknowledge(connectionId: Long, account: String, key: String, revision: Long) {
        queries.acknowledge(connectionId, account, key, revision)
    }

    override suspend fun annotations(connectionId: Long, account: String, chapterId: Long?) =
        queries.getAnnotations(connectionId, account, chapterId, ::KavitaAnnotationEntry).awaitAsList()

    override suspend fun putAnnotation(connectionId: Long, account: String, annotation: KavitaAnnotationEntry) {
        queries.putAnnotation(
            connectionId,
            account,
            annotation.key,
            annotation.chapterId,
            annotation.remoteId,
            annotation.payload,
            annotation.revision,
            annotation.pending,
        )
    }

    override suspend fun removeConnection(connectionId: Long) {
        database.transaction {
            queries.removeConnectionCache(connectionId)
            queries.removeConnectionOperations(connectionId)
            queries.removeConnectionAnnotations(connectionId)
        }
    }
}
