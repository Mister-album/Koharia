package koharia.lanraragi.ui

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import koharia.lanraragi.LanraragiEntryOpenManager
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class LanraragiArchivePreviewModel(
    private val mangaId: Long,
    val source: LanraragiSource,
) : StateScreenModel<LanraragiArchivePreviewModel.State>(State()) {
    val opener = LanraragiEntryOpenManager()
    private val downloads = Injekt.get<DownloadManager>()
    private val cache = Injekt.get<ChapterCache>()
    private var loadJob: Job? = null

    init {
        refresh(false)
        screenModelScope.launch {
            Injekt.get<MangaRepository>().getMangaByIdAsFlow(mangaId).collect { manga ->
                mutableState.update { it.copy(manga = manga) }
            }
        }
        screenModelScope.launch {
            Injekt.get<ChapterRepository>().getChapterByMangaIdAsFlow(mangaId).collect { chapters ->
                val chapter = chapters.singleOrNull() ?: return@collect
                mutableState.update { it.copy(chapter = chapter) }
            }
        }
    }

    fun refresh(forceNetwork: Boolean = true) {
        if (loadJob?.isActive == true) return
        loadJob = screenModelScope.launch(Dispatchers.IO) {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val manga = Injekt.get<MangaRepository>().getMangaById(mangaId)
                val chapter = opener.prepareChapter(source, manga)
                mutableState.update { it.copy(manga = manga, chapter = chapter) }
                val downloaded = downloads.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    manga.title,
                    manga.source,
                    skipCache = true,
                )
                val pages = if (downloaded) {
                    val loader = DownloadPageLoader(ReaderChapter(chapter), manga, source, downloads, Injekt.get())
                    try {
                        loader.getPages().map { Page(it.index) }
                    } finally {
                        loader.recycle()
                    }
                } else {
                    val list = cache.getPageListFromCache(chapter)?.takeUnless { forceNetwork }
                        ?: source.getConnectionPageList(chapter.toSChapter(), false).also {
                            cache.putPageListToCache(chapter, it)
                        }
                    source.decoratePageImageUrls(list.pages, chapter.memo)
                }
                mutableState.update {
                    it.copy(pages = pages.map { page -> LanraragiPreviewImage(manga, chapter, page, downloaded) })
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = error) }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    suspend fun currentChapter(): Chapter =
        Injekt.get<ChapterRepository>().getChapterById(checkNotNull(state.value.chapter).id)
            ?: checkNotNull(state.value.chapter)

    fun download() {
        val manga = state.value.manga ?: return
        val chapter = state.value.chapter ?: return
        downloads.downloadChapters(manga, listOf(chapter))
    }

    data class State(
        val manga: Manga? = null,
        val chapter: Chapter? = null,
        val pages: List<LanraragiPreviewImage> = emptyList(),
        val loading: Boolean = true,
        val error: Throwable? = null,
    )
}
