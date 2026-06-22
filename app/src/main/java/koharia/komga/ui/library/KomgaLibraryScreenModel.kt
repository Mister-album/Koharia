package koharia.komga.ui.library

import android.content.SharedPreferences
import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import koharia.komga.api.dto.LibraryDto
import koharia.source.komga.KomgaSource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.service.SourceManager
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

class KomgaLibraryScreenModel(
    private val sourceId: Long,
    listingQuery: String?,
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val basePreferences: BasePreferences,
    private val libraryPreferences: LibraryPreferences,
    private val downloadManager: DownloadManager,
    private val getRemoteManga: GetRemoteManga,
    private val getManga: GetManga,
    private val getIncognitoState: GetIncognitoState,
) : StateScreenModel<KomgaLibraryScreenModel.State>(State(Listing.valueOf(listingQuery))) {
    var displayMode by sourcePreferences.sourceDisplayMode.asState(screenModelScope)
    var cachedOnly by basePreferences.downloadedOnly.asState(screenModelScope)
    private val refreshSignal = MutableStateFlow(0)

    val source = sourceManager.getOrStub(sourceId)
    private var komgaSettingsChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    init {
        if (source is CatalogueSource) {
            mutableState.update {
                var query: String? = null
                var listing = it.listing

                if (listing is Listing.Search) {
                    query = listing.query
                    listing = Listing.Search(query, source.getFilterList())
                }

                it.copy(
                    listing = listing,
                    filters = source.getFilterList(),
                    toolbarQuery = query,
                )
            }
        }

        if (source is KomgaSource) {
            komgaSettingsChangeListener = source.registerServerSettingsChangeListener {
                screenModelScope.launchIO {
                    source.invalidateBrowseCache()
                    reloadKomgaState(source, showRefreshing = true, resetSelection = true)
                }
            }
            screenModelScope.launchIO {
                reloadKomgaState(source, showRefreshing = false, resetSelection = true)
            }
        }

        if (!getIncognitoState.await(source.id)) {
            sourcePreferences.lastUsedSource.set(source.id)
        }
    }

    val mangaPagerFlow: Flow<PagingData<StateFlow<Manga>>> = combine(
        state.map { it.listing }.distinctUntilChanged(),
        basePreferences.downloadedOnly.changes().onStart { emit(basePreferences.downloadedOnly.get()) },
        refreshSignal,
    ) { listing, cachedOnly, refreshSignal ->
        Triple(listing, cachedOnly, refreshSignal)
    }.flatMapLatest { (listing, cachedOnly, _) ->
        Pager(PagingConfig(pageSize = 25)) {
            getRemoteManga(sourceId, listing.query ?: "", listing.filters)
        }.flow.map { pagingData ->
            pagingData.map { remoteManga ->
                getManga.subscribe(remoteManga.url, remoteManga.source)
                    .map { localManga -> mergeRemoteWithLocal(remoteManga, localManga) }
                    .stateIn(
                        scope = ioCoroutineScope,
                        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                        initialValue = remoteManga,
                    )
            }
                .filter { mangaStateFlow ->
                    !cachedOnly ||
                        downloadManager.getDownloadCount(mangaStateFlow.value) > 0
                }
        }
    }
        .cachedIn(ioCoroutineScope)

    fun getColumnsPreference(orientation: Int): GridCells {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscape) {
            libraryPreferences.landscapeColumns
        } else {
            libraryPreferences.portraitColumns
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    fun resetFilters() {
        if (source !is CatalogueSource) return

        mutableState.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        mutableState.update { it.copy(listing = listing, toolbarQuery = null) }
    }

    fun setFilters(filters: FilterList) {
        if (source !is CatalogueSource) return

        mutableState.update {
            it.copy(
                filters = filters,
            )
        }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        if (source !is CatalogueSource) return

        val input = state.value.listing as? Listing.Search
            ?: Listing.Search(query = null, filters = source.getFilterList())

        mutableState.update {
            it.copy(
                listing = input.copy(
                    query = query ?: input.query,
                    filters = filters ?: input.filters,
                ),
                toolbarQuery = query ?: input.query,
            )
        }
    }

    fun searchGenre(genreName: String) {
        if (source !is CatalogueSource) return

        if (source is KomgaSource) {
            val filters = source.buildFilterListForTagSearch(genreName)
            logcat(LogPriority.DEBUG) {
                "KomgaLibraryScreenModel.searchGenre: applying Komga tag search tag=$genreName"
            }
            mutableState.update {
                it.copy(
                    filters = filters,
                    listing = Listing.Search(query = null, filters = filters),
                    toolbarQuery = genreName,
                )
            }
            return
        }

        val defaultFilters = source.getFilterList()
        var genreExists = false

        filter@ for (sourceFilter in defaultFilters) {
            if (sourceFilter is SourceModelFilter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is SourceModelFilter<*> && filter.name.equals(genreName, true)) {
                        when (filter) {
                            is SourceModelFilter.TriState -> filter.state = 1
                            is SourceModelFilter.CheckBox -> filter.state = true
                            else -> {}
                        }
                        genreExists = true
                        break@filter
                    }
                }
            } else if (sourceFilter is SourceModelFilter.Select<*>) {
                val index = sourceFilter.values.filterIsInstance<String>()
                    .indexOfFirst { it.equals(genreName, true) }

                if (index != -1) {
                    sourceFilter.state = index
                    genreExists = true
                    break
                }
            }
        }

        mutableState.update {
            val listing = if (genreExists) {
                Listing.Search(query = null, filters = defaultFilters)
            } else {
                Listing.Search(query = genreName, filters = defaultFilters)
            }
            it.copy(
                filters = defaultFilters,
                listing = listing,
                toolbarQuery = listing.query,
            )
        }
    }

    fun openFilterSheet() {
        setDialog(Dialog.Filter)
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
    }

    fun refresh() {
        val komgaSource = source as? KomgaSource
        if (komgaSource != null) {
            screenModelScope.launchIO {
                komgaSource.invalidateBrowseCache()
                reloadKomgaState(komgaSource, showRefreshing = true, resetSelection = false)
            }
        } else {
            refreshSignal.value += 1
        }
    }

    fun selectKomgaLibrary(libraryId: String?) {
        val komgaSource = source as? KomgaSource ?: return
        val filters = komgaSource.buildFilterListForLibrary(libraryId)
        mutableState.update {
            it.copy(
                selectedKomgaLibraryId = libraryId,
                filters = filters,
                listing = Listing.Search(query = null, filters = filters),
                toolbarQuery = null,
            )
        }
    }

    private suspend fun reloadKomgaState(
        komgaSource: KomgaSource,
        showRefreshing: Boolean,
        resetSelection: Boolean,
    ) {
        if (showRefreshing) {
            mutableState.update { it.copy(isRefreshing = true) }
        }

        if (!komgaSource.hasValidBaseUrl()) {
            val filters = FilterList()
            mutableState.update {
                it.copy(
                    listing = Listing.Search(query = null, filters = filters),
                    filters = filters,
                    komgaLibraries = persistentListOf(),
                    selectedKomgaLibraryId = null,
                    isRefreshing = false,
                )
            }
            refreshSignal.value += 1
            return
        }

        try {
            val libraries = komgaSource.getBrowseLibraries()
            val selectedLibraryId = if (resetSelection) {
                null
            } else {
                state.value.selectedKomgaLibraryId
                    ?.takeIf { selectedId -> libraries.any { it.id == selectedId } }
            }
            val filters = komgaSource.buildFilterListForLibrary(selectedLibraryId)

            mutableState.update {
                it.copy(
                    listing = Listing.Search(query = null, filters = filters),
                    filters = filters,
                    komgaLibraries = libraries.toImmutableList(),
                    selectedKomgaLibraryId = selectedLibraryId,
                    toolbarQuery = null,
                )
            }
        } catch (e: Exception) {
            if (resetSelection) {
                val filters = FilterList()
                mutableState.update {
                    it.copy(
                        listing = Listing.Search(query = null, filters = filters),
                        filters = filters,
                        komgaLibraries = persistentListOf(),
                        selectedKomgaLibraryId = null,
                        toolbarQuery = null,
                    )
                }
            }
            Log.e("KomgaLibraryScreenModel", "Failed to refresh Komga libraries", e)
        } finally {
            mutableState.update { it.copy(isRefreshing = false) }
            refreshSignal.value += 1
        }
    }

    override fun onDispose() {
        komgaSettingsChangeListener?.let { listener ->
            (source as? KomgaSource)?.unregisterServerSettingsChangeListener(listener)
        }
        komgaSettingsChangeListener = null
        super.onDispose()
    }

    sealed class Listing(open val query: String?, open val filters: FilterList) {
        data object Popular : Listing(query = GetRemoteManga.QUERY_POPULAR, filters = FilterList())
        data object Latest : Listing(query = GetRemoteManga.QUERY_LATEST, filters = FilterList())
        data class Search(
            override val query: String?,
            override val filters: FilterList,
        ) : Listing(query = query, filters = filters)

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteManga.QUERY_POPULAR -> Popular
                    GetRemoteManga.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = FilterList()) // filters are filled in later
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog
    }

    @Immutable
    data class State(
        val listing: Listing,
        val filters: FilterList = FilterList(),
        val toolbarQuery: String? = null,
        val dialog: Dialog? = null,
        val komgaLibraries: ImmutableList<LibraryDto> = persistentListOf(),
        val selectedKomgaLibraryId: String? = null,
        val isRefreshing: Boolean = false,
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
    }
}

private fun mergeRemoteWithLocal(remote: Manga, local: Manga?): Manga {
    if (local == null) return remote

    return remote.copy(
        id = local.id,
        favorite = local.favorite,
        lastUpdate = local.lastUpdate,
        nextUpdate = local.nextUpdate,
        fetchInterval = local.fetchInterval,
        dateAdded = local.dateAdded,
        viewerFlags = local.viewerFlags,
        chapterFlags = local.chapterFlags,
        coverLastModified = local.coverLastModified,
        updateStrategy = local.updateStrategy,
        initialized = local.initialized,
        lastModifiedAt = local.lastModifiedAt,
        favoriteModifiedAt = local.favoriteModifiedAt,
        version = local.version,
        notes = local.notes,
    )
}
