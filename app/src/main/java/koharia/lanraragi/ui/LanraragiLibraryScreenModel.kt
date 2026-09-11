package koharia.lanraragi.ui

import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.cachedIn
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import koharia.domain.lanraragi.LanraragiEntry
import koharia.domain.lanraragi.LanraragiReadState
import koharia.lanraragi.LanraragiFilter
import koharia.lanraragi.filterLanraragiCatalog
import koharia.lanraragi.searchLanraragiWithFallback
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LanraragiLibraryScreenModel(val source: LanraragiSource, initialQuery: String?) :
    StateScreenModel<LanraragiLibraryScreenModel.State>(
        State(
            filter = LanraragiFilter(
                query = initialQuery.orEmpty(),
                category = source.preferences.defaultCategory.takeIf { it.isNotEmpty() },
                grouped = source.preferences.groupCollections,
            ),
            toolbarQuery = initialQuery,
        ),
    ) {
    private val mangaRepository: MangaRepository = Injekt.get()
    private val downloads: DownloadManager = Injekt.get()
    private val basePreferences: BasePreferences = Injekt.get()
    private var searchJob: Job? = null

    @Volatile private var searchRevision = 0L

    init {
        screenModelScope.launch {
            source.networkAvailable.collectLatest { online ->
                if (online && !state.value.downloadedOnly) source.refreshIfStale()
                search(state.value.filter.query)
                if (online) source.retryPending()
            }
        }
        screenModelScope.launch {
            source.repository.observeEntries(source.id).collect { entries ->
                mutableState.update {
                    val selected = it.filter.category?.takeIf { id ->
                        entries.isEmpty() ||
                            entries.any { entry -> entry.id == id && entry.kind == LanraragiEntry.Kind.CATEGORY }
                    }
                    it.copy(entries = entries, catalogLoaded = true, filter = it.filter.copy(category = selected))
                }
            }
        }
        screenModelScope.launch {
            source.repository.observeReadStates(source.id).collect { read ->
                mutableState.update { it.copy(readStates = read) }
            }
        }
        screenModelScope.launch {
            basePreferences.downloadedOnly.changes().collect { value ->
                mutableState.update { it.copy(downloadedOnly = value) }
                search(state.value.filter.query)
            }
        }
    }

    val pages: Flow<PagingData<StateFlow<Manga>>> = combine(
        state,
        mangaRepository.getMangaBySourceIdAsFlow(source.id),
        downloads.cacheChanges.onStart { emit(Unit) },
    ) { state, local, _ ->
        val byUrl = local.associateBy { it.url }
        val effectiveFilter = if (state.advancedResults != null) {
            state.filter.copy(query = "", category = null)
        } else {
            state.filter
        }
        val entries = if (state.searchEntries.isEmpty()) {
            state.entries
        } else {
            (state.entries + state.searchEntries).associateBy { it.id }.values.toList()
        }
        val downloaded = if (state.downloadedOnly) local.filter { downloads.getDownloadCount(it) > 0 } else emptyList()
        val catalogIds = entries.mapTo(mutableSetOf()) { it.id }
        val orphans = downloaded.mapNotNull { manga ->
            val id = source.archiveId(manga.url)
            if (id in catalogIds) {
                null
            } else {
                LanraragiEntry(
                    id = id,
                    kind = if (manga.url.contains("/tank/")) LanraragiEntry.Kind.TANK else LanraragiEntry.Kind.ARCHIVE,
                    title = manga.title,
                    tags = manga.genre.orEmpty().joinToString(),
                    summary = manga.description.orEmpty(),
                    available = false,
                )
            }
        }
        val catalog = entries + orphans
        val filtered = filterLanraragiCatalog(
            catalog,
            state.readStates,
            effectiveFilter,
            state.advancedResults,
            availableEntryIds = if (state.downloadedOnly) {
                downloaded.mapTo(mutableSetOf()) {
                    source.archiveId(it.url)
                }
            } else {
                null
            },
        )
        val mangas = filtered.mapIndexed { index, entry ->
            byUrl[source.resourceUrl(entry)]?.copy(thumbnailUrl = source.thumbnail(entry, catalog))
                ?: source.toManga(entry, catalog, index)
        }
        PagingData.from(
            mangas.map { MutableStateFlow(it) as StateFlow<Manga> },
            sourceLoadStates = LoadStates(
                if (state.catalogLoaded) LoadState.NotLoading(false) else LoadState.Loading,
                LoadState.NotLoading(true),
                LoadState.NotLoading(true),
            ),
        )
    }.flowOn(Dispatchers.IO).cachedIn(screenModelScope)

    fun filter(value: LanraragiFilter) {
        mutableState.update { it.copy(filter = value, error = null) }
        search(value.query)
    }

    fun applyFilters(value: LanraragiFilter, downloaded: Boolean) {
        basePreferences.downloadedOnly.set(downloaded)
        mutableState.update { it.copy(downloadedOnly = downloaded) }
        filter(value)
    }

    fun selectCategory(id: String?) = filter(state.value.filter.copy(category = id))

    fun refresh(automatic: Boolean = false) {
        if (state.value.downloadedOnly) return
        screenModelScope.launch(Dispatchers.IO) {
            if (!automatic || source.networkAvailable.value) {
                if (automatic) source.refreshIfStale() else source.startRefresh()
                source.retryPending()
            }
        }
        if (!automatic || state.value.filter.query.isNotBlank()) search(state.value.filter.query)
    }

    fun search(query: String) {
        searchJob?.cancel()
        val revision = ++searchRevision
        val category = state.value.filter.category
        mutableState.update {
            it.copy(
                filter = it.filter.copy(query = query),
                toolbarQuery = if (it.toolbarQuery != null || query.isNotEmpty()) query else null,
                advancedResults = null,
                searchEntries = emptyList(),
                searching = false,
                error = null,
            )
        }
        if (query.isBlank() || state.value.downloadedOnly || !source.networkAvailable.value) return
        searchJob = screenModelScope.launch(Dispatchers.IO) {
            try {
                delay(250)
                mutableState.update { it.copy(searching = true) }
                val result = searchLanraragiWithFallback(source.networkAvailable.value) {
                    source.api.search(query, category)
                }
                mutableState.update { current ->
                    if (revision != searchRevision) {
                        current
                    } else {
                        current.copy(
                            searchEntries = result.orEmpty(),
                            advancedResults = result?.mapTo(mutableSetOf()) { it.id },
                        )
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (revision == searchRevision) mutableState.update { it.copy(error = error) }
            } finally {
                if (revision == searchRevision) mutableState.update { it.copy(searching = false) }
            }
        }
    }

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
        search(query.orEmpty())
    }

    fun exitSearch() {
        mutableState.update { it.copy(toolbarQuery = null) }
        search("")
    }

    fun setDisplayMode(value: LibraryDisplayMode) {
        mutableState.update { it.copy(displayMode = value) }
    }

    suspend fun materialize(manga: Manga): Manga {
        val entry = (state.value.searchEntries + state.value.entries).firstOrNull {
            source.resourceUrl(it) == manga.url
        }
        return if (entry != null) source.materialize(entry) else manga
    }

    data class State(
        val entries: List<LanraragiEntry> = emptyList(),
        val catalogLoaded: Boolean = false,
        val readStates: List<LanraragiReadState> = emptyList(),
        val filter: LanraragiFilter = LanraragiFilter(),
        val downloadedOnly: Boolean = false,
        val displayMode: LibraryDisplayMode = LibraryDisplayMode.ComfortableGrid,
        val toolbarQuery: String? = null,
        val searchEntries: List<LanraragiEntry> = emptyList(),
        val searching: Boolean = false,
        val advancedResults: Set<String>? = null,
        val error: Throwable? = null,
    ) {
        val categories: List<LanraragiEntry> get() = entries.filter { it.kind == LanraragiEntry.Kind.CATEGORY }
            .sortedWith(compareByDescending<LanraragiEntry> { it.pinned }.thenBy { it.title })
    }
}
