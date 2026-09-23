package eu.kanade.tachiyomi.util

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.model.SManga
import koharia.connection.isConnectionLibraryEntry
import koharia.cover.CustomCoverStore
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import java.time.Instant

/**
 * Call before updating [Manga.thumbnail_url] to ensure old cover can be cleared from cache
 */
fun Manga.prepUpdateCover(coverCache: CoverCache, remoteManga: SManga, refreshSameUrl: Boolean): Manga {
    // Never refresh covers if the new url is null, as the current url has possibly become invalid
    val newUrl = remoteManga.thumbnail_url ?: return this

    // Never refresh covers if the url is empty to avoid "losing" existing covers
    if (newUrl.isEmpty()) return this

    if (!refreshSameUrl && thumbnailUrl == newUrl) return this

    coverCache.deleteFromCache(this)
    return copy(coverLastModified = Instant.now().toEpochMilli())
}

fun Manga.removeCovers(coverCache: CoverCache = Injekt.get()): Manga {
    return if (coverCache.deleteFromCache(this) > 0) {
        return copy(coverLastModified = Instant.now().toEpochMilli())
    } else {
        this
    }
}

suspend fun Manga.editCover(
    stream: InputStream,
    updateManga: UpdateManga = Injekt.get(),
    customCovers: CustomCoverStore = Injekt.get(),
    sourceManager: SourceManager = Injekt.get(),
) {
    if (isConnectionLibraryEntry(sourceManager)) {
        customCovers.write(this, stream)
        updateManga.awaitUpdateCoverLastModified(id)
    }
}
