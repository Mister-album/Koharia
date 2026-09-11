package koharia.lanraragi

import koharia.domain.lanraragi.LanraragiEntry
import koharia.domain.lanraragi.LanraragiReadState
import java.util.Locale
import kotlin.random.Random

data class LanraragiFilter(
    val query: String = "",
    val category: String? = null,
    val tag: String? = null,
    val grouped: Boolean = true,
    val newOnly: Boolean = false,
    val untaggedOnly: Boolean = false,
    val readStatus: Int = 0,
    val sort: Int = 0,
    val descending: Boolean = false,
    val randomSeed: Int = 0,
)

/** Missing and cyclic members remain visible as unavailable chapters. */
fun flattenLanraragiTank(tank: LanraragiEntry, entries: Map<String, LanraragiEntry>): List<LanraragiEntry> {
    val result = linkedMapOf<String, LanraragiEntry>()
    fun visit(id: String, ancestors: Set<String>) {
        if (id in ancestors) return
        val entry = entries[id]
        if (entry?.kind == LanraragiEntry.Kind.TANK) {
            entry.members.forEach { visit(it, ancestors + id) }
        } else {
            result.putIfAbsent(id, entry ?: LanraragiEntry(id = id, title = id, available = false))
        }
    }
    tank.members.forEach { visit(it, setOf(tank.id)) }
    return result.values.toList()
}

fun filterLanraragiCatalog(
    entries: List<LanraragiEntry>,
    states: List<LanraragiReadState>,
    filter: LanraragiFilter,
    matchedArchiveIds: Set<String>? = null,
    availableEntryIds: Set<String>? = null,
): List<LanraragiEntry> {
    val byId = entries.associateBy { it.id }
    val readById = states.associateBy { it.archiveId }
    val categoryMembers = filter.category?.let { byId[it]?.members?.toSet().orEmpty() }
    val tokens = filter.query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotEmpty)
    fun matches(entry: LanraragiEntry): Boolean {
        val state = readById[entry.id]?.takeIf { it.localUnread || it.pending || it.readAt >= entry.lastRead }
        val page = if (state?.localUnread == true) 0 else state?.let { it.pageIndex + 1 } ?: entry.progress
        val count = state?.totalPages?.takeIf { it > 0 } ?: entry.pageCount
        val status = if (page == 0) {
            1
        } else if (count > 0 && page >= count) {
            3
        } else {
            2
        }
        val searchable = "${entry.title} ${entry.tags} ${entry.summary}".lowercase(Locale.ROOT)
        return (matchedArchiveIds == null || entry.id in matchedArchiveIds) &&
            (categoryMembers == null || entry.id in categoryMembers) &&
            tokens.all(searchable::contains) && (filter.tag == null || filter.tag in entry.tagList) &&
            (!filter.newOnly || entry.isNew) && (!filter.untaggedOnly || entry.untagged) &&
            (filter.readStatus == 0 || filter.readStatus == status)
    }
    val archives = entries.filter { it.kind == LanraragiEntry.Kind.ARCHIVE && matches(it) }
    val selected = archives.mapTo(mutableSetOf()) { it.id }
    val result = if (filter.grouped) {
        val tanks = entries.filter {
            it.kind == LanraragiEntry.Kind.TANK && (availableEntryIds == null || it.id in availableEntryIds)
        }
            .filter { tank ->
                flattenLanraragiTank(tank, byId).any { it.id in selected } ||
                    (
                        matchedArchiveIds == null && categoryMembers == null && filter.readStatus == 0 &&
                            !filter.newOnly && !filter.untaggedOnly &&
                            matches(tank)
                        )
            }
        val grouped = tanks.flatMap { flattenLanraragiTank(it, byId) }.mapTo(mutableSetOf()) { it.id }
        tanks + archives.filterNot { it.id in grouped }
    } else {
        archives
    }
    fun members(entry: LanraragiEntry) = if (entry.kind ==
        LanraragiEntry.Kind.TANK
    ) {
        flattenLanraragiTank(entry, byId)
    } else {
        listOf(entry)
    }
    val available = result.filter { availableEntryIds == null || it.id in availableEntryIds }
    val sorted = when (filter.sort) {
        1 -> available.sortedBy { members(it).maxOfOrNull(LanraragiEntry::addedAt) ?: 0 }
        2 -> available.sortedBy {
            members(it).maxOfOrNull { archive -> readById[archive.id]?.readAt ?: archive.lastRead }
                ?: 0
        }
        3 -> available.sortedBy { it.id }.shuffled(Random(filter.randomSeed))
        else -> available.sortedWith(compareBy<LanraragiEntry> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id })
    }
    return if (filter.descending && filter.sort != 3) sorted.reversed() else sorted
}

internal fun localProgressWins(local: LanraragiReadState, remote: LanraragiEntry): Boolean =
    !local.localUnread && local.readAt > remote.lastRead
