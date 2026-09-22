package koharia.smanga

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8
import java.util.PriorityQueue

@Serializable
internal data class SmangaMergedShelfPage(
    val page: SmangaMangaPage,
    val offsets: Map<Long, Int>,
    val totals: Map<Long, Int>,
)

/** The caller scopes cursors and loaded pages to the same account, query, order and cache generation. */
internal suspend fun mergeSmangaShelfPage(
    mediaIds: List<Long>,
    order: String,
    page: Int,
    previous: SmangaMergedShelfPage?,
    loadPage: suspend (Long, Int) -> SmangaMangaPage,
): SmangaMergedShelfPage = coroutineScope {
    val ids = mediaIds.toSet()
    if (page < 1 || ids.size != mediaIds.size || ids.any { it <= 0 }) protocol()
    val shelfOrder = shelfOrder(order)
    val primary = shelfOrder.comparator
    val comparator = primary.thenComparingLong { it.mediaId }
    validatePrevious(ids, page, previous, comparator.takeIf { shelfOrder.validateSequence })

    suspend fun updateHead(cursor: MergeCursor) {
        currentCoroutineContext().ensureActive()
        if (cursor.total != null && cursor.offset == cursor.total) {
            cursor.head = null
            return
        }
        val remotePage = cursor.offset / MERGED_PAGE_SIZE + 1
        if (cursor.buffer?.page != remotePage) {
            val loaded = loadPage(cursor.mediaId, remotePage)
            if (loaded.page != remotePage || loaded.pageSize != MERGED_PAGE_SIZE || loaded.total < 0) protocol()
            if (cursor.total != null && cursor.total != loaded.total) incomplete()
            if (loaded.data.size != expectedSize(remotePage, loaded.total)) incomplete()
            if (loaded.data.any { it.mediaId != cursor.mediaId || it.id <= 0 }) incomplete()
            if (loaded.data.map { it.id }.toSet().size != loaded.data.size) incomplete()
            if (shelfOrder.validateSequence &&
                loaded.data.zipWithNext().any { (left, right) -> primary.compare(left, right) > 0 }
            ) {
                incomplete()
            }
            cursor.total = loaded.total
            cursor.buffer = loaded
        }
        cursor.head = if (cursor.offset == cursor.total) {
            null
        } else {
            cursor.buffer?.data?.getOrNull(cursor.offset % MERGED_PAGE_SIZE) ?: incomplete()
        }
    }

    // Every nonempty library needs a head before the first result can be selected.
    val cursors = ids.sorted().chunked(2).flatMap { batch ->
        batch.map { mediaId ->
            async {
                MergeCursor(mediaId, previous?.offsets?.getValue(mediaId) ?: 0, previous?.totals?.getValue(mediaId))
                    .also { updateHead(it) }
            }
        }.awaitAll()
    }
    val total = cursors.sumOf { checkNotNull(it.total).toLong() }
    if (total > Int.MAX_VALUE) incomplete()
    val heads = PriorityQueue<MergeCursor> { left, right ->
        comparator.compare(checkNotNull(left.head), checkNotNull(right.head))
    }
    heads.addAll(cursors.filter { it.head != null })
    val items = ArrayList<SmangaManga>(MERGED_PAGE_SIZE)
    val seen = previous?.page?.data?.mapTo(mutableSetOf()) { it.id } ?: mutableSetOf()
    var last = previous?.page?.data?.lastOrNull()
    while (items.size < MERGED_PAGE_SIZE && heads.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val cursor = heads.remove()
        val item = checkNotNull(cursor.head)
        if (shelfOrder.validateSequence && last?.let { comparator.compare(it, item) > 0 } == true) incomplete()
        if (!seen.add(item.id)) incomplete()
        items += item
        cursor.offset++
        last = item
        if (items.size < MERGED_PAGE_SIZE) {
            updateHead(cursor)
            if (cursor.head != null) heads += cursor
        }
    }
    if (items.size != expectedSize(page, total.toInt())) incomplete()
    SmangaMergedShelfPage(
        page = SmangaMangaPage(items, page, MERGED_PAGE_SIZE, total.toInt()),
        offsets = cursors.associate { it.mediaId to it.offset },
        totals = cursors.associate { it.mediaId to checkNotNull(it.total) },
    )
}

private fun validatePrevious(
    mediaIds: Set<Long>,
    page: Int,
    previous: SmangaMergedShelfPage?,
    comparator: Comparator<SmangaManga>?,
) {
    if (page == 1) {
        if (previous != null) protocol()
        return
    }
    if (previous == null || previous.page.page != page - 1 || previous.page.pageSize != MERGED_PAGE_SIZE) protocol()
    if (previous.offsets.keys != mediaIds || previous.totals.keys != mediaIds) protocol()
    if (previous.totals.values.any { it < 0 }) protocol()
    val total = previous.totals.values.sumOf { it.toLong() }
    if (total > Int.MAX_VALUE || previous.page.total.toLong() != total) protocol()
    if (previous.offsets.any { (id, offset) -> offset < 0 || offset > previous.totals.getValue(id) }) protocol()
    val consumed = previous.offsets.values.sumOf { it.toLong() }
    if (consumed != minOf((page - 1L) * MERGED_PAGE_SIZE, total)) protocol()
    val items = previous.page.data
    if (items.size != expectedSize(page - 1, total.toInt())) protocol()
    if (items.any { it.mediaId !in mediaIds || it.id <= 0 } || items.map { it.id }.toSet().size != items.size) {
        protocol()
    }
    if (comparator != null &&
        items.zipWithNext().any { (left, right) -> comparator.compare(left, right) > 0 }
    ) {
        protocol()
    }
    if (items.groupingBy { it.mediaId }.eachCount().any { (id, count) -> count > previous.offsets.getValue(id) }) {
        protocol()
    }
}

private data class ShelfOrder(val comparator: Comparator<SmangaManga>, val validateSequence: Boolean)

private fun shelfOrder(order: String): ShelfOrder {
    val parts = order.trim().split(Regex("\\s+"))
    if (parts.size != 2 || parts[1] !in setOf("asc", "desc")) protocol()
    val ascending: Comparator<SmangaManga> = when (parts[0]) {
        "mangaName" -> Comparator<SmangaManga> { left, right ->
            left.sortName.encodeUtf8().compareTo(right.sortName.encodeUtf8())
        }
        "createTime" -> compareBy { it.createdAt }
        "updateTime" -> compareBy { it.updatedAt }
        "id" -> compareBy { it.id }
        else -> protocol()
    }
    // smanga does not expose its database collation. Keep each library's name order and use
    // UTF-8 only to choose between library heads; a different name sequence is not missing data.
    return ShelfOrder(
        comparator = if (parts[1] == "desc") ascending.reversed() else ascending,
        validateSequence = parts[0] != "mangaName",
    )
}

private fun expectedSize(page: Int, total: Int): Int =
    (total.toLong() - (page - 1L) * MERGED_PAGE_SIZE).coerceIn(0, MERGED_PAGE_SIZE.toLong()).toInt()

private class MergeCursor(val mediaId: Long, var offset: Int, var total: Int?) {
    var buffer: SmangaMangaPage? = null
    var head: SmangaManga? = null
}

private fun protocol(): Nothing = throw SmangaException(SmangaException.Reason.PROTOCOL)
private fun incomplete(): Nothing = throw SmangaException(SmangaException.Reason.INCOMPLETE)
private const val MERGED_PAGE_SIZE = 100
