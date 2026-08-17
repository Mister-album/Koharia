package koharia.connection

import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

fun Manga.isConnectionLibraryEntry(sourceManager: SourceManager): Boolean {
    if (favorite) return true
    val behavior = (sourceManager.get(source) as? ConnectionMangaBehaviorAdapter)?.mangaBehavior
    return behavior?.providerManagedLibrary == true
}

fun SourceManager.providerManagedLibrarySourceIds(): Set<Long> {
    return getCatalogueSources()
        .filter { source ->
            (source as? ConnectionMangaBehaviorAdapter)?.mangaBehavior?.providerManagedLibrary == true
        }
        .mapTo(mutableSetOf()) { it.id }
}

suspend fun MangaRepository.getProviderManagedLibraryEntries(
    sourceManager: SourceManager,
): List<Manga> {
    return sourceManager.providerManagedLibrarySourceIds()
        .flatMap { sourceId ->
            val entries = getMangaBySourceId(sourceId)
            (sourceManager.get(sourceId) as? ConnectionLibraryMembershipAdapter)
                ?.filterLibraryEntries(entries)
                ?: entries
        }
}
