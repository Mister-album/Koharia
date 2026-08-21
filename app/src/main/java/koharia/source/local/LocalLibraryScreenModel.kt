package koharia.source.local

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.paging.PagingData
import androidx.paging.cachedIn
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.presentation.library.components.MangaReadProgressDisplay
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.util.editCover
import koharia.connection.ConnectionChapterMetadata
import koharia.connection.ConnectionLibraryRefreshAdapter
import koharia.connection.ConnectionLibraryShelf
import koharia.connection.ConnectionLibraryShelfAdapter
import koharia.connection.ConnectionSeriesCoverAdapter
import koharia.connection.LibraryContentScope
import koharia.domain.epub.interactor.GetEpubProgress
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import kotlin.math.roundToLong

class LocalLibraryScreenModel(
    private val sourceId: Long,
    private val scope: LibraryContentScope,
    initialQuery: String?,
    private val sourceManager: SourceManager,
    sourcePreferences: SourcePreferences,
    private val mangaRepository: MangaRepository,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val getEpubProgress: GetEpubProgress,
    private val libraryPreferences: LibraryPreferences,
    private val entryOpenManager: LocalLibraryEntryOpenManager,
    private val updateManga: UpdateManga,
    private val coverCache: CoverCache,
) : StateScreenModel<LocalLibraryScreenModel.State>(
    State(
        toolbarQuery = initialQuery,
        submittedQuery = initialQuery.orEmpty(),
    ),
) {
    val source = sourceManager.getOrStub(sourceId)
    var displayMode by sourcePreferences.sourceDisplayMode.asState(screenModelScope)

    private val submittedQuery = MutableStateFlow(initialQuery.orEmpty())
    private val appliedFilters = MutableStateFlow(LocalLibraryFilters())
    private val selectedBookshelfId = MutableStateFlow<String?>(null)
    private val refreshSignal = MutableStateFlow(0)
    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    private val readProgressRefreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val localReadProgress = MutableStateFlow<Map<String, MangaReadProgress>>(emptyMap())

    val events = eventChannel.receiveAsFlow()
    val readProgressByUrl: StateFlow<Map<String, MangaReadProgress>> = localReadProgress.asStateFlow()

    init {
        screenModelScope.launchIO {
            for (ignored in readProgressRefreshRequests) {
                refreshLocalReadProgress()
            }
        }
        if (libraryPreferences.showLibraryReadProgress.get()) {
            refreshReadProgress()
        }
        screenModelScope.launchIO {
            libraryPreferences.showLibraryReadProgress.changes().collect { enabled ->
                if (enabled) {
                    refreshReadProgress()
                } else {
                    localReadProgress.value = emptyMap()
                }
            }
        }
        (source as? LocalFolderSource)?.let { localSource ->
            screenModelScope.launchIO {
                if (localSource.needsInitialScan()) refresh()
            }
        }
        (source as? ConnectionLibraryRefreshAdapter)?.let { refreshAdapter ->
            screenModelScope.launchIO {
                refreshAdapter.libraryRefreshes.collect {
                    refreshSignal.value += 1
                    if (libraryPreferences.showLibraryReadProgress.get()) {
                        refreshReadProgress()
                    }
                }
            }
        }
        (source as? ConnectionLibraryShelfAdapter)?.let { shelfAdapter ->
            screenModelScope.launchIO {
                shelfAdapter.libraryShelves.collect { shelves ->
                    val visibleShelves = shelves.filter { shelf ->
                        scope == LibraryContentScope.ALL || shelf.contentScope == scope
                    }
                    if (selectedBookshelfId.value !in visibleShelves.map { it.id }) {
                        selectedBookshelfId.value = null
                    }
                    mutableState.update { it.copy(bookshelves = visibleShelves) }
                    refreshSignal.value += 1
                }
            }
        }
    }

    fun refreshReadProgress() {
        readProgressRefreshRequests.trySend(Unit)
    }

    private suspend fun refreshLocalReadProgress() {
        val localSource = source as? LocalFolderSource ?: return
        if (!libraryPreferences.showLibraryReadProgress.get()) return

        val mangas = mangaRepository.getMangaBySourceId(sourceId)
        val chaptersByMangaId = getChaptersByMangaId.await(mangas.map(Manga::id))
        val readProgressIndexes = localSource.readProgressIndexes(mangas.map(Manga::url))
        val entries = mangas.map { manga ->
            val chapters = chaptersByMangaId[manga.id].orEmpty()
            val index = readProgressIndexes[manga.url.trimEnd('/')]
            LocalReadProgressEntry(
                manga = manga,
                chapters = chapters,
                indexedChapterCount = index?.indexedChapterCount ?: chapters.size,
                isIndividualFile = index?.isIndividualFile == true,
            )
        }
        val epubProgressByChapterId = getEpubProgress.await(
            entries.asSequence()
                .filter { it.isIndividualFile }
                .flatMap { it.chapters.asSequence() }
                .mapTo(mutableSetOf(), Chapter::id),
        )
        val progressByUrl = entries.mapNotNull { entry ->
            val epubProgression = entry.chapters.firstOrNull()?.id?.let { chapterId ->
                epubProgressByChapterId[chapterId]?.progression
            }
            val documentPageCount = entry.chapters.firstOrNull()?.let { chapter ->
                chapter.memo.let(ConnectionChapterMetadata::pagesCount)
                    ?: chapter.lastPageRead.takeIf { it > 0L }
                        ?.let { localSource.documentPageCount(chapter.url) }
                        ?.also { pageCount ->
                            updateChapter.await(
                                ChapterUpdate(
                                    id = chapter.id,
                                    memo = ConnectionChapterMetadata.withPagesCount(chapter.memo, pageCount),
                                ),
                            )
                        }
            }
            buildLocalReadProgress(
                indexedChapterCount = entry.indexedChapterCount,
                chapters = entry.chapters,
                isIndividualFile = entry.isIndividualFile,
                epubProgression = epubProgression,
                documentPageCount = documentPageCount,
            )?.let { progress ->
                entry.manga.url.trimEnd('/') to progress
            }
        }.toMap()
        if (libraryPreferences.showLibraryReadProgress.get()) {
            localReadProgress.value = progressByUrl
        }
    }

    private val browseRequests = combine(
        submittedQuery,
        appliedFilters,
        selectedBookshelfId,
        refreshSignal,
    ) { query, filters, bookshelfId, refresh ->
        BrowseRequest(query, filters, bookshelfId, refresh)
    }

    val mangaPagerFlow: Flow<PagingData<StateFlow<Manga>>> = browseRequests
        .combine(mangaRepository.getMangaBySourceIdAsFlow(sourceId)) { request, mangas ->
            request to mangas
        }
        .mapLatest { (request, mangas) ->
            val filteredMangas = (source as? LocalFolderSource)?.browseIndexedLibrary(
                mangas = mangas,
                query = request.query,
                scope = scope,
                filters = request.filters,
                bookshelfId = request.bookshelfId,
            ).orEmpty()
            PagingData.from(
                filteredMangas.map { manga -> MutableStateFlow(manga) as StateFlow<Manga> },
            )
        }.cachedIn(screenModelScope)

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
    }

    fun search(query: String) {
        val normalized = query.trim()
        submittedQuery.value = normalized
        mutableState.update {
            it.copy(
                toolbarQuery = query,
                submittedQuery = normalized,
            )
        }
    }

    fun exitSearch() {
        submittedQuery.value = ""
        mutableState.update {
            it.copy(
                toolbarQuery = null,
                submittedQuery = "",
            )
        }
    }

    fun refresh() {
        if (state.value.isRefreshing) return
        val refreshAdapter = source as? ConnectionLibraryRefreshAdapter
        if (refreshAdapter == null) {
            refreshSignal.value += 1
            return
        }
        mutableState.update { it.copy(isRefreshing = true, refreshError = null) }
        screenModelScope.launchIO {
            try {
                val result = runCatching { refreshAdapter.refreshLibrary().getOrThrow() }
                if (result.isFailure) {
                    refreshSignal.value += 1
                }
                mutableState.update {
                    it.copy(refreshError = result.exceptionOrNull())
                }
            } finally {
                mutableState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun openFilterDialog() {
        mutableState.update { it.copy(dialog = Dialog.Filter) }
    }

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun applyFilters(filters: LocalLibraryFilters) {
        appliedFilters.value = filters
        mutableState.update { it.copy(filters = filters, dialog = null) }
    }

    fun selectBookshelf(bookshelfId: String?) {
        selectedBookshelfId.value = bookshelfId
        mutableState.update { it.copy(selectedBookshelfId = bookshelfId) }
    }

    fun openMoveToBookshelfDialog(manga: Manga) {
        val adapter = source as? ConnectionLibraryShelfAdapter ?: return
        screenModelScope.launchIO {
            val currentShelfId = adapter.currentLibraryShelfId(manga.url) ?: return@launchIO
            val shelves = adapter.compatibleLibraryShelves(manga.url)
            mutableState.update {
                it.copy(dialog = Dialog.MoveToBookshelf(manga, shelves, currentShelfId))
            }
        }
    }

    fun openEntryActions(manga: Manga) {
        val localSource = source as? LocalFolderSource
        if (localSource?.isIndividualFileEntry(manga.url) == true) {
            mutableState.update { it.copy(dialog = Dialog.EntryActions(manga)) }
        } else {
            openMoveToBookshelfDialog(manga)
        }
    }

    fun useFirstItemAsCover(manga: Manga) {
        val adapter = source as? ConnectionSeriesCoverAdapter ?: return
        screenModelScope.launchIO {
            runCatching {
                val cover = adapter.loadSuggestedSeriesCover(manga.url)
                    ?: error("No usable cover image was found")
                cover.inputStream().use {
                    manga.editCover(it, updateManga, coverCache, sourceManager)
                }
            }.fold(
                onSuccess = { eventChannel.send(Event.CoverUpdated) },
                onFailure = { eventChannel.send(Event.CoverFailed(it)) },
            )
            mutableState.update { it.copy(dialog = null) }
        }
    }

    fun openLibraryEntry(manga: Manga): Boolean {
        val localSource = source as? LocalFolderSource ?: return false
        if (!localSource.isIndividualFileEntry(manga.url)) return false
        screenModelScope.launchIO {
            runCatching {
                entryOpenManager.prepareChapter(localSource, manga)
            }.fold(
                onSuccess = { eventChannel.send(Event.OpenChapter(it)) },
                onFailure = { eventChannel.send(Event.OpenFailed(it)) },
            )
        }
        return true
    }

    fun moveToBookshelf(manga: Manga, bookshelfId: String) {
        val adapter = source as? ConnectionLibraryShelfAdapter ?: return
        screenModelScope.launchIO {
            if (adapter.moveMangaToLibraryShelf(manga.url, bookshelfId).isSuccess) {
                refreshSignal.value += 1
            }
            mutableState.update { it.copy(dialog = null) }
        }
    }

    @Immutable
    data class State(
        val toolbarQuery: String? = null,
        val submittedQuery: String = "",
        val filters: LocalLibraryFilters = LocalLibraryFilters(),
        val bookshelves: List<ConnectionLibraryShelf> = emptyList(),
        val selectedBookshelfId: String? = null,
        val isRefreshing: Boolean = false,
        val refreshError: Throwable? = null,
        val dialog: Dialog? = null,
    )

    sealed interface Dialog {
        data object Filter : Dialog
        data class MoveToBookshelf(
            val manga: Manga,
            val bookshelves: List<ConnectionLibraryShelf>,
            val currentBookshelfId: String,
        ) : Dialog
        data class EntryActions(val manga: Manga) : Dialog
    }

    sealed interface Event {
        data class OpenChapter(val chapter: Chapter) : Event
        data class OpenFailed(val error: Throwable) : Event
        data object CoverUpdated : Event
        data class CoverFailed(val error: Throwable) : Event
    }

    private data class BrowseRequest(
        val query: String,
        val filters: LocalLibraryFilters,
        val bookshelfId: String?,
        @Suppress("unused") val refresh: Int,
    )

    private data class LocalReadProgressEntry(
        val manga: Manga,
        val chapters: List<Chapter>,
        val indexedChapterCount: Int,
        val isIndividualFile: Boolean,
    )
}

internal fun buildLocalReadProgress(
    indexedChapterCount: Int,
    chapters: List<Chapter>,
    isIndividualFile: Boolean = false,
    epubProgression: Double? = null,
    documentPageCount: Int? = null,
): MangaReadProgress? {
    val totalChapterCount = if (indexedChapterCount > 0) indexedChapterCount else chapters.size
    if (totalChapterCount <= 0) return null

    if (isIndividualFile) {
        val chapter = chapters.firstOrNull { it.read } ?: chapters.maxByOrNull { it.lastPageRead }
        return when {
            chapter?.read == true -> MangaReadProgress(
                readCount = 100,
                totalChapterCount = 100,
                display = MangaReadProgressDisplay.PERCENTAGE,
            )
            epubProgression != null -> MangaReadProgress(
                readCount = (epubProgression.coerceIn(0.0, 1.0) * 100).roundToLong(),
                totalChapterCount = 100,
                display = MangaReadProgressDisplay.PERCENTAGE,
            )
            documentPageCount != null && chapter != null && chapter.lastPageRead > 0L -> MangaReadProgress(
                readCount = (((chapter.lastPageRead + 1).toDouble() / documentPageCount) * 100)
                    .roundToLong()
                    .coerceIn(0L, 100L),
                totalChapterCount = 100,
                display = MangaReadProgressDisplay.PERCENTAGE,
            )
            // Do not expose a page number as a library progress label. Page counts are loaded
            // for supported single-file formats before this function is called; if counting
            // fails, omit the progress until the next refresh rather than showing a misleading
            // value such as "Page 12".
            chapter != null && chapter.lastPageRead > 0L -> null
            else -> MangaReadProgress(
                readCount = 0,
                totalChapterCount = 100,
                display = MangaReadProgressDisplay.PERCENTAGE,
            )
        }
    }

    return MangaReadProgress(
        readCount = chapters.count { it.read }.coerceAtMost(totalChapterCount).toLong(),
        totalChapterCount = totalChapterCount.toLong(),
    )
}
