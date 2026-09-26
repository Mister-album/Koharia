package koharia.smanga

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

@Serializable
internal data class SmangaSearchCursor(
    var page: Int = 1,
    var offset: Int = 0,
    var buffer: List<SmangaManga> = emptyList(),
    var loaded: Boolean = false,
    var hasNext: Boolean = true,
    var ended: Boolean = false,
)

@Serializable
internal data class SmangaSearchPage(
    val page: SmangaMangaPage,
    val title: SmangaSearchCursor,
    val tag: SmangaSearchCursor,
    val seen: Set<Long>,
)

/** Bounded input buffers; checkpoints are published only after both streams succeed. */
internal suspend fun mergeSmangaSearchPage(
    mediaIds: List<Long>,
    order: String,
    page: Int,
    previous: SmangaSearchPage?,
    loadTitle: suspend (Int) -> SmangaMangaPage,
    loadTag: suspend (Int) -> List<SmangaManga>,
): SmangaSearchPage = coroutineScope {
    require(page == (previous?.page?.page ?: 0) + 1)
    val allowed = mediaIds.toSet()
    val title = previous?.title?.copy() ?: SmangaSearchCursor()
    val tag = previous?.tag?.copy() ?: SmangaSearchCursor()
    val seen = previous?.seen?.toMutableSet() ?: mutableSetOf()
    val compare = shelfOrder(order).comparator.thenBy { it.mediaId }
    suspend fun head(cursor: SmangaSearchCursor, tags: Boolean): SmangaManga? {
        while (!cursor.ended) {
            currentCoroutineContext().ensureActive()
            if (!cursor.loaded) {
                if (tags) {
                    cursor.buffer = loadTag(cursor.page)
                    cursor.hasNext = cursor.buffer.isNotEmpty()
                } else {
                    val result = loadTitle(cursor.page)
                    cursor.buffer = result.data
                    cursor.hasNext = result.hasNext
                }
                cursor.loaded = true
            }
            while (cursor.offset < cursor.buffer.size) {
                val item = cursor.buffer[cursor.offset]
                // The tag endpoint is account-scoped, but cannot scope its rows to the selected media.
                if (item.mediaId in allowed) return item
                cursor.offset++
            }
            if (!cursor.hasNext) {
                cursor.ended = true
            } else {
                cursor.page++
                cursor.offset = 0
                cursor.loaded = false
            }
        }
        return null
    }
    val output = mutableListOf<SmangaManga>()
    while (output.size < 100) {
        currentCoroutineContext().ensureActive()
        val titleHead = async { head(title, false) }
        val tagHead = async { head(tag, true) }
        val a = titleHead.await()
        val b = tagHead.await()
        if (a == null && b == null) break
        val cursor = if (b == null || (a != null && compare.compare(a, b) <= 0)) title else tag
        val item = cursor.buffer[cursor.offset++]
        if (seen.add(item.id)) output += item
    }
    val more = listOf(title, tag).any { !it.ended && (it.offset < it.buffer.size || it.hasNext) }
    SmangaSearchPage(
        SmangaMangaPage(output, page, 100, if (more) -1 else seen.size, hasMore = more),
        title,
        tag,
        seen,
    )
}
