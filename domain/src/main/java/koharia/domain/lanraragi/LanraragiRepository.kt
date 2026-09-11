package koharia.domain.lanraragi

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class LanraragiEntry(
    val id: String,
    val kind: Kind = Kind.ARCHIVE,
    val title: String,
    val tags: String = "",
    val summary: String = "",
    val pageCount: Int = 0,
    val progress: Int = 0,
    val lastRead: Long = 0,
    val isNew: Boolean = false,
    val members: List<String> = emptyList(),
    val search: String = "",
    val pinned: Boolean = false,
    val untagged: Boolean = false,
    val available: Boolean = true,
) {
    @Serializable
    enum class Kind { ARCHIVE, TANK, CATEGORY }

    val tagList: List<String> get() = tags.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
    val addedAt: Long get() = tagList.firstOrNull { it.startsWith("date_added:") }
        ?.substringAfter(':')?.toLongOrNull() ?: 0
    val url: String get() = "/lanraragi/${kind.name.lowercase()}/$id"
}

data class LanraragiReadState(
    val archiveId: String,
    val pageIndex: Int,
    val totalPages: Int,
    val readAt: Long,
    val localUnread: Boolean = false,
    val pending: Boolean = true,
    val revision: Long = 0,
    val initialPage: Boolean = false,
)

interface LanraragiRepository {
    fun observeEntries(connectionId: Long): Flow<List<LanraragiEntry>>
    suspend fun entries(connectionId: Long): List<LanraragiEntry>
    suspend fun lastSync(connectionId: Long): Long
    suspend fun begin(connectionId: Long, generation: Long)
    suspend fun stage(connectionId: Long, generation: Long, entries: List<LanraragiEntry>)
    suspend fun publish(connectionId: Long, generation: Long, completedAt: Long)
    suspend fun abort(connectionId: Long, generation: Long)
    fun observeReadStates(connectionId: Long): Flow<List<LanraragiReadState>>
    suspend fun readStates(connectionId: Long): List<LanraragiReadState>
    suspend fun record(connectionId: Long, state: LanraragiReadState)
    suspend fun acknowledge(connectionId: Long, state: LanraragiReadState)
    suspend fun resetReadStates(connectionId: Long, archiveIds: List<String>)
    suspend fun remove(connectionId: Long)
}
