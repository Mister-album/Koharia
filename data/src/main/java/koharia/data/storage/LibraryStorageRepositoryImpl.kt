package koharia.data.storage

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.data.observeBatched
import koharia.domain.storage.LibraryStorageRepository
import koharia.domain.storage.StorageRecord
import koharia.domain.storage.StorageRecordUpdate
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

class LibraryStorageRepositoryImpl(private val database: Database) : LibraryStorageRepository {
    private val queries get() = database.storageQueries
    override fun observeNamespace(connection: Long, account: String, root: String, namespace: String) =
        queries.getRecords(connection, account, root, namespace, ::StorageRecord).subscribeToList()
            .distinctUntilChanged()

    override fun observe(
        connection: Long,
        account: String,
        root: String,
        namespace: String,
        keys: Collection<String>,
    ) = observeBatched(keys) {
        queries.getSelectedRecords(connection, account, root, namespace, it, ::StorageRecord).subscribeToList()
    }.distinctUntilChanged()

    override suspend fun get(connection: Long, account: String, root: String, namespace: String, key: String) =
        queries.getRecord(connection, account, root, namespace, key).awaitAsOneOrNull()
    override suspend fun getSnapshot(connection: Long, account: String, root: String, namespace: String, key: String) =
        queries.getSelectedRecords(
            connection,
            account,
            root,
            namespace,
            listOf(key),
            ::StorageRecord,
        ).awaitAsOneOrNull()
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
    override suspend fun replaceIfUnchanged(
        connection: Long,
        account: String,
        root: String,
        namespace: String,
        expected: StorageRecord,
        updated: StorageRecord,
        checkActive: () -> Unit,
    ): Boolean = compareAndSet(
        connection,
        account,
        root,
        listOf(StorageRecordUpdate(namespace, expected, updated)),
        checkActive,
    )

    override suspend fun compareAndSet(
        connection: Long,
        account: String,
        root: String,
        updates: List<StorageRecordUpdate>,
        checkActive: () -> Unit,
    ): Boolean {
        require(updates.isNotEmpty())
        require(updates.all { it.expected == null || it.expected?.key == it.updated.key })
        require(updates.map { it.namespace to it.updated.key }.distinct().size == updates.size)
        return database.transactionWithResult {
            checkActive()
            val matches = updates.all {
                getSnapshot(connection, account, root, it.namespace, it.updated.key) == it.expected
            }
            checkActive()
            if (!matches) return@transactionWithResult false
            updates.forEach {
                queries.putRecord(
                    connection,
                    account,
                    root,
                    it.namespace,
                    it.updated.key,
                    it.updated.payload,
                    it.updated.revision,
                )
            }
            checkActive()
            true
        }
    }

    override suspend fun removeConnection(connection: Long) {
        queries.removeConnection(connection)
    }
}
