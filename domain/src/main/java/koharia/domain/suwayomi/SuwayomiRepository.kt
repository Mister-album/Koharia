package koharia.domain.suwayomi

import kotlinx.coroutines.flow.Flow

data class SuwayomiCacheEntry(val key: String, val payload: String, val generation: Long)

data class SuwayomiOperation(val key: String, val payload: String, val revision: Long, val pending: Boolean)

interface SuwayomiRepository {
    fun observeOperations(connectionId: Long, account: String, keys: Collection<String>): Flow<List<SuwayomiOperation>>
    suspend fun cache(connectionId: Long, account: String, group: String, key: String): SuwayomiCacheEntry?
    suspend fun putCache(connectionId: Long, account: String, group: String, entry: SuwayomiCacheEntry)
    suspend fun replaceCacheGroup(connectionId: Long, account: String, group: String, entries: List<SuwayomiCacheEntry>)
    suspend fun migrateDiscoveryCache(
        connectionId: Long,
        account: String,
        groups: Map<String, List<SuwayomiCacheEntry>>,
    )
    suspend fun invalidateDiscoveryCache(connectionId: Long, account: String)
    suspend fun operations(connectionId: Long, account: String): List<SuwayomiOperation>
    suspend fun operation(connectionId: Long, account: String, key: String): SuwayomiOperation?
    suspend fun pendingOperations(connectionId: Long, account: String): List<SuwayomiOperation>
    suspend fun putOperation(connectionId: Long, account: String, operation: SuwayomiOperation)
    suspend fun acknowledge(connectionId: Long, account: String, operation: SuwayomiOperation)
    suspend fun removeConnection(connectionId: Long)
}
