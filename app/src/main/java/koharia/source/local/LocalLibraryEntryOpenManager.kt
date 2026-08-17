package koharia.source.local

import android.content.Context
import android.content.Intent
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import koharia.epub.EpubReaderLauncher
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga

class LocalLibraryEntryOpenManager(
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val chapterRepository: ChapterRepository,
    private val epubReaderLauncher: EpubReaderLauncher,
) {

    suspend fun prepareChapter(source: LocalFolderSource, manga: Manga): Chapter {
        require(source.isIndividualFileEntry(manga.url)) { "Local entry is not an individual file" }
        val sourceChapters = source.getChapterList(manga.toSManga())
        syncChaptersWithSource.await(sourceChapters, manga, source, manualFetch = false)
        return chapterRepository.getChapterByMangaId(manga.id).singleOrNull()
            ?: error("Unable to prepare local media for reading")
    }

    suspend fun prepareIntent(context: Context, source: LocalFolderSource, manga: Manga): Intent {
        val chapter = prepareChapter(source, manga)
        return epubReaderLauncher.resolveIntent(context, manga.id, chapter.id)
    }
}
