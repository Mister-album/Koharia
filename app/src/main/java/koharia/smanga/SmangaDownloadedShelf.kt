package koharia.smanga

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

internal fun SmangaManga.shelfMetadata(): JsonObject = buildJsonObject {
    put("id", id)
    put("mediaId", mediaId)
    put("sortName", sortName)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
}

internal const val SMANGA_SHELF_METADATA = "smangaShelf"

internal suspend fun persistSmangaDownloadedMetadata(
    repository: MangaRepository,
    entries: List<Manga>,
    checkSession: () -> Unit,
) {
    if (entries.isEmpty()) return
    val currentById = entries.map { it.source }.distinct()
        .flatMap { repository.getMangaBySourceId(it) }.associateBy { it.id }
    val updates = entries.mapNotNull { entry ->
        val metadata = entry.memo[SMANGA_SHELF_METADATA] as? JsonObject ?: return@mapNotNull null
        val current = currentById[entry.id] ?: return@mapNotNull null
        if (current.source != entry.source || current.url != entry.url) return@mapNotNull null
        val old = current.memo[SMANGA_SHELF_METADATA] as? JsonObject
        if (old == metadata ||
            ((old?.get("updatedAt") as? JsonPrimitive)?.longOrNull ?: 0) >
            ((metadata["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0)
        ) {
            return@mapNotNull null
        }
        MangaUpdate(entry.id, memo = JsonObject(current.memo + (SMANGA_SHELF_METADATA to metadata)))
    }
    checkSession()
    if (updates.isNotEmpty()) check(repository.updateAll(updates)) { "Could not persist downloaded library metadata" }
    checkSession()
}

internal fun downloadedSmangaShelf(
    downloaded: List<Manga>,
    prefix: String,
    selectedMedia: Long,
    query: String,
    order: String,
    cached: Map<Long, SmangaManga>,
): List<Manga> {
    val entries = downloaded.mapNotNull { manga ->
        if (!manga.url.startsWith("${prefix}manga/")) return@mapNotNull null
        val id = manga.url.removePrefix("${prefix}manga/").toLongOrNull() ?: return@mapNotNull null
        val memo = manga.memo[SMANGA_SHELF_METADATA] as? JsonObject
        val owned = memo?.takeIf { (it["id"] as? JsonPrimitive)?.longOrNull == id }
        val stored = SmangaManga(
            id,
            (owned?.get("mediaId") as? JsonPrimitive)?.longOrNull ?: 0,
            manga.title,
            createdAt = (owned?.get("createdAt") as? JsonPrimitive)?.longOrNull ?: 0,
            updatedAt = (owned?.get("updatedAt") as? JsonPrimitive)?.longOrNull ?: 0,
            sortName = (owned?.get("sortName") as? JsonPrimitive)?.contentOrNull ?: manga.title,
            chapterCount = (manga.memo["booksCount"] as? JsonPrimitive)?.intOrNull ?: -1,
        )
        val metadata = cached[id]?.takeIf { owned == null || it.updatedAt >= stored.updatedAt } ?: stored
        if (selectedMedia != 0L && metadata.mediaId != selectedMedia) return@mapNotNull null
        if (!manga.title.contains(query, true) && manga.genre.orEmpty().none { it.contains(query, true) }) {
            return@mapNotNull null
        }
        manga to metadata
    }
    val comparator = shelfOrder(order).comparator.thenBy { it.mediaId }.thenBy { it.id }
    return entries.sortedWith { left, right ->
        comparator.compare(left.second, right.second)
    }.map { (manga, metadata) ->
        val ownership = if (metadata.mediaId > 0) {
            mapOf(SMANGA_SHELF_METADATA to metadata.shelfMetadata())
        } else {
            emptyMap()
        }
        val count = if (metadata.chapterCount >= 0) {
            mapOf("booksCount" to JsonPrimitive(metadata.chapterCount))
        } else {
            emptyMap()
        }
        manga.copy(
            memo = JsonObject(manga.memo + ownership + count),
        )
    }
}
