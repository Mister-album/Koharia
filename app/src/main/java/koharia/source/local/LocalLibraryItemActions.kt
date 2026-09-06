package koharia.source.local

import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
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
    suspend fun markRead(source: LocalFolderSource, manga: Manga, read: Boolean) {
        val sourceChapters = source.getChapterList(manga.toSManga())
        syncChaptersWithSource.await(sourceChapters, manga, source, manualFetch = false)
        val chapters = chapterRepository.getChapterByMangaId(manga.id)
        check(chapters.isNotEmpty()) { "No local chapters available" }
        when (val result = setReadStatus.await(read, *chapters.toTypedArray())) {
            is SetReadStatus.Result.InternalError -> throw result.error
            else -> Unit
        }
        if (!read) chapters.forEach { epubProgressRepository.deleteProgress(it.id) }
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
