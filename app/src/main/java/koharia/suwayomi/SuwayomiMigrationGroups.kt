package koharia.suwayomi

/** One library source with its entry count, as shown by the migration source picker. */
data class SuwayomiLibrarySourceGroup(
    val sourceId: Long,
    val displayName: String,
    val count: Int,
    val isObsolete: Boolean,
)

enum class SuwayomiMigrationSort { ALPHABETICAL, TOTAL }

/**
 * Groups the server library by source for the "move everything off a dead source" picker.
 * A source is obsolete when the server reports its extension as obsolete or missing, so it can be
 * flagged and floated to the top. Pure so ordering and counting stay unit-testable.
 */
fun groupSuwayomiLibraryBySource(
    mangas: List<SuwayomiManga>,
    sourceNames: Map<Long, String>,
    obsoleteSourceIds: Set<Long>,
): List<SuwayomiLibrarySourceGroup> {
    val counts = LinkedHashMap<Long, Int>()
    mangas.forEach { manga -> counts[manga.sourceId] = (counts[manga.sourceId] ?: 0) + 1 }
    return counts.map { (sourceId, count) ->
        SuwayomiLibrarySourceGroup(
            sourceId = sourceId,
            displayName = sourceNames[sourceId] ?: sourceId.toString(),
            count = count,
            isObsolete = sourceId in obsoleteSourceIds,
        )
    }
}

/**
 * Applies the picker's obsolete filter, text query and sort mode. Obsolete sources are listed
 * first regardless of the chosen sort so a dead source is always easy to find.
 */
fun sortSuwayomiLibrarySources(
    groups: List<SuwayomiLibrarySourceGroup>,
    query: String,
    obsoleteOnly: Boolean,
    sort: SuwayomiMigrationSort,
    ascending: Boolean,
): List<SuwayomiLibrarySourceGroup> {
    val trimmed = query.trim()
    val filtered = groups.filter { group ->
        (!obsoleteOnly || group.isObsolete) &&
            (trimmed.isEmpty() || group.displayName.contains(trimmed, ignoreCase = true))
    }
    return filtered.sortedWith(
        compareByDescending<SuwayomiLibrarySourceGroup> { it.isObsolete }
            .thenComparator { a, b ->
                val result = when (sort) {
                    SuwayomiMigrationSort.ALPHABETICAL ->
                        a.displayName.lowercase().compareTo(b.displayName.lowercase())
                    SuwayomiMigrationSort.TOTAL -> a.count.compareTo(b.count)
                }
                if (ascending) result else -result
            },
    )
}

/** Sources that can host a migration target: every installed source except the one being left. */
fun suwayomiMigrationTargets(
    sources: List<SuwayomiSourceInfo>,
    excludeSourceId: Long,
    query: String,
): List<SuwayomiSourceInfo> {
    val trimmed = query.trim()
    return sources
        .filter { it.id != excludeSourceId }
        .filter {
            trimmed.isEmpty() ||
                it.name.contains(trimmed, ignoreCase = true) ||
                it.lang.contains(trimmed, ignoreCase = true)
        }
        .sortedBy { it.name.lowercase() }
}
