package koharia.domain.suwayomi

data class SuwayomiCacheEntry(val key: String, val payload: String, val generation: Long)

data class SuwayomiOperation(val key: String, val payload: String, val revision: Long, val pending: Boolean)

interface SuwayomiRepository {
    suspend fun cache(connectionId: Long, account: String, group: String, key: String): SuwayomiCacheEntry?
    suspend fun putCache(connectionId: Long, account: String, group: String, entry: SuwayomiCacheEntry)
    suspend fun operations(connectionId: Long, account: String): List<SuwayomiOperation>
    suspend fun putOperation(connectionId: Long, account: String, operation: SuwayomiOperation)
    suspend fun acknowledge(connectionId: Long, account: String, operation: SuwayomiOperation)
    suspend fun removeConnection(connectionId: Long)
}
