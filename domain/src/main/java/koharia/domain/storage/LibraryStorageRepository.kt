package koharia.domain.storage

import kotlinx.coroutines.flow.Flow

data class StorageRecord(val key: String, val payload: String, val revision: Long)

/** A null expected record permits insertion only while the key remains absent. */
data class StorageRecordUpdate(val namespace: String, val expected: StorageRecord?, val updated: StorageRecord)

interface LibraryStorageRepository {
    fun observeNamespace(
        connection: Long,
        account: String,
        root: String,
        namespace: String,
    ): Flow<List<StorageRecord>>

    fun observe(
        connection: Long,
        account: String,
        root: String,
        namespace: String,
        keys: Collection<String>,
    ): Flow<List<StorageRecord>>
    suspend fun get(connection: Long, account: String, root: String, namespace: String, key: String): String?
    suspend fun getSnapshot(
        connection: Long,
        account: String,
        root: String,
        namespace: String,
        key: String,
    ): StorageRecord?
    suspend fun compareAndSet(
        connection: Long,
        account: String,
        root: String,
        updates: List<StorageRecordUpdate>,
        checkActive: () -> Unit = {},
    ): Boolean
    suspend fun list(connection: Long, account: String, root: String, namespace: String): List<StorageRecord>
    suspend fun put(
        connection: Long,
        account: String,
        root: String,
        namespace: String,
        record: StorageRecord,
        checkActive: () -> Unit = {},
    )
    suspend fun replaceIfUnchanged(
        connection: Long,
        account: String,
        root: String,
        namespace: String,
        expected: StorageRecord,
        updated: StorageRecord,
        checkActive: () -> Unit = {},
    ): Boolean

    suspend fun remove(
        connection: Long,
        account: String,
        root: String,
        namespace: String,
        key: String,
        revision: Long,
        checkActive: () -> Unit = {},
    )
    suspend fun removeConnection(connection: Long)
}
