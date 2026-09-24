package koharia.lanraragi.ui

import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.cachedIn
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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
            filter = initialLanraragiFilter(source, initialQuery),
            rememberFilters = source.preferences.rememberFilters,
            toolbarQuery = initialQuery,
            displayMode = Injekt.get<SourcePreferences>().sourceDisplayMode.get(),
        ),
    ) {
    private val mangaRepository: MangaRepository = Injekt.get()
    private val downloads: DownloadManager = Injekt.get()
    private val sourcePreferences: SourcePreferences = Injekt.get()
    private var searchJob: Job? = null
    private var progressJob: Job? = null

    @Volatile private var searchRevision = 0L

    init {
        screenModelScope.launch {
            sourcePreferences.sourceDisplayMode.changes().collect { mode ->
                mutableState.update { it.copy(displayMode = mode) }
            }
        }
        screenModelScope.launch {
            while (true) {
                source.refreshIfStale()
                delay(5_000)
                if (source.repository.lastSync(source.id) > 0L) break
            }
        }
        screenModelScope.launch {
            koharia.connection.ConnectionShelfUpdates.changes.filter { it == source.id }.collectLatest {
                delay(500)
                refresh()
            }
        }
        screenModelScope.launch {
            source.networkAvailable.collectLatest { online ->
                if (online && !state.value.downloadedOnly) source.refreshIfStale()
                search(state.value.filter.query)
                if (online) refreshProgress()
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

    fun applyFilters(value: LanraragiFilter, downloaded: Boolean, rememberFilters: Boolean) {
        mutableState.update { it.copy(downloadedOnly = downloaded) }
        source.preferences.saveFilter(value, rememberFilters)
        mutableState.update { it.copy(rememberFilters = rememberFilters) }
        filter(value)
    }

    fun selectCategory(id: String?) {
        val value = state.value.filter.copy(category = id)
        if (state.value.rememberFilters) source.preferences.saveFilter(value, true)
        filter(value)
    }

    fun refresh(automatic: Boolean = false) {
        if (state.value.downloadedOnly) return
        screenModelScope.launch(Dispatchers.IO) {
            if (!automatic || source.networkAvailable.value) {
                if (automatic) {
                    source.refreshIfStale()
                    refreshProgress()
                } else {
                    progressJob?.cancelAndJoin()
                    source.startRefresh()
                }
            }
        }
        if (!automatic || state.value.filter.query.isNotBlank()) search(state.value.filter.query)
    }

    private fun refreshProgress() {
        if (state.value.downloadedOnly || progressJob?.isActive == true) return
        progressJob = screenModelScope.launch(Dispatchers.IO) {
            try {
                source.refreshShelfProgress()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = error) }
            }
        }
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
        // The persisted catalogue is authoritative until an explicit refresh or server event.
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
        sourcePreferences.sourceDisplayMode.set(value)
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
        val rememberFilters: Boolean = false,
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

private fun initialLanraragiFilter(source: LanraragiSource, initialQuery: String?): LanraragiFilter {
    val saved = source.preferences.savedFilter().takeIf { source.preferences.rememberFilters }
    return (
        saved ?: LanraragiFilter(
            category = source.preferences.defaultCategory.takeIf(String::isNotEmpty),
            grouped = source.preferences.groupCollections,
        )
        ).copy(query = initialQuery.orEmpty())
}
