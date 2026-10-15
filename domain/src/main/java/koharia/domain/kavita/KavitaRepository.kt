package koharia.domain.kavita

import kotlinx.coroutines.flow.Flow

data class KavitaCacheEntry(val key: String, val payload: String, val generation: Long, val stale: Boolean = false)

data class KavitaCachedGroup(val group: String, val payload: String, val stale: Boolean)

data class KavitaOperation(
    val key: String,
    val payload: String,
    val revision: Long,
    val pending: Boolean,
)

data class KavitaAnnotationEntry(
    val key: String,
    val chapterId: Long,
    val remoteId: Long?,
    val payload: String,
    val revision: Long,
    val pending: Boolean,
)

interface KavitaRepository {
    fun observeOperations(connectionId: Long, account: String, keys: Collection<String>): Flow<List<KavitaOperation>>
    suspend fun cache(connectionId: Long, account: String, group: String, key: String): KavitaCacheEntry?
    suspend fun entries(connectionId: Long, account: String, group: String): List<KavitaCacheEntry>
    fun observeCachedGroups(
        connectionId: Long,
        account: String,
        groups: Collection<String>,
    ): Flow<List<KavitaCachedGroup>>
    suspend fun cachedGroups(connectionId: Long, account: String, groups: Collection<String>): List<KavitaCachedGroup>
    suspend fun putCache(connectionId: Long, account: String, group: String, entry: KavitaCacheEntry)
    suspend fun replaceCache(connectionId: Long, account: String, group: String, entries: List<KavitaCacheEntry>)
    suspend fun invalidate(connectionId: Long, account: String)
    suspend fun invalidateGroup(connectionId: Long, account: String, groupPrefix: String)
    suspend fun operations(connectionId: Long, account: String): List<KavitaOperation>
    suspend fun operation(connectionId: Long, account: String, key: String): KavitaOperation?
    suspend fun pendingOperations(connectionId: Long, account: String): List<KavitaOperation>
    suspend fun putOperation(connectionId: Long, account: String, operation: KavitaOperation)
    suspend fun compareAndSetOperation(
        connectionId: Long,
        account: String,
        expected: KavitaOperation?,
        updated: KavitaOperation,
        checkCurrent: () -> Unit,
    ): Boolean
    suspend fun acknowledge(connectionId: Long, account: String, key: String, revision: Long)
    suspend fun quarantinePending(
        connectionId: Long,
        account: String,
        operation: (KavitaOperation) -> KavitaOperation,
        annotation: (KavitaAnnotationEntry) -> KavitaAnnotationEntry,
        checkCurrent: () -> Unit,
    )
    suspend fun annotations(connectionId: Long, account: String, chapterId: Long? = null): List<KavitaAnnotationEntry>
    suspend fun putAnnotation(connectionId: Long, account: String, annotation: KavitaAnnotationEntry)
    suspend fun removeConnection(connectionId: Long)
}
