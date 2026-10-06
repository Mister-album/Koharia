package koharia.kavita.ui

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.paging.map
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.tachiyomi.data.download.DownloadManager
import koharia.connection.ConnectionShelfUpdates
import koharia.connection.LibraryContentScope
import koharia.kavita.KavitaLibrary
import koharia.kavita.KavitaSeries
import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal fun mergeKavitaShelfManga(remote: Manga, local: Manga?): Manga {
    return local?.copy(
        title = remote.title,
        author = remote.author,
        description = remote.description,
        genre = remote.genre,
        thumbnailUrl = remote.thumbnailUrl,
        memo = kotlinx.serialization.json.JsonObject(local.memo + remote.memo),
    ) ?: remote
}

class KavitaLibraryScreenModel(
    val source: KavitaSource,
    initialQuery: String?,
    filterJson: String? = null,
    private val contentScope: LibraryContentScope = LibraryContentScope.ALL,
) :
    StateScreenModel<KavitaLibraryScreenModel.State>(
        State(
            // A filter carried by the route wins; otherwise the opt-in store supplies one.
            appliedFilter = source.preferences.initialFilter(filterJson),
            query = initialQuery.orEmpty(),
            toolbarQuery = initialQuery,
            order = source.preferences.initialFilter(filterJson)?.sortOptions?.let { sort ->
                "${sort.sortField} ${if (sort.isAscending) "asc" else "desc"}"
            } ?: source.preferences.initialOrder(),
            persistentFilters = source.preferences.persistentFilters,
            displayMode = Injekt.get<SourcePreferences>().sourceDisplayMode.get(),
        ),
    ) {
    private val session = source.session()
    private val extraFilter get() = state.value.appliedFilter
    fun editableFilter() = (extraFilter ?: koharia.kavita.KavitaFilter()).copy(
        statements = extraFilter?.statements.orEmpty() + listOfNotNull(genreFilter),
        sortOptions = koharia.kavita.KavitaSort(
            state.value.order.substringBefore(' ').toInt(),
            !state.value.order.endsWith("desc"),
        ),
    )
    fun applyFilter(value: koharia.kavita.KavitaFilter) {
        refreshJob?.cancel()
        genreFilter = null
        val order = "${value.sortOptions.sortField} ${if (value.sortOptions.isAscending) "asc" else "desc"}"
        val json = runCatching { source.session().api.json.encodeToString(value) }.getOrNull()
        // A confirmed submission is what the persistence choice applies to.
        source.preferences.commitFilter(json, order, state.value.persistentFilters)
        logcat(LogPriority.INFO) {
            "Kavita shelf filters persisted=${state.value.persistentFilters} order='$order' " +
                "statements=${value.statements.size} stored=${source.preferences.savedFilter != null}"
        }
        mutableState.update { it.copy(appliedFilter = value, order = order, error = null) }
    }

    /** Sets the persistence choice and stores or clears the current filters and sort accordingly. */
    fun setPersistentFilters(enabled: Boolean) {
        source.preferences.persistentFilters = enabled
        val current = state.value.appliedFilter
        val json = current?.let { runCatching { source.session().api.json.encodeToString(it) }.getOrNull() }
        source.preferences.commitFilter(json, state.value.order, enabled)
        logcat(LogPriority.INFO) {
            "Kavita persistence toggle enabled=$enabled stored=${source.preferences.savedFilter != null}"
        }
        mutableState.update { it.copy(persistentFilters = enabled) }
    }
    private var genreFilter: koharia.kavita.KavitaFilterStatement? = null
    fun currentFilter(): koharia.kavita.KavitaFilter = state.value.let {
        filter(
            it.media.filter { media ->
                it.selectedMedia == 0L || media.id == it.selectedMedia
            }.map { media -> media.id },
            it.query,
            it.order,
        )
    }
    private fun filter(
        media: List<Long>,
        query: String,
        order: String,
        advanced: koharia.kavita.KavitaFilter? = extraFilter,
    ): koharia.kavita.KavitaFilter {
        val base = source.filter(media, query, order).let {
            it.copy(statements = it.statements + listOfNotNull(genreFilter))
        }
        return koharia.kavita.combineKavitaFilters(
            base,
            advanced,
            unrestricted =
            contentScope == LibraryContentScope.ALL && query.isBlank() && state.value.selectedMedia == 0L &&
                genreFilter == null,
        )
    }
    private val repository: MangaRepository = Injekt.get()
    private val downloads: DownloadManager = Injekt.get()
    private val sourcePreferences: SourcePreferences = Injekt.get()
    private val libraryPreferences: LibraryPreferences = Injekt.get()
    private var searchJob: Job? = null
    private var refreshJob: Job? = null
    private val shelfReadProgress = MutableStateFlow<Map<String, MangaReadProgress>>(emptyMap())
    private val progressLoading = ConcurrentHashMap<String, Long>()
    private val visibleSeriesByUrl = ConcurrentHashMap<String, KavitaSeries>()
    private val progressEpoch = AtomicLong(0)
    val readProgressByUrl: StateFlow<Map<String, MangaReadProgress>> = shelfReadProgress
    private val shelfDownloadCounts = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val shelfTotalBookCounts = MutableStateFlow<Map<String, Long>>(emptyMap())
    val readingUnitCounts: StateFlow<Map<String, Long>> = shelfTotalBookCounts

    init {
        screenModelScope.launch(Dispatchers.IO) {
            combine(
                repository.getMangaBySourceIdAsFlow(source.id),
                downloads.cacheChanges.onStart { emit(Unit) },
            ) { mangas, _ ->
                mangas.associate { manga -> manga.url to downloads.getDownloadCount(manga).toLong() }
            }.collect { counts ->
                shelfDownloadCounts.value = counts
                if (!libraryPreferences.showLibraryReadProgress.get()) {
                    scheduleReadProgress(
                        counts.mapNotNull { (url, count) ->
                            visibleSeriesByUrl[url].takeIf { count > 0L }
                        },
                        session,
                    )
                }
            }
        }
        screenModelScope.launch {
            sourcePreferences.sourceDisplayMode.changes().collect { mode ->
                mutableState.update { it.copy(displayMode = mode) }
            }
        }
        screenModelScope.launch {
            libraryPreferences.showLibraryReadProgress.changes().collect { enabled ->
                if (enabled) {
                    clearReadProgress()
                    mutableState.update { it.copy(generation = it.generation + 1) }
                } else {
                    shelfReadProgress.value = emptyMap()
                }
            }
        }
        loadMedia()
        screenModelScope.launch {
            Injekt.get<BasePreferences>().downloadedOnly.changes().collect { enabled ->
                mutableState.update { it.copy(downloadedOnly = enabled) }
            }
        }
        screenModelScope.launch {
            ConnectionShelfUpdates.changes.filter { it == source.id }.collect {
                clearReadProgress()
                loadMedia()
                mutableState.update { it.copy(generation = it.generation + 1) }
            }
        }
    }
    private fun loadMedia(refresh: Boolean = false) {
        screenModelScope.launch(Dispatchers.IO) {
            try {
                val media = session.catalog.libraries(refresh).filter {
                    koharia.kavita.kavitaLibraryMatchesScope(it.type, contentScope)
                }
                session.checkActive()
                val extraFilter = extraFilter
                val selected = if (extraFilter?.combination == 0 && extraFilter.statements.size > 1) {
                    0L
                } else {
                    source.preferences.mediaId.takeIf { id -> media.any { it.id == id } } ?: 0L
                }
                mutableState.update {
                    it.copy(media = media, selectedMedia = selected, mediaLoaded = true, error = null)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(mediaLoaded = true, error = error) }
            }
        }
    }
    private data class Request(
        val media: List<Long>,
        val query: String,
        val order: String,
        val downloads: Boolean,
        val generation: Long,
        val advanced: koharia.kavita.KavitaFilter?,
    )
    private val request = state.map {
        Request(
            it.media.filter { media ->
                it.selectedMedia == 0L || media.id == it.selectedMedia
            }.map { media -> media.id },
            it.query,
            it.order,
            it.downloadedOnly,
            it.generation,
            it.appliedFilter,
        )
    }.distinctUntilChanged()
    val pages: Flow<PagingData<StateFlow<Manga>>> = request.flatMapLatest { request ->
        visibleSeriesByUrl.clear()
        if (request.media.isEmpty()) {
            flowOf(PagingData.empty())
        } else if (request.downloads) {
            combine(
                repository.getMangaBySourceIdAsFlow(source.id),
                downloads.cacheChanges.onStart { emit(Unit) },
            ) { local, _ ->
                val cachedIds = request.advanced?.takeIf { it.statements.isNotEmpty() }?.let {
                    session.catalog.cachedSeriesIds(filter(request.media, "", request.order, request.advanced))
                }
                val shelf = local.filter {
                    it.url.startsWith(session.prefix) &&
                        it.memo["kavitaLibraryId"]?.toString()?.toLongOrNull() in request.media &&
                        (cachedIds == null || session.identity.seriesId(it.url) in cachedIds) &&
                        (
                            it.title.contains(request.query, true) ||
                                it.genre.orEmpty().any { tag -> tag.contains(request.query, true) }
                            ) &&
                        downloads.getDownloadCount(it) > 0
                }
                scheduleLocalReadProgress(shelf, session)
                PagingData.from(
                    shelf.map { MutableStateFlow(it) as StateFlow<Manga> },
                )
            }
        } else if (request.media.isEmpty()) {
            flowOf(PagingData.empty())
        } else {
            Pager(PagingConfig(pageSize = 50, initialLoadSize = 50, prefetchDistance = 5, enablePlaceholders = false)) {
                object : PagingSource<Int, Manga>() {
                    override fun getRefreshKey(state: PagingState<Int, Manga>): Int? = null
                    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Manga> =
                        withContext(Dispatchers.IO) {
                            try {
                                val page = params.key ?: 1
                                val result = session.catalog.page(
                                    page,
                                    filter(request.media, request.query, request.order, request.advanced),
                                )
                                session.checkActive()
                                scheduleReadProgress(result.items, session)
                                LoadResult.Page(
                                    data = result.items.map { source.toManga(it, session) },
                                    prevKey = (page - 1).takeIf { page > 1 },
                                    nextKey = (page + 1).takeIf { result.hasNext },
                                )
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                LoadResult.Error(KavitaShelfException(error))
                            }
                        }
                }
            }.flow.cachedIn(screenModelScope).combine(repository.getMangaBySourceIdAsFlow(source.id)) { paging, local ->
                val byUrl = local.associateBy { it.url }
                paging.map { MutableStateFlow(mergeKavitaShelfManga(it, byUrl[it.url])) as StateFlow<Manga> }
            }
        }
    }.flowOn(Dispatchers.IO).cachedIn(screenModelScope)

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
        if (query != null) {
            searchJob?.cancel()
            searchJob = screenModelScope.launch {
                delay(350)
                search(query)
            }
        }
    }
    fun search(query: String) {
        genreFilter = null
        searchJob?.cancel()
        refreshJob?.cancel()
        mutableState.update {
            it.copy(query = query.trim(), toolbarQuery = query, error = if (it.media.isEmpty()) it.error else null)
        }
    }
    fun exitSearch() {
        genreFilter = null
        refreshJob?.cancel()
        searchJob?.cancel()
        mutableState.update {
            it.copy(query = "", toolbarQuery = null, error = if (it.media.isEmpty()) it.error else null)
        }
    }
    suspend fun searchGenre(name: String) {
        if (state.value.downloadedOnly) {
            search(name)
            return
        }
        try {
            val statement = withContext(Dispatchers.IO) {
                val genres = session.api.json.decodeFromString<List<koharia.kavita.KavitaTag>>(
                    session.catalog.resource("Metadata/genres").toString(),
                )
                val genre = genres.firstOrNull { it.title.equals(name, true) }
                if (genre != null) {
                    koharia.kavita.KavitaFilterStatement(18, 0, genre.id.toString())
                } else {
                    val tags = session.api.json.decodeFromString<List<koharia.kavita.KavitaTag>>(
                        session.catalog.resource("Metadata/tags").toString(),
                    )
                    val tag = tags.firstOrNull { it.title.equals(name, true) }
                    koharia.kavita.KavitaFilterStatement(6, 0, (tag?.id ?: -1).toString())
                }
            }
            genreFilter = statement
            mutableState.update { it.copy(query = "", toolbarQuery = name, generation = it.generation + 1) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update { it.copy(error = error) }
        }
    }
    fun selectMedia(id: Long) {
        if (id != 0L && state.value.media.none { it.id == id }) return
        if (state.value.selectedMedia == id) return
        refreshJob?.cancel()
        source.preferences.mediaId = id
        mutableState.update { it.copy(selectedMedia = id, error = null) }
    }
    fun setDisplayMode(mode: LibraryDisplayMode) {
        sourcePreferences.sourceDisplayMode.set(mode)
        mutableState.update { it.copy(displayMode = mode) }
    }
    fun filter(order: String, downloadedOnly: Boolean) {
        refreshJob?.cancel()
        source.preferences.order = order
        mutableState.update {
            it.copy(
                order = order,
                downloadedOnly = downloadedOnly,
                error = if (it.media.isEmpty()) it.error else null,
            )
        }
    }
    fun selectSearchSort(field: String, ascending: Boolean) {
        if (field !in listOf("1", "3", "2") || state.value.downloadedOnly) return
        filter("$field ${if (ascending) "asc" else "desc"}", state.value.downloadedOnly)
    }
    fun refresh() {
        if (refreshJob?.isActive == true) return
        val request = state.value
        refreshJob = screenModelScope.launch(Dispatchers.IO) {
            clearReadProgress()
            mutableState.update { it.copy(refreshing = true, error = null) }
            var refreshed = false
            try {
                if (!request.downloadedOnly) {
                    session.catalog.libraries(true)
                    session.catalog.refresh(
                        filter(
                            request.media.filter {
                                request.selectedMedia == 0L ||
                                    it.id == request.selectedMedia
                            }.map { it.id },
                            request.query,
                            request.order,
                            request.appliedFilter,
                        ),
                    )
                }
                session.checkActive()
                refreshed = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = error) }
            } finally {
                // A permission reduction can be committed before a failed or cancelled shelf refresh.
                // Read only the persisted scope, preserving the user's newer search or library selection.
                withContext(NonCancellable) {
                    runCatching {
                        session.catalog.libraries().filter {
                            koharia.kavita.kavitaLibraryMatchesScope(it.type, contentScope)
                        }.let { media ->
                            session.checkActive()
                            mutableState.update { current ->
                                current.copy(
                                    media = media,
                                    mediaLoaded = true,
                                    selectedMedia =
                                    current.selectedMedia.takeIf { id -> media.any { it.id == id } } ?: 0,
                                    generation = current.generation + if (refreshed) 1 else 0,
                                )
                            }
                            source.preferences.mediaId = state.value.selectedMedia
                        }
                    }
                }
                mutableState.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun clearReadProgress() {
        progressEpoch.incrementAndGet()
        shelfReadProgress.value = emptyMap()
        shelfTotalBookCounts.value = emptyMap()
        progressLoading.clear()
    }

    private fun scheduleReadProgress(entries: List<KavitaSeries>, session: KavitaSource.Session) {
        val epoch = progressEpoch.get()
        val downloadCounts = shelfDownloadCounts.value
        val showReadProgress = libraryPreferences.showLibraryReadProgress.get()
        val pending = entries.filter { entry ->
            val url = session.identity.series(entry.id)
            visibleSeriesByUrl[url] = entry
            if (showReadProgress && entry.pages > 0 && !shelfReadProgress.value.containsKey(url)) {
                shelfReadProgress.update {
                    it + (
                        url to MangaReadProgress(
                            (entry.pagesRead.toLong() * 100 / entry.pages).coerceIn(0, 100),
                            100,
                            eu.kanade.presentation.library.components.MangaReadProgressDisplay.PERCENTAGE,
                        )
                        )
                }
            }
            val needsProgress = showReadProgress || (downloadCounts[url] ?: 0L) > 0L
            needsProgress &&
                !shelfTotalBookCounts.value.containsKey(url) &&
                progressLoading.putIfAbsent(url, epoch) == null
        }
        if (pending.isEmpty()) return
        screenModelScope.launch(Dispatchers.IO) {
            val gate = Semaphore(permits = 6)
            try {
                coroutineScope {
                    pending.map { entry ->
                        async {
                            runCatching {
                                gate.withPermit { session.catalog.cachedSeriesBookProgress(entry.id) }
                            }.onSuccess { progress ->
                                if (progress != null && epoch == progressEpoch.get()) {
                                    val url = session.identity.series(entry.id)
                                    shelfTotalBookCounts.update { it + (url to progress.totalCount) }
                                    if (libraryPreferences.showLibraryReadProgress.get() && progress.totalCount > 0) {
                                        shelfReadProgress.update {
                                            it + (url to MangaReadProgress(progress.readCount, progress.totalCount))
                                        }
                                    }
                                }
                            }
                        }
                    }.forEach { it.await() }
                }
            } finally {
                pending.forEach { progressLoading.remove(session.identity.series(it.id), epoch) }
            }
        }
    }

    private fun scheduleLocalReadProgress(mangas: List<Manga>, session: KavitaSource.Session) {
        val entries = mangas.mapNotNull { manga ->
            runCatching {
                KavitaSeries(
                    id = session.identity.seriesId(manga.url),
                    libraryId = manga.memo["kavitaLibraryId"]?.toString()?.toLongOrNull() ?: 0L,
                )
            }.getOrNull()
        }
        scheduleReadProgress(entries, session)
    }

    data class State(
        val appliedFilter: koharia.kavita.KavitaFilter? = null,
        val media: List<KavitaLibrary> = emptyList(),
        val selectedMedia: Long = 0,
        val mediaLoaded: Boolean = false,
        val query: String = "",
        val toolbarQuery: String? = null,
        val order: String = "1 asc",
        val persistentFilters: Boolean = false,
        val displayMode: LibraryDisplayMode = LibraryDisplayMode.ComfortableGrid,
        val downloadedOnly: Boolean = false,
        val generation: Long = 0,
        val refreshing: Boolean = false,
        val error: Throwable? = null,
    )
    companion object {
        val modes = listOf(
            LibraryDisplayMode.ComfortableGrid,
            LibraryDisplayMode.CompactGrid,
            LibraryDisplayMode.CoverOnlyGrid,
            LibraryDisplayMode.List,
        )
    }
}

/** The shared empty state must not describe an uncached offline query as an empty full library. */
class KavitaShelfException(cause: Throwable) : java.io.IOException("Kavita shelf unavailable", cause)
