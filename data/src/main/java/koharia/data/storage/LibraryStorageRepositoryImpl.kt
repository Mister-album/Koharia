package koharia.data.storage

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.domain.storage.LibraryStorageRepository
import koharia.domain.storage.StorageRecord
import tachiyomi.data.Database

class LibraryStorageRepositoryImpl(private val database: Database) : LibraryStorageRepository {
    private val queries get() = database.storageQueries
    override suspend fun get(connection: Long, account: String, root: String, namespace: String, key: String) =
        queries.getRecord(connection, account, root, namespace, key).awaitAsOneOrNull()
    override suspend fun list(connection: Long, account: String, root: String, namespace: String) =
        queries.getRecords(connection, account, root, namespace, ::StorageRecord).awaitAsList()
    override suspend fun put(
        connection: Long,
        account: String,
        root: String,
        namespace: String,
        record: StorageRecord,
        checkActive: () -> Unit,
    ) {
        database.transaction {
            checkActive()
            queries.putRecord(connection, account, root, namespace, record.key, record.payload, record.revision)
            checkActive()
        }
    }
    override suspend fun remove(
        connection: Long,
        account: String,
        root: String,
        namespace: String,
        key: String,
        revision: Long,
        checkActive: () -> Unit,
    ) {
        database.transaction {
            checkActive()
            queries.removeRecord(connection, account, root, namespace, key, revision)
            checkActive()
        }
    }
    override suspend fun removeConnection(connection: Long) {
        queries.removeConnection(connection)
    }
}
