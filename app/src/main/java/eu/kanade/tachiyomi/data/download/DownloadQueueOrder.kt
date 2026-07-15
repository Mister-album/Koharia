package eu.kanade.tachiyomi.data.download

internal fun isValidDownloadQueueReorder(
    currentChapterIds: List<Long>,
    reorderedChapterIds: List<Long>,
): Boolean {
    return currentChapterIds.size == reorderedChapterIds.size &&
        currentChapterIds.toSet() == reorderedChapterIds.toSet()
}
