package eu.kanade.tachiyomi.data.download

internal fun isValidDownloadQueueReorder(
    currentChapterIds: List<Long>,
    reorderedChapterIds: List<Long>,
): Boolean {
    val reorderedChapterIdSet = reorderedChapterIds.toSet()
    return reorderedChapterIdSet.size == reorderedChapterIds.size &&
        reorderedChapterIdSet.containsAll(currentChapterIds)
}
