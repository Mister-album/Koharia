package koharia.suwayomi

/** Short, session-local backoff: a transient preview failure must not disable it until reconnect. */
internal class SuwayomiPreviewFailures(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val chapters = linkedMapOf<Int, Long>()
    private val sources = linkedMapOf<Int, Long>()

    @Synchronized
    fun blocksChapter(mangaId: Int, chapterId: Int): Boolean =
        blocked(sources, mangaId) || blocked(chapters, chapterId)

    @Synchronized
    fun blocksSource(mangaId: Int): Boolean = blocked(sources, mangaId)

    @Synchronized
    fun failedChapter(chapterId: Int) = remember(chapters, chapterId, 30_000)

    @Synchronized
    fun missingSource(mangaId: Int): Boolean {
        if (blocked(sources, mangaId)) return false
        remember(sources, mangaId, 300_000)
        return true
    }

    @Synchronized
    fun succeeded(chapterId: Int) {
        chapters.remove(chapterId)
    }

    private fun blocked(entries: MutableMap<Int, Long>, id: Int): Boolean {
        val deadline = entries[id] ?: return false
        if (nowMillis() < deadline) return true
        entries.remove(id)
        return false
    }

    private fun remember(entries: MutableMap<Int, Long>, id: Int, delayMillis: Long) {
        entries.remove(id)
        if (entries.size >= 512) entries.remove(entries.keys.first())
        entries[id] = nowMillis() + delayMillis
    }
}
