package koharia.source.local

import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import koharia.connection.ChapterFetchOrigin
import koharia.connection.ConnectionLibraryShelf
import koharia.connection.ConnectionLibraryShelfAdapter
import koharia.domain.epub.repository.EpubProgressRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga

internal class LocalLibraryItemActions(
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val chapterRepository: ChapterRepository,
    private val setReadStatus: SetReadStatus,
    private val epubProgressRepository: EpubProgressRepository,
) {
    suspend fun markRead(source: LocalFolderSource, manga: Manga, read: Boolean): List<SetReadStatus.Result> {
        val targets = if (source.isFolderContainer(manga.url)) {
            source.readableDescendantMangas(manga.url)
        } else {
            listOf(manga)
        }
        return targets.distinctBy(Manga::id).map { target ->
            syncChaptersWithSource.fetchAndSync(target, source, ChapterFetchOrigin.PROGRESS)
            val chapters = chapterRepository.getChapterByMangaId(target.id)
            val result = setReadStatus.await(read, *chapters.toTypedArray())
            if (!read &&
                result is SetReadStatus.Result.Completed
            ) {
                chapters.forEach { epubProgressRepository.deleteProgress(it.id) }
            }
            result
        }
    }
}

internal suspend fun commonLocalLibraryShelves(
    adapter: ConnectionLibraryShelfAdapter,
    mangas: List<Manga>,
): List<ConnectionLibraryShelf> {
    if (mangas.isEmpty()) return emptyList()
    var shelves = adapter.compatibleLibraryShelves(mangas.first().url)
    for (manga in mangas.drop(1)) {
        val compatibleIds = adapter.compatibleLibraryShelves(manga.url).mapTo(mutableSetOf()) { it.id }
        shelves = shelves.filter { it.id in compatibleIds }
        if (shelves.isEmpty()) break
    }
    return shelves
}
