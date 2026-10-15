package koharia.source.komga

import koharia.komga.api.dto.mergeKomgaOfflineMemo
import tachiyomi.domain.manga.model.Manga

internal fun mergeKomgaShelfManga(remote: Manga, local: Manga?): Manga {
    if (local == null) return remote

    return remote.copy(
        id = local.id,
        favorite = local.favorite,
        lastUpdate = local.lastUpdate,
        nextUpdate = local.nextUpdate,
        fetchInterval = local.fetchInterval,
        dateAdded = local.dateAdded,
        viewerFlags = local.viewerFlags,
        chapterFlags = local.chapterFlags,
        coverLastModified = local.coverLastModified,
        updateStrategy = local.updateStrategy,
        initialized = local.initialized,
        lastModifiedAt = local.lastModifiedAt,
        favoriteModifiedAt = local.favoriteModifiedAt,
        version = local.version,
        notes = local.notes,
        memo = mergeKomgaOfflineMemo(local.memo, remote.memo) ?: local.memo,
    )
}
