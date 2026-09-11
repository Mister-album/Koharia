package koharia.lanraragi

import android.content.Context
import android.content.Intent
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import koharia.source.lanraragi.LanraragiSource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LanraragiEntryOpenManager(
    private val chapters: ChapterRepository = Injekt.get(),
    private val syncChapters: SyncChaptersWithSource = Injekt.get(),
) {
    suspend fun prepareChapter(source: LanraragiSource, manga: Manga): Chapter {
        require(
            lanraragiEntryDestination(manga.url, source.id, LanraragiArchiveOpenMode.READER) ==
                LanraragiEntryDestination.READER,
        )
        chapters.getChapterByUrlAndMangaId(manga.url, manga.id)?.let { return it }
        syncChapters.await(source.getChapterList(manga.toSManga()), manga, source)
        return chapters.getChapterByUrlAndMangaId(manga.url, manga.id)
            ?: throw LanraragiException(LanraragiException.Reason.UNAVAILABLE)
    }

    fun readerIntent(context: Context, manga: Manga, chapter: Chapter, pageIndex: Int? = null): Intent =
        ReaderActivity.newIntent(
            context,
            manga.id,
            chapter.id,
            sourceId = manga.source,
            pageIndex = pageIndex,
            explicitPageSelection = pageIndex != null,
        )
}
