package koharia.source.local

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.paging.LoadState
import androidx.paging.LoadStates
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
import kotlinx.coroutines.CancellationException
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
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import kotlin.math.roundToLong

internal class LocalLibraryScreenModel(
    private val sourceId: Long,
    private val scope: LibraryContentScope,
    initialQuery: String?,
    private val sourceManager: SourceManager,
    sourcePreferences: SourcePreferences,
    private val mangaRepository: MangaRepository,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getEpubProgress: GetEpubProgress,
    private val libraryPreferences: LibraryPreferences,
    private val entryOpenManager: LocalLibraryEntryOpenManager,
    private val updateManga: UpdateManga,
    private val coverCache: CoverCache,
    private val itemActions: LocalLibraryItemActions,
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
            val documentPageCount = entry.chapters.firstOrNull()?.memo?.let(ConnectionChapterMetadata::pagesCount)
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
                sourceLoadStates = LoadStates(
                    refresh = LoadState.NotLoading(false),
                    prepend = LoadState.NotLoading(true),
                    append = LoadState.NotLoading(true),
                ),
            )
        }
        .cachedIn(screenModelScope)

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
        mutableState.update { it.copy(isRefreshing = true) }
        screenModelScope.launchIO { refreshLibrary() }
    }

    private suspend fun refreshLibrary() {
        val refreshAdapter = source as? ConnectionLibraryRefreshAdapter
        if (refreshAdapter == null) {
            refreshSignal.value += 1
            mutableState.update { it.copy(isRefreshing = false) }
            return
        }
        mutableState.update { it.copy(isRefreshing = true, refreshError = null) }
        try {
            refreshAdapter.refreshLibrary().getOrThrow()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            refreshSignal.value += 1
            mutableState.update { it.copy(refreshError = error) }
        } finally {
            mutableState.update { it.copy(isRefreshing = false) }
        }
    }

    fun openFilterDialog() {
        mutableState.update { it.copy(dialog = Dialog.Filter) }
    }

    fun dismissDialog() {
        if (state.value.isBusy) return
        mutableState.update { it.copy(dialog = null) }
    }

    fun applyFilters(filters: LocalLibraryFilters) {
        appliedFilters.value = filters
        mutableState.update { it.copy(filters = filters, dialog = null) }
    }

    fun selectBookshelf(bookshelfId: String?) {
        if (state.value.isBusy) return
        clearSelection()
        selectedBookshelfId.value = bookshelfId
        mutableState.update { it.copy(selectedBookshelfId = bookshelfId) }
    }

    fun openMoveToBookshelfDialog(mangas: List<Manga>) {
        val adapter = source as? ConnectionLibraryShelfAdapter ?: return
        if (mangas.isEmpty() || state.value.isBusy) return
        mutableState.update { it.copy(dialog = null, isUpdatingItems = true) }
        screenModelScope.launchIO {
            try {
                val shelves = commonLocalLibraryShelves(adapter, mangas)
                if (shelves.isEmpty()) {
                    eventChannel.send(Event.NoCompatibleShelf)
                    return@launchIO
                }
                val currentIds = mangas.map { adapter.currentLibraryShelfId(it.url) }.distinct()
                mutableState.update {
                    it.copy(dialog = Dialog.MoveToBookshelf(mangas, shelves, currentIds.singleOrNull().orEmpty()))
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                eventChannel.send(Event.ItemActionFailed)
            } finally {
                mutableState.update { it.copy(isUpdatingItems = false) }
            }
        }
    }

    fun openEntryActions(manga: Manga) {
        if (state.value.isBusy) return
        if (state.value.selectedMangas.isNotEmpty()) return toggleSelection(manga)
        mutableState.update { it.copy(dialog = Dialog.EntryActions(manga)) }
    }

    fun toggleSelection(manga: Manga) {
        if (state.value.isBusy) return
        mutableState.update { state ->
            val selected = state.selectedMangas
            state.copy(
                dialog = null,
                selectedMangas = if (selected.any { it.id == manga.id }) {
                    selected.filterNot { it.id == manga.id }
                } else {
                    selected + manga
                },
            )
        }
    }

    fun selectAll(mangas: List<Manga>) {
        if (state.value.isBusy) return
        mutableState.update { it.copy(selectedMangas = mangas.distinctBy(Manga::id)) }
    }

    fun clearSelection() {
        if (state.value.isBusy) return
        mutableState.update { it.copy(selectedMangas = emptyList()) }
    }

    fun requestDeletion(mangas: List<Manga>) {
        val localSource = source as? LocalFolderSource ?: return
        if (mangas.isEmpty() || state.value.isBusy) return
        mutableState.update { it.copy(dialog = null, isPreparingDeletion = true) }
        screenModelScope.launchIO {
            try {
                val plan = localSource.prepareFileDeletion(mangas)
                mutableState.update { it.copy(dialog = Dialog.DeleteFiles(plan)) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                eventChannel.send(Event.DeleteFailed)
            } finally {
                mutableState.update { it.copy(isPreparingDeletion = false) }
            }
        }
    }

    fun confirmDeletion() {
        val localSource = source as? LocalFolderSource ?: return
        val dialog = state.value.dialog as? Dialog.DeleteFiles ?: return
        if (state.value.isBusy) return
        mutableState.update { it.copy(isDeleting = true) }
        screenModelScope.launchIO {
            try {
                val result = localSource.deleteLocalFiles(dialog.plan)
                mutableState.update { it.copy(selectedMangas = result.failed) }
                eventChannel.send(Event.FilesDeleted(result.deleted.size, result.failed.size))
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                eventChannel.send(Event.DeleteFailed)
            } finally {
                refreshSignal.value += 1
                mutableState.update { it.copy(isDeleting = false, dialog = null) }
            }
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

    fun moveToBookshelf(mangas: List<Manga>, bookshelfId: String) {
        val adapter = source as? ConnectionLibraryShelfAdapter ?: return
        updateItems(mangas) { adapter.moveMangaToLibraryShelf(it.url, bookshelfId).getOrThrow() }
    }

    fun markRead(mangas: List<Manga>, read: Boolean) {
        val localSource = source as? LocalFolderSource ?: return
        updateItems(mangas) { itemActions.markRead(localSource, it, read) }
    }

    private fun updateItems(mangas: List<Manga>, action: suspend (Manga) -> Unit) {
        if (mangas.isEmpty() || state.value.isBusy) return
        mutableState.update { it.copy(dialog = null, isUpdatingItems = true) }
        screenModelScope.launchIO {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val failed = mangas.distinctBy(Manga::id).filter { manga ->
                        runCatching { action(manga) }.isFailure
                    }
                    mutableState.update { it.copy(selectedMangas = failed) }
                    eventChannel.send(Event.ItemsUpdated(mangas.distinctBy(Manga::id).size - failed.size, failed.size))
                }
            } finally {
                refreshSignal.value += 1
                refreshReadProgress()
                mutableState.update { it.copy(isUpdatingItems = false) }
            }
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
        val selectedMangas: List<Manga> = emptyList(),
        val isPreparingDeletion: Boolean = false,
        val isDeleting: Boolean = false,
        val isUpdatingItems: Boolean = false,
    ) {
        val isBusy: Boolean get() = isDeleting || isPreparingDeletion || isUpdatingItems
    }

    sealed interface Dialog {
        data object Filter : Dialog
        data class MoveToBookshelf(
            val mangas: List<Manga>,
            val bookshelves: List<ConnectionLibraryShelf>,
            val currentBookshelfId: String,
        ) : Dialog
        data class EntryActions(val manga: Manga) : Dialog
        data class DeleteFiles(val plan: LocalLibraryDeletionPlan) : Dialog
    }

    sealed interface Event {
        data class OpenChapter(val chapter: Chapter) : Event
        data class OpenFailed(val error: Throwable) : Event
        data object CoverUpdated : Event
        data class CoverFailed(val error: Throwable) : Event
        data object ItemActionFailed : Event
        data object NoCompatibleShelf : Event
        data class ItemsUpdated(val updated: Int, val failed: Int) : Event
        data object DeleteFailed : Event
        data class FilesDeleted(val deleted: Int, val failed: Int) : Event
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
            // Unknown page counts stay unknown on the shelf; opening the reader can populate
            // the cached count without parsing documents just to display a progress badge.
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
