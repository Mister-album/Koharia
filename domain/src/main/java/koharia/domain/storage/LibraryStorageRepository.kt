package koharia.domain.storage

data class StorageRecord(val key: String, val payload: String, val revision: Long)

interface LibraryStorageRepository {
    suspend fun get(connection: Long, account: String, root: String, namespace: String, key: String): String?
    suspend fun list(connection: Long, account: String, root: String, namespace: String): List<StorageRecord>
    suspend fun put(
        connection: Long,
        account: String,
        root: String,
        namespace: String,
        record: StorageRecord,
        checkActive: () -> Unit = {},
    )
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
