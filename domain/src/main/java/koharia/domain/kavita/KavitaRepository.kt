package koharia.domain.kavita

data class KavitaCacheEntry(val key: String, val payload: String, val generation: Long, val stale: Boolean = false)

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
    suspend fun cache(connectionId: Long, account: String, group: String, key: String): KavitaCacheEntry?
    suspend fun entries(connectionId: Long, account: String, group: String): List<KavitaCacheEntry>
    suspend fun putCache(connectionId: Long, account: String, group: String, entry: KavitaCacheEntry)
    suspend fun replaceCache(connectionId: Long, account: String, group: String, entries: List<KavitaCacheEntry>)
    suspend fun invalidate(connectionId: Long, account: String)
    suspend fun invalidateGroup(connectionId: Long, account: String, groupPrefix: String)
    suspend fun operations(connectionId: Long, account: String): List<KavitaOperation>
    suspend fun putOperation(connectionId: Long, account: String, operation: KavitaOperation)
    suspend fun acknowledge(connectionId: Long, account: String, key: String, revision: Long)
    suspend fun annotations(connectionId: Long, account: String, chapterId: Long? = null): List<KavitaAnnotationEntry>
    suspend fun putAnnotation(connectionId: Long, account: String, annotation: KavitaAnnotationEntry)
    suspend fun removeConnection(connectionId: Long)
}
