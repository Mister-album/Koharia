package koharia.suwayomi

/** One chapter state to write onto the target entry. */
data class SuwayomiChapterPatch(
    val targetChapterId: Int,
    val read: Boolean,
    val lastPageRead: Int,
)

data class SuwayomiMigrationPlan(
    val patches: List<SuwayomiChapterPatch>,
    /** Source chapters carrying read state that the target has no matching number for. */
    val unmatchedStateCount: Int,
)

/**
 * Matches reading state by chapter number. Chapter numbers are the only stable identity between
 * two sources, so an unmatched state-bearing chapter is reported instead of dropped.
 */
fun planSuwayomiChapterMigration(
    source: List<SuwayomiChapter>,
    target: List<SuwayomiChapter>,
): SuwayomiMigrationPlan {
    val targets = target
        .filter { it.chapterNumber > 0f }
        .groupBy { it.chapterNumber }
        .mapValues { (_, entries) -> entries.minByOrNull { it.sourceOrder } ?: entries.first() }
    var unmatched = 0
    val patches = source.mapNotNull { chapter ->
        val carried = chapter.isRead || chapter.lastPageRead > 0
        if (chapter.chapterNumber <= 0f) {
            if (carried) unmatched++
            return@mapNotNull null
        }
        val match = targets[chapter.chapterNumber]
        if (match == null) {
            if (carried) unmatched++
            return@mapNotNull null
        }
        val read = chapter.isRead || match.isRead
        val lastPageRead = maxOf(chapter.lastPageRead, match.lastPageRead)
        if (!read && lastPageRead <= 0) {
            null
        } else {
            SuwayomiChapterPatch(targetChapterId = match.id, read = read, lastPageRead = lastPageRead)
        }
    }
    return SuwayomiMigrationPlan(patches = patches, unmatchedStateCount = unmatched)
}

/** Target chapters to fetch again, matched from the source's downloaded chapters. */
fun planSuwayomiDownloadMigration(
    source: List<SuwayomiChapter>,
    target: List<SuwayomiChapter>,
): List<Int> {
    val downloaded = source.filter { it.isDownloaded }
    if (downloaded.isEmpty()) return emptyList()
    val targets = target
        .filter { it.chapterNumber > 0f }
        .groupBy { it.chapterNumber }
        .mapValues { (_, entries) -> entries.minByOrNull { it.sourceOrder } ?: entries.first() }
    return downloaded.mapNotNull { targets[it.chapterNumber]?.id }.distinct()
}

data class SuwayomiMigrationOutcome(
    val targetMangaId: Int,
    val targetTitle: String,
    val migratedChapters: Int,
    val migratedCategories: Int,
    val migratedMeta: Int,
    val queuedDownloads: Int,
    val removedSource: Boolean,
    val warnings: List<String>,
    val failure: Throwable? = null,
) {
    val succeeded: Boolean get() = failure == null
}

/**
 * Whether the old library entry may be removed. Only a run that carried every requested piece of
 * state qualifies, so a hard write failure can never drop what it failed to copy. Unmatched chapter
 * numbers are not a failure: they are reported as warnings instead, because a migration off a dead
 * source has to be able to finish.
 */
fun canRemoveMigratedSource(
    requested: Boolean,
    sourceInLibrary: Boolean,
    hardFailure: Throwable?,
): Boolean = requested && sourceInLibrary && hardFailure == null
