package koharia.connection

import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.manga.model.Manga

/** Paged remote rows use the shared viewport query for live local state instead of per-row jobs. */
interface ConnectionShelfLocalStateAdapter {
    fun mergeShelfLocalState(remote: Manga, local: Manga?): Manga
}

internal fun resolveShelfManga(
    source: eu.kanade.tachiyomi.source.Source?,
    remote: Manga,
    state: ConnectionShelfEntryState?,
): Manga {
    if (source !is ConnectionShelfLocalStateAdapter || source.id != remote.source) return remote
    val local = state?.manga?.takeIf { it.source == remote.source && it.url == remote.url }
    return source.mergeShelfLocalState(remote, local)
}

internal fun mergeShelfMangaMetadata(remote: Manga, local: Manga?): Manga = local?.copy(
    title = remote.title,
    author = remote.author,
    description = remote.description,
    genre = remote.genre,
    thumbnailUrl = remote.thumbnailUrl,
    memo = JsonObject(local.memo + remote.memo),
) ?: remote
