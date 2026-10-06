package koharia.suwayomi.ui

import androidx.paging.PagingData
import androidx.paging.cachedIn
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import koharia.connection.ConnectionShelfUpdates
import koharia.connection.ui.CONNECTION_SHELF_STATIC_LOAD_STATES
import koharia.connection.ui.ConnectionTriState
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiCategory
import koharia.suwayomi.SuwayomiLibraryFilter
import koharia.suwayomi.SuwayomiLibraryFilters
import koharia.suwayomi.SuwayomiShelf
import koharia.suwayomi.suwayomiLibraryFiltersMatch
import koharia.suwayomi.suwayomiShelfGenres
import koharia.suwayomi.with
import koharia.suwayomi.withVisibleCategories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiLibraryScreenModel(val source: SuwayomiSource, initialQuery: String?) :
    StateScreenModel<SuwayomiLibraryScreenModel.State>(
        State(
            shelf = source.session().catalog.memoryShelf?.withVisibleCategories(source.preferences.visibleCategoryIds),
            media = source.session().catalog.memoryShelf?.withVisibleCategories(
                source.preferences.visibleCategoryIds,
            )?.categories.orEmpty(),
            selectedMedia = source.preferences.categoryId.takeIf { selected ->
                source.session().catalog.memoryShelf?.categories?.any { it.id == selected } == true
            } ?: -1,
            mediaLoaded = source.session().catalog.memoryShelf != null,
            query = initialQuery.orEmpty(),
            toolbarQuery = initialQuery,
            // Persisted sort and filters only apply while persistence is opted in.
            order = source.preferences.initialShelfOrder(DEFAULT_ORDER),
            filters = source.preferences.initialShelfFilters(),
            persistentFilters = source.preferences.persistentFilters,
            displayMode = Injekt.get<SourcePreferences>().sourceDisplayMode.get(),
            downloadedOnly = Injekt.get<BasePreferences>().downloadedOnly.get(),
        ),
    ) {
    private val session = source.session()
    init {
        bindSession(session)
    }
    private val visibleCategoryIds = source.preferences.visibleCategoryIds
    private val repository: MangaRepository = Injekt.get()
    private val downloads: DownloadManager = Injekt.get()
    private val sourcePreferences: SourcePreferences = Injekt.get()
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    fun mangaUrl(remoteId: Int) = session.identity.mangaUrl(remoteId)

    init {
        load()
        screenModelScope.launch {
            sourcePreferences.sourceDisplayMode.changes().collect { mode ->
                mutableState.update { it.copy(displayMode = mode) }
            }
        }
        screenModelScope.launch {
            Injekt.get<BasePreferences>().downloadedOnly.changes().collect { enabled ->
                mutableState.update { it.copy(downloadedOnly = enabled) }
            }
        }
        screenModelScope.launch {
            ConnectionShelfUpdates.changes.filter { it == source.id }.collect { load() }
        }
    }

    private fun load(refresh: Boolean = false) {
        if (loadJob?.isActive == true) return
        loadJob = screenModelScope.launch(Dispatchers.IO) {
            mutableState.update { it.copy(refreshing = refresh, error = null) }
            try {
                val shelf = session.catalog.shelf(refresh).withVisibleCategories(visibleCategoryIds)
                session.checkActive()
                val selected = source.preferences.categoryId.takeIf { id -> shelf.categories.any { it.id == id } } ?: -1
                source.preferences.categoryId = selected
                mutableState.update {
                    it.copy(
                        shelf = shelf,
                        media = shelf.categories,
                        selectedMedia = selected,
                        mediaLoaded = true,
                        refreshing = false,
                        error = null,
                    )
                }
                if (refresh) ConnectionShelfUpdates.notify(source.id)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update {
                    it.copy(
                        shelf = it.shelf ?: session.catalog.memoryShelf?.withVisibleCategories(visibleCategoryIds),
                        mediaLoaded = true,
                        refreshing = false,
                        error = error,
                    )
                }
            }
        }
    }

    val pages: Flow<PagingData<StateFlow<Manga>>> = combine(
        state,
        repository.getMangaBySourceIdAsFlow(source.id),
        downloads.cacheChanges.onStart { emit(Unit) },
    ) { request, local, _ ->
        val byUrl = local.filter { it.url.startsWith(session.prefix) }.associateBy { it.url }
        val categoryEntries = request.shelf?.mangas.orEmpty()
            .filter {
                request.selectedMedia == -1 ||
                    it.categories.nodes.any { category -> category.id == request.selectedMedia }
            }
        val entries = if (request.downloadedOnly) {
            val categoryUrls = categoryEntries.mapTo(hashSetOf()) { session.identity.mangaUrl(it.id) }
            byUrl.values.filter {
                downloads.getDownloadCount(it) > 0 &&
                    ((visibleCategoryIds == null && request.selectedMedia == -1) || it.url in categoryUrls)
            }
        } else {
            // The shelf filters run on the server payload, where the counts and status live.
            categoryEntries
                .filter { suwayomiLibraryFiltersMatch(request.filters, it.libraryFacts) }
                .map { source.toManga(it, session) }.map { remote ->
                    byUrl[remote.url]?.copy(
                        title = remote.title,
                        author = remote.author,
                        artist = remote.artist,
                        description = remote.description,
                        genre = remote.genre,
                        thumbnailUrl = remote.thumbnailUrl,
                    )
                        ?: remote
                }
        }
        val filtered = entries.filter {
            it.title.contains(request.query, true) ||
                it.author.orEmpty().contains(request.query, true) ||
                it.genre.orEmpty().any { tag -> tag.contains(request.query, true) }
        }
        val sorted = when (request.order.substringBefore(' ')) {
            "author" -> filtered.sortedWith(
                compareBy<Manga> {
                    it.author.orEmpty().lowercase()
                }.thenBy { it.title.lowercase() },
            )
            "added" -> {
                val dates = request.shelf?.mangas.orEmpty().associate {
                    session.identity.mangaUrl(it.id) to
                        it.inLibraryAt
                }
                filtered.sortedWith(compareBy<Manga> { dates[it.url] ?: 0 }.thenBy { it.title.lowercase() })
            }
            else -> filtered.sortedBy { it.title.lowercase() }
        }.let { if (request.order.endsWith("desc")) it.reversed() else it }
        PagingData.from(
            data = sorted.map { MutableStateFlow(it) as StateFlow<Manga> },
            sourceLoadStates = CONNECTION_SHELF_STATIC_LOAD_STATES,
        )
    }.flowOn(Dispatchers.IO).cachedIn(screenModelScope)

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
        searchJob?.cancel()
        if (query != null) {
            searchJob = screenModelScope.launch {
                delay(350)
                search(query)
            }
        }
    }
    fun search(query: String) {
        searchJob?.cancel()
        mutableState.update { it.copy(query = query.trim(), toolbarQuery = query) }
    }
    fun exitSearch() {
        searchJob?.cancel()
        mutableState.update { it.copy(query = "", toolbarQuery = null) }
    }
    fun selectMedia(id: Int) {
        if (id != -1 && state.value.media.none { it.id == id }) return
        source.preferences.categoryId = id
        mutableState.update { it.copy(selectedMedia = id) }
    }
    fun setDisplayMode(mode: LibraryDisplayMode) {
        sourcePreferences.sourceDisplayMode.set(mode)
    }
    fun filter(order: String, downloadedOnly: Boolean) {
        source.preferences.order = order
        mutableState.update { it.copy(order = order, downloadedOnly = downloadedOnly) }
        if (!downloadedOnly && state.value.shelf == null) load()
    }

    /**
     * Applies one filter sheet submission: the shared tri-state conditions, the provider narrowing,
     * the sort, downloaded-only, and the persistence choice are committed together.
     */
    fun applyLibraryFilters(
        conditions: Map<SuwayomiLibraryFilter, ConnectionTriState>,
        narrowing: SuwayomiLibraryFilters,
        order: String,
        downloadedOnly: Boolean,
        persistentFilters: Boolean,
    ) {
        val filters = conditions.entries.fold(narrowing) { acc, (key, state) -> acc.with(key, state.toTriState()) }
        source.preferences.persistentFilters = persistentFilters
        if (persistentFilters) {
            source.preferences.order = order
            source.preferences.libraryFilters = filters
        } else {
            // Turning persistence off drops what was stored, so it cannot reappear later.
            source.preferences.clearPersistentFilters()
        }
        logcat(LogPriority.INFO) {
            "Suwayomi shelf filters persisted=$persistentFilters order='$order' active=${filters.activeCount}"
        }
        mutableState.update {
            it.copy(
                order = order,
                downloadedOnly = downloadedOnly,
                filters = filters,
                persistentFilters = persistentFilters,
            )
        }
    }
    fun selectSearchSort(field: String, ascending: Boolean) {
        if (field !in listOf("title", "author", "added")) return
        filter("$field ${if (ascending) "asc" else "desc"}", state.value.downloadedOnly)
    }
    fun refresh() = load(true)

    data class State(
        val shelf: SuwayomiShelf? = null,
        val media: List<SuwayomiCategory> = emptyList(),
        val selectedMedia: Int = -1,
        val mediaLoaded: Boolean = false,
        val query: String = "",
        val toolbarQuery: String? = null,
        val order: String = "title asc",
        val displayMode: LibraryDisplayMode = LibraryDisplayMode.CompactGrid,
        val downloadedOnly: Boolean = false,
        val filters: SuwayomiLibraryFilters = SuwayomiLibraryFilters.NONE,
        val persistentFilters: Boolean = false,
        val refreshing: Boolean = false,
        val error: Throwable? = null,
    ) {
        /** Every genre the current shelf exposes, for the genre narrowing. */
        val availableGenres: List<String> get() = suwayomiShelfGenres(shelf?.mangas.orEmpty().flatMap { it.genre })

        val hasActiveFilters: Boolean get() = filters.active
    }

    companion object {
        const val DEFAULT_ORDER = "title asc"
    }
}
