package koharia.connection

/** Resume the saved unread item, then the next unread item in queue order. */
internal fun readingQueueResumeIndex(completed: List<Boolean>, rememberedIndex: Int): Int? {
    if (completed.isEmpty()) return null
    if (rememberedIndex in completed.indices && !completed[rememberedIndex]) return rememberedIndex
    val next = (rememberedIndex + 1).coerceIn(0, completed.size)
    return (next until completed.size).firstOrNull { !completed[it] }
        ?: completed.indices.firstOrNull { !completed[it] }
        ?: 0
}
