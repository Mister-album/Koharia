package eu.kanade.tachiyomi.data.download.model

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class Download(
    val source: HttpSource,
    val manga: Manga,
    val chapter: Chapter,
    val mode: Mode = Mode.PAGE_CACHE,
) {
    var pages: List<Page>? = null

    @Transient
    private val _rawProgress = MutableStateFlow(0)

    @Transient
    private val _rawDownloadedBytes = MutableStateFlow(0L)

    @Transient
    private val _rawTotalBytes = MutableStateFlow(0L)

    val totalProgress: Int
        get() = when (mode) {
            Mode.PAGE_CACHE -> pages?.sumOf(Page::progress) ?: 0
            Mode.RAW_FILE -> _rawProgress.value
        }

    val downloadedImages: Int
        get() = pages?.count { it.status == Page.State.Ready } ?: 0

    val rawDownloadedBytes: Long
        get() = _rawDownloadedBytes.value

    val rawTotalBytes: Long
        get() = _rawTotalBytes.value

    @Transient
    private val _statusFlow = MutableStateFlow(State.NOT_DOWNLOADED)

    @Transient
    val statusFlow = _statusFlow.asStateFlow()
    var status: State
        get() = _statusFlow.value
        set(status) {
            _statusFlow.value = status
        }

    @Transient
    val progressFlow = flow {
        when (mode) {
            Mode.PAGE_CACHE -> {
                if (pages == null) {
                    emit(0)
                    while (pages == null) {
                        delay(50)
                    }
                }

                val progressFlows = pages!!.map(Page::progressFlow)
                emitAll(combine(progressFlows) { it.average().toInt() })
            }
            Mode.RAW_FILE -> {
                emitAll(_rawProgress.map { it })
            }
        }
    }
        .distinctUntilChanged()
        .debounce(50)

    val progress: Int
        get() {
            val pages = pages ?: return 0
            return pages.map(Page::progress).average().toInt()
        }

    fun updateRawProgress(downloadedBytes: Long, totalBytes: Long) {
        _rawDownloadedBytes.value = downloadedBytes
        _rawTotalBytes.value = totalBytes
        _rawProgress.value = when {
            totalBytes > 0L -> ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            downloadedBytes > 0L -> 100
            else -> 0
        }
    }

    enum class State(val value: Int) {
        NOT_DOWNLOADED(0),
        QUEUE(1),
        DOWNLOADING(2),
        DOWNLOADED(3),
        ERROR(4),
    }

    enum class Mode {
        PAGE_CACHE,
        RAW_FILE,
    }

    companion object {
        suspend fun fromChapterId(
            chapterId: Long,
            mode: Mode = Mode.PAGE_CACHE,
            getChapter: GetChapter = Injekt.get(),
            getManga: GetManga = Injekt.get(),
            sourceManager: SourceManager = Injekt.get(),
        ): Download? {
            val chapter = getChapter.await(chapterId) ?: return null
            val manga = getManga.await(chapter.mangaId) ?: return null
            val source = sourceManager.get(manga.source) as? HttpSource ?: return null

            return Download(source, manga, chapter, mode)
        }
    }
}
