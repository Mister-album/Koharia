package koharia.lanraragi.ui

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.LocalPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import koharia.connection.ChapterFetchOrigin
import koharia.connection.ConnectionLocalFileAdapter
import koharia.connection.ConnectionPageAdapter
import koharia.connection.ConnectionPublicationAdapter
import koharia.connection.ConnectionSource
import koharia.core.archive.epubReader
import koharia.komga.download.KomgaChapterMemo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.storage.extension
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class LanraragiArchivePreviewModel(
    private val mangaId: Long,
    val source: ConnectionSource,
    private val chapterId: Long? = null,
) : StateScreenModel<LanraragiArchivePreviewModel.State>(State()) {
    private val downloads = Injekt.get<DownloadManager>()
    private val context = Injekt.get<android.app.Application>()
    private val cache = Injekt.get<ChapterCache>()
    private val chapters = Injekt.get<ChapterRepository>()
    private val syncChapters = Injekt.get<SyncChaptersWithSource>()
    private var loadJob: Job? = null

    init {
        refresh(false)
        screenModelScope.launch {
            Injekt.get<MangaRepository>().getMangaByIdAsFlow(mangaId).collect { manga ->
                mutableState.update { it.copy(manga = manga) }
            }
        }
        screenModelScope.launch {
            chapters.getChapterByMangaIdAsFlow(mangaId).collect { values ->
                val chapter = if (chapterId == null) values.singleOrNull() else values.find { it.id == chapterId }
                mutableState.update { it.copy(chapter = chapter) }
            }
        }
    }

    fun refresh(forceNetwork: Boolean = true) {
        if (loadJob?.isActive == true) return
        loadJob = screenModelScope.launch(Dispatchers.IO) {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val manga = checkNotNull(Injekt.get<MangaRepository>().getMangaById(mangaId))
                check(manga.source == source.id)
                val chapter = prepareChapter(manga)
                val localFile = (source as? ConnectionLocalFileAdapter)?.localChapterFile(chapter.url)
                val localEpub = localFile?.extension.equals("epub", true)
                val isEpub = localEpub || KomgaChapterMemo.isEpub(chapter.memo) == true ||
                    chapter.url.substringBefore('?').substringAfterLast('.', "").equals("epub", true)
                val previewSupported = when {
                    localEpub -> localFile?.epubReader(context)?.use { it.isImageOnlyPublication() } == true
                    isEpub -> (source as? ConnectionPublicationAdapter)?.canOpenAsPages(chapter.memo) == true
                    else -> true
                }
                if (!previewSupported) {
                    mutableState.update { it.copy(manga = manga, chapter = chapter, previewUnsupported = true) }
                    return@launch
                }
                val downloaded = downloads.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    manga.title,
                    manga.source,
                    skipCache = true,
                )
                val pages = loadPages(manga, chapter, downloaded, forceNetwork)
                mutableState.update {
                    it.copy(
                        manga = manga,
                        chapter = chapter,
                        pages = pages.map { page -> LanraragiPreviewImage(manga, chapter, page, downloaded) },
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = error) }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    private suspend fun prepareChapter(manga: Manga): Chapter {
        chapterId?.let { selected ->
            return checkNotNull(chapters.getChapterById(selected)) {
                "Unable to prepare entry for preview"
            }.also { check(it.mangaId == manga.id) }
        }
        chapters.getChapterByMangaId(manga.id).singleOrNull()?.let { return it }
        syncChapters.fetchAndSync(manga, source, ChapterFetchOrigin.OPEN)
        return chapters.getChapterByMangaId(manga.id).singleOrNull()
            ?: error("Unable to prepare entry for preview")
    }

    private suspend fun loadPages(
        manga: Manga,
        chapter: Chapter,
        downloaded: Boolean,
        forceNetwork: Boolean,
    ): List<Page> {
        if (downloaded) {
            val loader = DownloadPageLoader(ReaderChapter(chapter), manga, source, downloads, Injekt.get())
            return try {
                loader.getPages().map { Page(it.index) }
            } finally {
                loader.recycle()
            }
        }
        if (source is ConnectionLocalFileAdapter) {
            val loader = LocalPageLoader(ReaderChapter(chapter), source, source)
            return try {
                loader.getPages().map { Page(it.index) }
            } finally {
                loader.recycle()
            }
        }
        val adapter = source as? ConnectionPageAdapter ?: error("Page preview is unsupported")
        val cached = cache.getPageListFromCache(chapter)?.takeUnless { forceNetwork }
        val list = cached ?: adapter.getConnectionPageList(chapter.toSChapter(), forceNetwork).also {
            cache.putPageListToCache(chapter, it)
        }
        return adapter.decoratePageImageUrls(list.pages, chapter.memo)
    }

    suspend fun currentChapter(): Chapter =
        checkNotNull(chapters.getChapterById(checkNotNull(state.value.chapter).id))
            .also { check(it.mangaId == mangaId) }

    fun download() {
        val manga = state.value.manga ?: return
        val chapter = state.value.chapter ?: return
        if (source is HttpSource) downloads.downloadChapters(manga, listOf(chapter))
    }

    data class State(
        val manga: Manga? = null,
        val chapter: Chapter? = null,
        val pages: List<LanraragiPreviewImage> = emptyList(),
        val loading: Boolean = true,
        val previewUnsupported: Boolean = false,
        val error: Throwable? = null,
    )
}
