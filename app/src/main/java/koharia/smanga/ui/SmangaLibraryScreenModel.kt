package koharia.smanga.ui

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
import eu.kanade.tachiyomi.data.download.DownloadManager
import koharia.connection.ConnectionShelfUpdates
import koharia.smanga.SmangaMedia
import koharia.source.smanga.SmangaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import kotlinx.coroutines.withContext
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal fun mergeSmangaShelfManga(remote: Manga, local: Manga?): Manga {
    return local?.copy(
        title = remote.title,
        author = remote.author,
        description = remote.description,
        genre = remote.genre,
        thumbnailUrl = remote.thumbnailUrl,
    ) ?: remote
}

class SmangaLibraryScreenModel(val source: SmangaSource, initialQuery: String?) :
    StateScreenModel<SmangaLibraryScreenModel.State>(
        State(
            query = initialQuery.orEmpty(),
            toolbarQuery = initialQuery,
            order = source.preferences.order,
            displayMode = modes.getOrElse(source.preferences.displayMode) {
                LibraryDisplayMode.ComfortableGrid
            },
        ),
    ) {
    private val session = source.session()
    private val repository: MangaRepository = Injekt.get()
    private val downloads: DownloadManager = Injekt.get()
    private var searchJob: Job? = null
    private var refreshJob: Job? = null

    init {
        loadMedia()
        screenModelScope.launch {
            Injekt.get<BasePreferences>().downloadedOnly.changes().collect { enabled ->
                mutableState.update { it.copy(downloadedOnly = enabled) }
            }
        }
        screenModelScope.launch { ConnectionShelfUpdates.changes.filter { it == source.id }.collect { refresh() } }
    }
    private fun loadMedia(refresh: Boolean = false) {
        screenModelScope.launch(Dispatchers.IO) {
            try {
                val media = session.catalog.media(refresh)
                session.checkActive()
                val selected = source.preferences.mediaId.takeIf { id -> media.any { it.id == id } } ?: 0
                source.preferences.mediaId = selected
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
        )
    }.distinctUntilChanged()
    val pages: Flow<PagingData<StateFlow<Manga>>> = request.flatMapLatest { request ->
        if (request.downloads) {
            combine(
                repository.getMangaBySourceIdAsFlow(source.id),
                downloads.cacheChanges.onStart { emit(Unit) },
            ) { local, _ ->
                PagingData.from(
                    local.filter {
                        it.url.startsWith(session.prefix) &&
                            it.title.contains(request.query, true) &&
                            downloads.getDownloadCount(it) > 0
                    }.map { MutableStateFlow(it) as StateFlow<Manga> },
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
                                val result = session.catalog.page(request.media, request.query, request.order, page)
                                session.checkActive()
                                LoadResult.Page(
                                    data = result.data.map { source.toManga(it, session) },
                                    prevKey = (page - 1).takeIf { page > 1 },
                                    nextKey = (page + 1).takeIf { result.hasNext },
                                )
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                LoadResult.Error(SmangaShelfException(error))
                            }
                        }
                }
            }.flow.cachedIn(screenModelScope).combine(repository.getMangaBySourceIdAsFlow(source.id)) { paging, local ->
                val byUrl = local.associateBy { it.url }
                paging.map { MutableStateFlow(mergeSmangaShelfManga(it, byUrl[it.url])) as StateFlow<Manga> }
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
        searchJob?.cancel()
        refreshJob?.cancel()
        mutableState.update {
            it.copy(query = query.trim(), toolbarQuery = query, error = if (it.media.isEmpty()) it.error else null)
        }
    }
    fun exitSearch() {
        refreshJob?.cancel()
        searchJob?.cancel()
        mutableState.update {
            it.copy(query = "", toolbarQuery = null, error = if (it.media.isEmpty()) it.error else null)
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
        source.preferences.displayMode = modes.indexOf(mode)
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
    fun refresh() {
        if (refreshJob?.isActive == true) return
        val request = state.value
        refreshJob = screenModelScope.launch(Dispatchers.IO) {
            mutableState.update { it.copy(refreshing = true, error = null) }
            var refreshed = false
            try {
                session.catalog.refreshShelf(
                    request.selectedMedia,
                    request.query,
                    request.order,
                    request.downloadedOnly,
                )
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
                        session.catalog.cachedMedia()?.let { media ->
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
    data class State(
        val media: List<SmangaMedia> = emptyList(),
        val selectedMedia: Long = 0,
        val mediaLoaded: Boolean = false,
        val query: String = "",
        val toolbarQuery: String? = null,
        val order: String = "mangaName asc",
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
class SmangaShelfException(cause: Throwable) : java.io.IOException("Smanga shelf unavailable", cause)
