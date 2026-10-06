package eu.kanade.tachiyomi.data.coil

import coil3.getOrDefault
import coil3.key.Keyer
import coil3.request.Options
import koharia.cover.CustomCoverStore
import tachiyomi.domain.manga.model.MangaCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.domain.manga.model.Manga as DomainManga

class MangaKeyer(private val customCovers: CustomCoverStore = Injekt.get()) : Keyer<DomainManga> {
    override fun key(data: DomainManga, options: Options): String {
        return mangaCoverCacheKey(
            sourceId = data.source,
            mangaId = data.id,
            url = data.thumbnailUrl,
            lastModified = data.coverLastModified,
            useCustomCover = options.extras.getOrDefault(MangaCoverFetcher.USE_CUSTOM_COVER_KEY),
            customCoverCacheKey = customCovers.cacheKey,
        )
    }
}

class MangaCoverKeyer(
    private val customCovers: CustomCoverStore = Injekt.get(),
) : Keyer<MangaCover> {
    override fun key(data: MangaCover, options: Options): String {
        return mangaCoverCacheKey(
            sourceId = data.sourceId,
            mangaId = data.mangaId,
            url = data.url,
            lastModified = data.lastModified,
            useCustomCover = data.useCustomCover &&
                options.extras.getOrDefault(MangaCoverFetcher.USE_CUSTOM_COVER_KEY),
            customCoverCacheKey = customCovers.cacheKey,
        )
    }
}

internal fun mangaCoverCacheKey(
    sourceId: Long,
    mangaId: Long,
    url: String?,
    lastModified: Long,
    useCustomCover: Boolean,
    customCoverCacheKey: String,
): String {
    return "$sourceId;$mangaId;$url;$lastModified;$useCustomCover;$customCoverCacheKey"
}
