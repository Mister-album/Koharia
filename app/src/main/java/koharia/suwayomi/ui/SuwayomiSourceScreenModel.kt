package koharia.suwayomi.ui

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiBrowseFilters
import koharia.suwayomi.SuwayomiBrowseSort
import koharia.suwayomi.SuwayomiFilterChange
import koharia.suwayomi.SuwayomiFilterState
import koharia.suwayomi.SuwayomiListingChangedException
import koharia.suwayomi.SuwayomiManga
import koharia.suwayomi.SuwayomiSourceInfo
import koharia.suwayomi.SuwayomiSourceMangaType
import koharia.suwayomi.activeSuwayomiBrowseFilterCount
import koharia.suwayomi.activeSuwayomiFilterCount
import koharia.suwayomi.applySuwayomiBrowseFilters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Remote listing for one Suwayomi source. Modes follow the server's `FetchSourceMangaType`:
 * popular and latest pages come from the source itself, while a submitted query switches to the
 * source's search endpoint. The screen holds only ids, so the remote identity is fetched here and
 * never has to be serialized with the screen.
 *
 * Paging is owned by [Pager]; the model only holds the parameters of the current listing, so a
 * query, mode or filter change produces exactly one new listing.
 */
class SuwayomiSourceScreenModel(
    val source: SuwayomiSource,
    private val infoId: Long,
    private val initialMode: SuwayomiSourceMangaType,
) : StateScreenModel<SuwayomiSourceScreenModel.State>(
    State(
        mode = initialMode,
        displayMode = Injekt.get<SourcePreferences>().sourceDisplayMode.get(),
        info = source.session().catalog.sourceInventory.state.value.value?.firstOrNull { it.id == infoId },
        pinned =
        source.session().catalog.sourceInventory.state.value.value?.firstOrNull { it.id == infoId }?.isPinned ==
            true,
    ),
) {
    private val session = source.session()
    init {
        bindSession(session)
    }
    private val repository: MangaRepository = Injekt.get()
    private val sourcePreferences: SourcePreferences = Injekt.get()
    private val errorChannel = Channel<Throwable>(Channel.BUFFERED)
    val errors = errorChannel.receiveAsFlow()
    private var infoJob: Job? = null
    private var pinJob: Job? = null
    private var refreshJob: Job? = null

    init {
        screenModelScope.launch {
            sourcePreferences.sourceDisplayMode.changes().collect { mode ->
                mutableState.update { it.copy(displayMode = mode) }
            }
        }
        loadInfo()
    }

    private fun loadInfo() {
        if (infoJob?.isActive == true) return
        infoJob = screenModelScope.launch(Dispatchers.IO) {
            try {
                session.checkActive()
                val info = session.catalog.source(infoId)
                require(info.id == infoId) { "Suwayomi returned another source" }
                session.checkActive()
                mutableState.update { it.copy(info = info, pinned = info.isPinned, loadingInfo = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(loadingInfo = false, infoError = error) }
            }
            loadFilters()
        }
    }

    /** Filters are optional; a source without them still browses. */
    private fun loadFilters() {
        screenModelScope.launch(Dispatchers.IO) {
            val filters = runCatching {
                session.checkActive()
                session.catalog.sourceFilters(infoId)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                errorChannel.send(error)
                emptyList()
            }
            session.checkActive()
            mutableState.update { it.copy(filters = SuwayomiFilterState.of(filters)) }
        }
    }

    fun setPinned(pinned: Boolean) {
        if (pinJob?.isActive == true) return
        pinJob = screenModelScope.launch {
            val previous = state.value.pinned
            mutableState.update { it.copy(pinned = pinned) }
            try {
                session.checkActive()
                source.setSourcePinned(infoId, pinned)
                session.checkActive()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(pinned = previous) }
                errorChannel.send(error)
            }
        }
    }

    /** Applies the sheet's filter values and restarts the listing with them. */
    fun applyFilters(draft: SuwayomiFilterDraft) = mutableState.update {
        val changes = SuwayomiFilterState.changes(draft.sourceFilters)
        it.copy(
            filters = draft.sourceFilters,
            appliedFilters = changes,
            browseFilters = draft.browseFilters,
            sort = draft.sort,
            // The server only forwards filters to the source's search endpoint, so an applied source
            // filter has to move the listing there or it would silently do nothing.
            mode = if (changes.isNotEmpty()) SuwayomiSourceMangaType.SEARCH else it.mode,
            appliedQuery = if (changes.isNotEmpty()) "" else it.appliedQuery,
            listingGeneration = it.listingGeneration + 1,
        )
    }

    fun resetFilters() = mutableState.update {
        it.copy(
            filters = SuwayomiFilterState.of(it.filters.map { filter -> filter.filter }),
            appliedFilters = emptyList(),
            browseFilters = SuwayomiBrowseFilters.NONE,
            sort = SuwayomiBrowseSort.SOURCE,
            listingGeneration = it.listingGeneration + 1,
        )
    }

    /** Runs a search with the given query; a blank query returns to the source's popular listing. */
    fun search(query: String) = mutableState.update {
        val trimmed = query.trim()
        it.copy(
            query = trimmed,
            appliedQuery = trimmed,
            mode = if (trimmed.isEmpty()) SuwayomiSourceMangaType.POPULAR else SuwayomiSourceMangaType.SEARCH,
            listingGeneration = it.listingGeneration + 1,
        )
    }

    fun setMode(mode: SuwayomiSourceMangaType) = mutableState.update {
        if (mode == SuwayomiSourceMangaType.LATEST && !it.supportsLatest) {
            it
        } else {
            it.copy(mode = mode, appliedQuery = "", query = "", listingGeneration = it.listingGeneration + 1)
        }
    }

    fun setDisplayMode(mode: LibraryDisplayMode) {
        sourcePreferences.sourceDisplayMode.set(mode)
    }

    /** Tracks the search field's text without applying it until the user submits. */
    fun applyQuery(query: String) = mutableState.update { it.copy(query = query) }

    /** Clears the applied query so the next listing returns to the source's default mode. */
    fun clearSearch() = mutableState.update { it.copy(query = "", appliedQuery = "") }

    fun retryInfo() {
        if (state.value.info == null) {
            mutableState.update { it.copy(loadingInfo = true, infoError = null) }
            loadInfo()
        }
    }

    suspend fun materialize(manga: Manga): Manga = withContext(Dispatchers.IO) {
        source.materialize(manga)
    }

    /** Bootstrap a newly discovered series; prepared details can open from the existing snapshot. */
    suspend fun prepareOpen(manga: Manga) {
        val remote = session.identity.mangaId(manga.url)
        session.checkActive()
        if (!session.catalog.hasPreparedManga(remote)) {
            session.api.fetchSourceDetails(remote)
            session.catalog.manga(remote, refresh = true)
            session.catalog.chapters(remote, refresh = true)
        }
        session.checkActive()
    }

    fun refreshListing() {
        if (refreshJob?.isActive == true) return
        val listing = listing()
        val generation = state.value.listingGeneration
        refreshJob = screenModelScope.launch(Dispatchers.IO) {
            mutableState.update { it.copy(refreshing = true) }
            try {
                session.catalog.refreshSourceListing(listing.infoId, listing.query, listing.mode, listing.filters)
                session.checkActive()
                mutableState.update {
                    if (it.listingGeneration == generation) it.copy(listingGeneration = generation + 1) else it
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                session.checkActive()
                errorChannel.send(error)
            } finally {
                mutableState.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun listing(): SourceListing = with(state.value) {
        SourceListing(
            infoId = infoId,
            query = appliedQuery,
            mode = mode,
            filters = appliedFilters,
            browseFilters = browseFilters,
            sort = sort,
        )
    }

    /**
     * One [Pager] per listing generation, so a query, mode or filter change starts a fresh listing
     * while recomposition alone never restarts the current one. The server paginates by page number
     * and the paging source owns that page state, appending once per requested page.
     */
    fun pager(generation: Int): Flow<PagingData<StateFlow<Manga>>> = pagerForGeneration.getOrPut(generation) {
        val listing = listing()
        Pager(
            config = PagingConfig(pageSize = 50, initialLoadSize = 50, enablePlaceholders = false),
            pagingSourceFactory = { SourceListingPagingSource(listing) },
        ).flow.cachedIn(screenModelScope)
    }

    /** Bounded so a long search session cannot retain every previous listing. */
    private val pagerForGeneration = object : LinkedHashMap<Int, Flow<PagingData<StateFlow<Manga>>>>(4, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, Flow<PagingData<StateFlow<Manga>>>>?,
        ): Boolean = size > 3
    }

    private inner class SourceListingPagingSource(
        private val listing: SourceListing,
    ) : PagingSource<Int, StateFlow<Manga>>() {
        private var revision: Long? = null
        override fun getRefreshKey(state: PagingState<Int, StateFlow<Manga>>): Int? = null

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, StateFlow<Manga>> = try {
            val page = params.key ?: 1
            session.checkActive()
            val result = session.catalog.sourceListing(
                sourceId = listing.infoId,
                page = page,
                query = listing.query,
                type = listing.mode,
                filters = listing.filters,
                revision = revision ?: session.catalog.listingRevision().also { revision = it },
            )
            session.checkActive()
            val local = withContext(Dispatchers.IO) {
                repository.getMangaBySourceId(source.id).filter { it.url.startsWith(session.prefix) }
            }
            val byUrl = local.associateBy { it.url }
            // The client-side narrowing runs on the remote entries, where library membership and the
            // server's own ordering are still available.
            val mangas = applySuwayomiBrowseFilters(
                mangas = result.mangas.filter { it.id > 0 },
                filters = listing.browseFilters,
                sort = listing.sort,
            )
                .map { entry ->
                    val remote = source.toManga(entry, session)
                    byUrl[remote.url]?.copy(
                        title = remote.title,
                        author = remote.author,
                        artist = remote.artist,
                        description = remote.description,
                        genre = remote.genre,
                        thumbnailUrl = remote.thumbnailUrl,
                    ) ?: remote
                }
                .map { MutableStateFlow(it) as StateFlow<Manga> }
            LoadResult.Page(
                data = mangas,
                prevKey = if (page > 1) page - 1 else null,
                nextKey = if (result.hasNextPage) page + 1 else null,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is SuwayomiListingChangedException) {
                LoadResult.Invalid()
            } else {
                logcat(LogPriority.WARN, error) { "Suwayomi listing failed" }
                LoadResult.Error(error)
            }
        }
    }

    private data class SourceListing(
        val infoId: Long,
        val query: String,
        val mode: SuwayomiSourceMangaType,
        val filters: List<SuwayomiFilterChange>,
        val browseFilters: SuwayomiBrowseFilters,
        val sort: SuwayomiBrowseSort,
    )

    data class State(
        val info: SuwayomiSourceInfo? = null,
        val pinned: Boolean = false,
        val query: String = "",
        val appliedQuery: String = "",
        val mode: SuwayomiSourceMangaType = SuwayomiSourceMangaType.POPULAR,
        val filters: List<SuwayomiFilterState> = emptyList(),
        val appliedFilters: List<SuwayomiFilterChange> = emptyList(),
        val browseFilters: SuwayomiBrowseFilters = SuwayomiBrowseFilters.NONE,
        val sort: SuwayomiBrowseSort = SuwayomiBrowseSort.SOURCE,
        val listingGeneration: Int = 0,
        val refreshing: Boolean = false,
        val displayMode: LibraryDisplayMode = LibraryDisplayMode.CompactGrid,
        val loadingInfo: Boolean = true,
        val infoError: Throwable? = null,
    ) {
        val supportsLatest: Boolean get() = info?.supportsLatest == true
        val hasFilters: Boolean get() = filters.any { it !is koharia.suwayomi.UnsupportedState }
        val activeFilterCount: Int
            get() = activeSuwayomiFilterCount(filters) + activeSuwayomiBrowseFilterCount(browseFilters, sort)
        val hasActiveFilters: Boolean get() = activeFilterCount > 0
    }
}
