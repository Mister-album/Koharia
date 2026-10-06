package koharia.suwayomi

import koharia.domain.suwayomi.SuwayomiCacheEntry
import koharia.domain.suwayomi.SuwayomiOperation
import koharia.domain.suwayomi.SuwayomiRepository

internal class MemorySuwayomiRepository : SuwayomiRepository {
    private val cache = mutableMapOf<List<Any>, SuwayomiCacheEntry>()
    private val operations = mutableMapOf<List<Any>, SuwayomiOperation>()
    override suspend fun cache(connectionId: Long, account: String, group: String, key: String) =
        cache[listOf(connectionId, account, group, key)]
    override suspend fun putCache(connectionId: Long, account: String, group: String, entry: SuwayomiCacheEntry) {
        cache[listOf(connectionId, account, group, entry.key)] = entry
    }
    override suspend fun operations(connectionId: Long, account: String) =
        operations.filterKeys { it[0] == connectionId && it[1] == account }.values.toList()
    override suspend fun putOperation(connectionId: Long, account: String, operation: SuwayomiOperation) {
        operations[listOf(connectionId, account, operation.key)] = operation
    }
    override suspend fun acknowledge(connectionId: Long, account: String, operation: SuwayomiOperation) {
        val key = listOf(connectionId, account, operation.key)
        if (operations[key]?.revision == operation.revision) operations[key] = operation.copy(pending = false)
    }
    override suspend fun removeConnection(connectionId: Long) {
        cache.keys.removeAll { it[0] == connectionId }
        operations.keys.removeAll { it[0] == connectionId }
    }
}

internal open class FakeSuwayomiService : SuwayomiService {
    var remote = SuwayomiChapter(11, 3, pageCount = 10)
    var updates = 0
    var shelfRequests = 0
    var onUpdate: suspend () -> Unit = {}
    override suspend fun categories() = listOf(SuwayomiCategory(0, "Default"), SuwayomiCategory(1, "Other"))
    override suspend fun libraryPage(after: String?): SuwayomiNodes<SuwayomiManga> {
        shelfRequests++
        return SuwayomiNodes(listOf(SuwayomiManga(3, "Fixture")))
    }
    override suspend fun manga(id: Int) = SuwayomiManga(id, "Fixture")
    override suspend fun chapters(mangaId: Int) = listOf(remote)
    override suspend fun chapter(id: Int) = remote
    override suspend fun pages(chapterId: Int) = SuwayomiPages(
        (0 until remote.pageCount).map { "/api/v1/manga/3/chapter/0/page/$it" },
        remote,
    )
    override suspend fun updateChapter(id: Int, pageIndex: Int?, read: Boolean?): SuwayomiChapter {
        updates++
        onUpdate()
        remote = remote.copy(
            lastPageRead = pageIndex ?: remote.lastPageRead,
            isRead = read ?: remote.isRead,
            lastReadAt = remote.lastReadAt + 1,
        )
        return remote
    }
}
