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
        return "${data.source};${data.id};${data.thumbnailUrl};${data.coverLastModified};" +
            "${options.extras.getOrDefault(MangaCoverFetcher.USE_CUSTOM_COVER_KEY)};${customCovers.cacheKey}"
    }
}

class MangaCoverKeyer(
    private val customCovers: CustomCoverStore = Injekt.get(),
) : Keyer<MangaCover> {
    override fun key(data: MangaCover, options: Options): String {
        return "${data.sourceId};${data.mangaId};${data.url};${data.lastModified};${data.useCustomCover};" +
            "${options.extras.getOrDefault(MangaCoverFetcher.USE_CUSTOM_COVER_KEY)};${customCovers.cacheKey}"
    }
}
