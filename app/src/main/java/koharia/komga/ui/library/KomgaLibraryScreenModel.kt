package koharia.komga.ui.library

import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import koharia.connection.ConnectionEpubHistorySyncAdapter
import koharia.connection.ConnectionHistorySyncAdapter
import koharia.connection.ConnectionPublicationAdapter
import koharia.epub.cache.EpubCacheManager
import koharia.komga.api.dto.KOMGA_LIBRARY_IDS_MEMO_KEY
import koharia.komga.api.dto.KOMGA_LIBRARY_ID_MEMO_KEY
import koharia.komga.api.dto.KomgaOfflineAuthor
import koharia.komga.api.dto.KomgaOfflineFilterMetadata
import koharia.komga.api.dto.LibraryDto
import koharia.komga.api.dto.mergeKomgaOfflineMemo
import koharia.komga.api.dto.offlineFilterMetadata
import koharia.komga.domain.repository.KomgaRepository
import koharia.komga.download.KomgaChapterMemo
import koharia.source.komga.AuthorFilter
import koharia.source.komga.AuthorGroup
import koharia.source.komga.CollectionSelect
import koharia.source.komga.InProgressFilter
import koharia.source.komga.KomgaClassifiedLibrary
import koharia.source.komga.KomgaLibraryClassificationManager
import koharia.source.komga.KomgaLibraryKind
import koharia.source.komga.KomgaLibraryScope
import koharia.source.komga.KomgaSource
import koharia.source.komga.LibraryFilter
import koharia.source.komga.OneshotFilter
import koharia.source.komga.ReadFilter
import koharia.source.komga.ReadingStateGroup
import koharia.source.komga.SeriesSort
import koharia.source.komga.TYPE_ALL_INDEX
import koharia.source.komga.TYPE_BOOKS_INDEX
import koharia.source.komga.TYPE_READ_LISTS_INDEX
import koharia.source.komga.TYPE_SERIES_INDEX
import koharia.source.komga.TypeSelect
import koharia.source.komga.UnreadFilter
import koharia.source.komga.UriMultiSelectFilter
import koharia.source.komga.UriMultiSelectOption
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
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
    private val updateManga: UpdateManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val epubCacheManager: EpubCacheManager,
    private val getIncognitoState: GetIncognitoState,
    private val libraryScope: KomgaLibraryScope,
    private val libraryClassificationManager: KomgaLibraryClassificationManager,
) : StateScreenModel<KomgaLibraryScreenModel.State>(
    State(
        listing = Listing.valueOf(listingQuery),
        isServerConfigured = (sourceManager.getOrStub(sourceId) as? KomgaSource)?.hasValidBaseUrl() == true,
    ),
) {
    var displayMode by sourcePreferences.sourceDisplayMode.asState(screenModelScope)
    var cachedOnly by basePreferences.downloadedOnly.asState(screenModelScope)
    private val refreshSignal = MutableStateFlow(0)

    val source = sourceManager.getOrStub(sourceId)
    private var komgaSettingsChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Volatile
    private var filtersInitialized = false
    private var listingBeforeSearch: Listing? = null
    private var libraryFilterBeforeQuickSelection: Set<String>? = null

    @Volatile
    private var latestCachedManga = emptyList<Manga>()

    init {
        if (source is CatalogueSource) {
            mutableState.update {
                var query: String? = null
                var listing = it.listing
                val filters = source.getFilterList()

                if (listing is Listing.Search) {
                    query = listing.query
                    listing = Listing.Search(query, filters)
                }

                it.copy(
                    listing = listing,
                    filters = filters,
                    toolbarQuery = query,
                    persistentFilteringEnabled =
                    (source as? KomgaSource)?.isPersistentFilteringEnabled(libraryScope) == true,
                )
            }
        }

        if (source is KomgaSource) {
            komgaSettingsChangeListener = source.registerServerSettingsChangeListener { shelfLibrariesChanged ->
                screenModelScope.launchIO {
                    source.invalidateBrowseCache()
                    if (basePreferences.downloadedOnly.get()) {
                        if (shelfLibrariesChanged) {
                            applyClassifiedLibraries(
                                source,
                                libraryClassificationManager.getLibraries(sourceId),
                                forceShelfLibrarySelection = true,
                            )
                        } else {
                            refreshSignal.value += 1
                        }
                    } else {
                        reloadKomgaState(
                            komgaSource = source,
                            showRefreshing = true,
                            resetSelection = true,
                            forceRefresh = true,
                            forceShelfLibrarySelection = shelfLibrariesChanged,
                        )
                    }
                }
            }
            if (!basePreferences.downloadedOnly.get()) {
                screenModelScope.launchIO {
                    reloadKomgaState(source, showRefreshing = false, resetSelection = true, forceRefresh = true)
                }
            }
            screenModelScope.launchIO {
                basePreferences.downloadedOnly.changes().collect { cachedOnly ->
                    if (cachedOnly) {
                        val filters = state.value.filters.withoutUnsupportedCachedSelections()
                        mutableState.update { current ->
                            current.copy(
                                filters = filters,
                                listing = Listing.Search(current.listing.query, filters),
                            )
                        }
                    } else {
                        reloadKomgaState(source, showRefreshing = true, resetSelection = true, forceRefresh = true)
                    }
                }
            }
            applyClassifiedLibraries(source, libraryClassificationManager.getLibraries(sourceId))
            screenModelScope.launchIO {
                libraryClassificationManager.classificationsChanges(sourceId).collect { libraries ->
                    applyClassifiedLibraries(source, libraries)
                }
            }
        }

        if (!getIncognitoState.await(source.id)) {
            sourcePreferences.lastUsedSource.set(source.id)
        }
    }

    val mangaPagerFlow: Flow<PagingData<StateFlow<Manga>>> = combine(
        state.map { it.listing }.distinctUntilChanged(),
        state.map { it.isLibraryScopeEmpty }.distinctUntilChanged(),
        state.map { it.isServerConfigured }.distinctUntilChanged(),
        basePreferences.downloadedOnly.changes().onStart { emit(basePreferences.downloadedOnly.get()) },
        refreshSignal,
    ) { listing, scopeEmpty, serverConfigured, cachedOnly, refreshSignal ->
        BrowseRequest(listing, scopeEmpty, serverConfigured, cachedOnly, refreshSignal)
    }.flatMapLatest { request ->
        if (request.scopeEmpty || !request.serverConfigured) {
            flowOf(PagingData.empty())
        } else if (request.cachedOnly) {
            combine(
                getManga.subscribeBySourceId(sourceId),
                downloadManager.cacheChanges,
                epubCacheManager.changes,
            ) { localManga, _, _ ->
                val chaptersByMangaId = mutableMapOf<Long, List<Chapter>>()
                suspend fun chaptersFor(manga: Manga): List<Chapter> {
                    chaptersByMangaId[manga.id]?.let { return it }
                    return getChaptersByMangaId.await(manga.id).also { chaptersByMangaId[manga.id] = it }
                }

                val query = (request.listing as? Listing.Search)?.query
                val selectedLibraryIds = request.listing.filters.selectedLibraryIds()
                val selectedContentType = request.listing.filters.selectedContentType()
                val selectedAdvancedFilters = request.listing.filters.cachedAdvancedFilterSelection()
                val hasCompleteEpubCache = epubCacheManager.hasAnyCompleteBook(sourceId)
                val publicationAdapter = source as? ConnectionPublicationAdapter
                val locallyAvailableManga = buildList {
                    for (manga in localManga) {
                        val isAvailable = downloadManager.getDownloadCount(manga) > 0 ||
                            (
                                hasCompleteEpubCache &&
                                    chaptersFor(manga).any {
                                        publicationAdapter?.hasCompleteCachedPublication(it) == true
                                    }
                                )
                        if (isAvailable) add(manga)
                    }
                }
                val cachedManga = locallyAvailableManga.withCachedLibraryIds(::chaptersFor)
                latestCachedManga = cachedManga
                val filteredManga = buildList {
                    for (manga in cachedManga) {
                        if (!manga.matchesCachedLibraryFilter(selectedLibraryIds)) continue
                        if (!manga.matchesCachedContentType(selectedContentType)) continue
                        if (!manga.matchesCachedOnlyQuery(query)) continue
                        val chapters = if (selectedAdvancedFilters.requiresReadingStatus) {
                            chaptersFor(manga)
                        } else {
                            emptyList()
                        }
                        if (!manga.matchesCachedAdvancedFilters(selectedAdvancedFilters, chapters)) continue
                        add(manga)
                    }
                }
                val cachedItems = filteredManga
                    .sortedForCachedFilters(selectedAdvancedFilters, request.refreshSignal)
                    .asSequence()
                    .map { MutableStateFlow(it) as StateFlow<Manga> }
                    .toList()
                PagingData.from(
                    data = cachedItems,
                    sourceLoadStates = CACHED_ONLY_LOAD_STATES,
                )
            }
        } else {
            Pager(PagingConfig(pageSize = 25)) {
                getRemoteManga(sourceId, request.listing.query ?: "", request.listing.filters)
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
            }
        }
    }
        .cachedIn(ioCoroutineScope)

    private suspend fun List<Manga>.withCachedLibraryIds(
        chaptersFor: suspend (Manga) -> List<Chapter>,
    ): List<Manga> {
        val komgaSource = source as? KomgaSource ?: return this
        val updates = mutableListOf<MangaUpdate>()
        val enriched = map { manga ->
            if (manga.cachedLibraryIds.isNotEmpty()) return@map manga

            val libraryIds = manga.resolveCachedLibraryIds(komgaSource, chaptersFor)
            if (libraryIds.isEmpty()) return@map manga
            val updatedMemo = JsonObject(
                manga.memo +
                    (KOMGA_LIBRARY_IDS_MEMO_KEY to JsonArray(libraryIds.map(::JsonPrimitive))) +
                    if (libraryIds.size == 1) {
                        mapOf(KOMGA_LIBRARY_ID_MEMO_KEY to JsonPrimitive(libraryIds.single()))
                    } else {
                        emptyMap()
                    },
            )
            updates += MangaUpdate(id = manga.id, memo = updatedMemo)
            manga.copy(memo = updatedMemo)
        }
        if (updates.isNotEmpty()) {
            updateManga.awaitAll(updates)
        }
        return enriched
    }

    private suspend fun Manga.resolveCachedLibraryIds(
        komgaSource: KomgaSource,
        chaptersFor: suspend (Manga) -> List<Chapter>,
    ): Set<String> {
        val cachedIds = komgaSource.findCachedLibraryIds(url)
        if (cachedIds.isNotEmpty()) return cachedIds

        return chaptersFor(this).mapNotNullTo(linkedSetOf()) { chapter ->
            KomgaChapterMemo.libraryId(chapter.memo)
                ?: KomgaChapterMemo.readFingerprint(chapter.memo)?.bookUrl?.let(komgaSource::findCachedLibraryId)
        }
    }

    fun resetFilters() {
        if (source !is CatalogueSource) return

        (source as? KomgaSource)?.run {
            resetPersistentFilters(libraryScope)
            resetSessionFilterState(libraryScope)
        }
        val filters = (
            (source as? KomgaSource)?.buildFilterListForLibrary(
                libraryId = state.value.selectedKomgaLibraryId,
                allowedLibraryIds = currentAllowedLibraryIds(),
                libraryScope = libraryScope,
            ) ?: source.getFilterList()
            ).let { filters ->
            if (basePreferences.downloadedOnly.get()) {
                filters.withCachedMetadataOptions(latestCachedManga).withoutUnsupportedCachedSelections()
            } else {
                filters
            }
        }
        mutableState.update { it.copy(filters = filters) }
    }

    fun setListing(listing: Listing) {
        listingBeforeSearch = null
        mutableState.update { it.copy(listing = listing, toolbarQuery = null, searchType = TYPE_ALL_INDEX) }
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

        val currentListing = state.value.listing
        val input = currentListing as? Listing.Search
            ?: Listing.Search(
                query = null,
                filters = (source as? KomgaSource)?.buildFilterListForLibrary(
                    libraryId = state.value.selectedKomgaLibraryId,
                    allowedLibraryIds = currentAllowedLibraryIds(),
                    libraryScope = libraryScope,
                    currentFilters = state.value.filters,
                ) ?: source.getFilterList(),
            )

        val nextFilters = when {
            filters != null -> filters
            query != null -> buildSearchFilters(state.value.filters, state.value.searchType)
            else -> state.value.filters.takeIf { it.isNotEmpty() } ?: input.filters
        }
        if (!query.isNullOrEmpty() && input.query.isNullOrEmpty() && listingBeforeSearch == null) {
            listingBeforeSearch = currentListing
        }
        if (filters != null) {
            (source as? KomgaSource)?.saveSessionFilterState(nextFilters, libraryScope)
            (source as? KomgaSource)?.savePersistentFilterState(nextFilters, libraryScope)
        }

        mutableState.update {
            it.copy(
                listing = input.copy(
                    query = query ?: input.query,
                    filters = nextFilters,
                ),
                toolbarQuery = query ?: input.query,
            )
        }
    }

    fun exitSearch() {
        val currentListing = state.value.listing
        val restoredListing = listingBeforeSearch ?: when (currentListing) {
            is Listing.Search -> currentListing.copy(query = null, filters = state.value.filters)
            else -> currentListing
        }
        listingBeforeSearch = null
        mutableState.update {
            it.copy(
                listing = restoredListing,
                toolbarQuery = null,
                searchType = TYPE_ALL_INDEX,
            )
        }
    }

    fun setPersistentFilteringEnabled(enabled: Boolean) {
        val komgaSource = source as? KomgaSource ?: return
        komgaSource.setPersistentFilteringEnabled(enabled, state.value.filters, libraryScope)
        mutableState.update { it.copy(persistentFilteringEnabled = enabled) }
    }

    fun searchGenre(genreName: String) {
        if (source !is CatalogueSource) return

        if (source is KomgaSource) {
            val filters = source.buildFilterListForTagSearch(
                tag = genreName,
                allowedLibraryIds = currentAllowedLibraryIds(),
                libraryScope = libraryScope,
            )
            source.saveSessionFilterState(filters, libraryScope)
            source.savePersistentFilterState(filters, libraryScope)
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
        if (basePreferences.downloadedOnly.get()) {
            val filters = state.value.filters.withCachedMetadataOptions(latestCachedManga)
            mutableState.update { current ->
                current.copy(
                    filters = filters,
                    listing = Listing.Search(current.listing.query, filters),
                )
            }
        }
        setDialog(Dialog.Filter)
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun setToolbarQuery(query: String?) {
        mutableState.update {
            it.copy(
                toolbarQuery = query,
                searchType = if (query == null || it.toolbarQuery == null) TYPE_ALL_INDEX else it.searchType,
            )
        }
    }

    fun setSearchType(type: Int) {
        if (type !in SEARCH_TYPES) return
        val currentState = state.value
        if (currentState.searchType == type) return

        val activeQuery = currentState.toolbarQuery
            ?.takeIf { currentState.isUserQuery && it.isNotBlank() }
        val listing = if (activeQuery != null) {
            (currentState.listing as Listing.Search).copy(
                query = activeQuery,
                filters = buildSearchFilters(currentState.filters, type),
            )
        } else {
            currentState.listing
        }
        mutableState.update {
            it.copy(
                listing = listing,
                searchType = type,
            )
        }
    }

    private fun buildSearchFilters(filters: FilterList, searchType: Int): FilterList {
        val komgaSource = source as? KomgaSource ?: return filters
        return komgaSource.buildFilterListForLibrary(
            libraryId = state.value.selectedKomgaLibraryId,
            allowedLibraryIds = currentAllowedLibraryIds(),
            libraryScope = libraryScope,
            currentFilters = filters,
        ).apply {
            selectContentType(searchType)
        }
    }

    fun refresh() {
        if (basePreferences.downloadedOnly.get()) {
            refreshSignal.value += 1
            return
        }
        val komgaSource = source as? KomgaSource
        if (komgaSource != null) {
            screenModelScope.launchIO {
                try {
                    komgaSource.invalidateBrowseCache()
                    reloadKomgaState(
                        komgaSource = komgaSource,
                        showRefreshing = true,
                        resetSelection = false,
                        forceRefresh = true,
                        finishRefreshing = false,
                        emitRefreshSignal = false,
                    )
                    runCatching {
                        (komgaSource as? ConnectionHistorySyncAdapter)?.syncConnectionHistory()
                        (komgaSource as? ConnectionEpubHistorySyncAdapter)?.syncConnectionEpubProgress()
                    }.onFailure { error ->
                        logcat(LogPriority.WARN, error) {
                            "Failed to sync Komga progress during manual library refresh sourceId=$sourceId"
                        }
                    }
                } finally {
                    mutableState.update { it.copy(isRefreshing = false) }
                    refreshSignal.value += 1
                }
            }
        } else {
            refreshSignal.value += 1
        }
    }

    fun selectKomgaLibrary(libraryId: String?) {
        val komgaSource = source as? KomgaSource ?: return
        val currentState = state.value
        if (currentState.selectedKomgaLibraryId == libraryId) return

        if (currentState.selectedKomgaLibraryId == null && libraryId != null) {
            libraryFilterBeforeQuickSelection = currentState.filters.selectedLibraryIds()
        }
        val restoredLibraryIds = if (libraryId == null) libraryFilterBeforeQuickSelection else null
        val filters = komgaSource.buildFilterListForLibrary(
            libraryId = libraryId,
            allowedLibraryIds = currentAllowedLibraryIds(),
            libraryScope = libraryScope,
            currentFilters = currentState.filters,
            librarySelectionOverride = restoredLibraryIds,
            resetLibrarySelection = libraryId == null && restoredLibraryIds == null,
        )
        mutableState.update {
            it.copy(
                selectedKomgaLibraryId = libraryId,
                filters = filters,
                listing = Listing.Search(query = null, filters = filters),
                toolbarQuery = null,
                searchType = TYPE_ALL_INDEX,
            )
        }
        if (libraryId == null) {
            libraryFilterBeforeQuickSelection = null
        }
        komgaSource.saveSessionFilterState(filters, libraryScope)
        if (!basePreferences.downloadedOnly.get()) {
            komgaSource.refreshBrowseRequests()
        }
        refreshSignal.value += 1
    }

    private suspend fun reloadKomgaState(
        komgaSource: KomgaSource,
        showRefreshing: Boolean,
        resetSelection: Boolean,
        forceRefresh: Boolean,
        forceShelfLibrarySelection: Boolean = false,
        finishRefreshing: Boolean = true,
        emitRefreshSignal: Boolean = true,
    ) {
        if (resetSelection) {
            libraryFilterBeforeQuickSelection = null
        }
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
                    isRefreshing = if (finishRefreshing) false else it.isRefreshing,
                    isServerConfigured = false,
                    isLibraryScopeEmpty = false,
                    toolbarQuery = null,
                    searchType = TYPE_ALL_INDEX,
                    persistentFilteringEnabled = komgaSource.isPersistentFilteringEnabled(libraryScope),
                )
            }
            if (emitRefreshSignal) {
                refreshSignal.value += 1
            }
            return
        }

        mutableState.update { it.copy(isServerConfigured = true) }

        try {
            val libraries = komgaSource.getBrowseLibraries(forceRefresh)
            libraryClassificationManager.updateLibraries(sourceId, libraries)
            val visibleLibraries = librariesForScope(libraries)
            val selectedLibraryId = if (resetSelection) {
                null
            } else {
                state.value.selectedKomgaLibraryId
                    ?.takeIf { selectedId -> visibleLibraries.any { it.id == selectedId } }
            }
            val filters = komgaSource.buildFilterListForLibrary(
                libraryId = selectedLibraryId,
                preservePersistentFilters = selectedLibraryId == null,
                allowedLibraryIds = visibleLibraries.idsForScope(),
                libraryScope = libraryScope,
                currentFilters = currentFiltersForReload(),
                preserveSessionFilters = true,
                resetLibrarySelection = resetSelection,
                forceConfiguredLibrarySelection = forceShelfLibrarySelection,
            )

            mutableState.update {
                it.copy(
                    listing = Listing.Search(query = null, filters = filters),
                    filters = filters,
                    komgaLibraries = visibleLibraries.toImmutableList(),
                    selectedKomgaLibraryId = selectedLibraryId,
                    isLibraryScopeEmpty = libraryScope != KomgaLibraryScope.ALL && visibleLibraries.isEmpty(),
                    toolbarQuery = null,
                    searchType = TYPE_ALL_INDEX,
                    persistentFilteringEnabled = komgaSource.isPersistentFilteringEnabled(libraryScope),
                )
            }
            filtersInitialized = true
            komgaSource.saveSessionFilterState(filters, libraryScope)
            if (forceShelfLibrarySelection) {
                komgaSource.savePersistentFilterState(filters, libraryScope)
            }
            if (forceRefresh) {
                komgaSource.refreshBrowseRequests()
            }
        } catch (e: Exception) {
            if (libraryScope != KomgaLibraryScope.ALL) {
                applyClassifiedLibraries(
                    komgaSource,
                    libraryClassificationManager.getLibraries(sourceId),
                )
            } else if (resetSelection) {
                val filters = FilterList()
                mutableState.update {
                    it.copy(
                        listing = Listing.Search(query = null, filters = filters),
                        filters = filters,
                        komgaLibraries = persistentListOf(),
                        selectedKomgaLibraryId = null,
                        toolbarQuery = null,
                        searchType = TYPE_ALL_INDEX,
                        persistentFilteringEnabled = komgaSource.isPersistentFilteringEnabled(libraryScope),
                    )
                }
            }
            Log.e("KomgaLibraryScreenModel", "Failed to refresh Komga libraries", e)
        } finally {
            if (finishRefreshing) {
                mutableState.update { it.copy(isRefreshing = false) }
            }
            if (emitRefreshSignal) {
                refreshSignal.value += 1
            }
        }
    }

    override fun onDispose() {
        komgaSettingsChangeListener?.let { listener ->
            (source as? KomgaSource)?.unregisterServerSettingsChangeListener(listener)
        }
        komgaSettingsChangeListener = null
        super.onDispose()
    }

    private fun applyClassifiedLibraries(
        komgaSource: KomgaSource,
        libraries: List<KomgaClassifiedLibrary>,
        forceShelfLibrarySelection: Boolean = false,
    ) {
        val configuredLibraryIds = komgaSource.configuredShelfLibraryIds()
        val visibleLibraries = libraries
            .filter { library ->
                (libraryScope == KomgaLibraryScope.ALL || library.kind == libraryScope.kind) &&
                    (configuredLibraryIds.isEmpty() || library.id in configuredLibraryIds)
            }
            .map { LibraryDto(it.id, it.name) }
        if (!forceShelfLibrarySelection && filtersInitialized && state.value.komgaLibraries == visibleLibraries) return
        val selectedLibraryId = state.value.selectedKomgaLibraryId
            ?.takeIf { selectedId -> visibleLibraries.any { it.id == selectedId } }
        val filters = komgaSource.buildFilterListForLibrary(
            libraryId = selectedLibraryId,
            preservePersistentFilters = selectedLibraryId == null,
            allowedLibraryIds = visibleLibraries.idsForScope(),
            libraryScope = libraryScope,
            currentFilters = currentFiltersForReload(),
            preserveSessionFilters = true,
            fallbackLibraries = visibleLibraries,
            forceConfiguredLibrarySelection = forceShelfLibrarySelection,
        ).let { filters ->
            if (basePreferences.downloadedOnly.get()) filters.withoutUnsupportedCachedSelections() else filters
        }
        mutableState.update {
            it.copy(
                listing = Listing.Search(query = null, filters = filters),
                filters = filters,
                toolbarQuery = null,
                searchType = TYPE_ALL_INDEX,
                komgaLibraries = visibleLibraries.toImmutableList(),
                selectedKomgaLibraryId = selectedLibraryId,
                isLibraryScopeEmpty = libraryScope != KomgaLibraryScope.ALL && visibleLibraries.isEmpty(),
            )
        }
        filtersInitialized = true
        komgaSource.saveSessionFilterState(filters, libraryScope)
        if (forceShelfLibrarySelection) {
            komgaSource.savePersistentFilterState(filters, libraryScope)
        }
        refreshSignal.value += 1
    }

    private fun librariesForScope(libraries: List<LibraryDto>): List<LibraryDto> {
        val configuredLibraryIds = (source as? KomgaSource)?.configuredShelfLibraryIds().orEmpty()
        val shelfLibraries = if (configuredLibraryIds.isEmpty()) {
            libraries
        } else {
            libraries.filter { it.id in configuredLibraryIds }
        }
        if (libraryScope == KomgaLibraryScope.ALL) return shelfLibraries
        val kindById = libraryClassificationManager.getLibraries(sourceId).associate { it.id to it.kind }
        return shelfLibraries.filter { library ->
            (kindById[library.id] ?: KomgaLibraryKind.COMIC) == libraryScope.kind
        }
    }

    private fun List<LibraryDto>.idsForScope(): Set<String>? {
        val shelfIsRestricted = (source as? KomgaSource)?.configuredShelfLibraryIds()?.isNotEmpty() == true
        return if (libraryScope == KomgaLibraryScope.ALL && !shelfIsRestricted) {
            null
        } else {
            mapTo(linkedSetOf(), LibraryDto::id)
        }
    }

    private fun currentAllowedLibraryIds(): Set<String>? {
        return state.value.komgaLibraries.idsForScope()
    }

    private fun currentFiltersForReload(): FilterList? {
        if (!filtersInitialized) return null
        return state.value.filters.takeIf { it.isNotEmpty() }
    }

    private fun FilterList.selectedLibraryIds(): Set<String> {
        return filterIsInstance<LibraryFilter>()
            .firstOrNull()
            ?.state
            .orEmpty()
            .filter { it.state }
            .mapTo(linkedSetOf()) { it.id }
    }

    private fun FilterList.selectedContentType(): Int {
        return filterIsInstance<koharia.source.komga.TypeSelect>()
            .firstOrNull()
            ?.state
            ?: TYPE_ALL_INDEX
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
        val isLibraryScopeEmpty: Boolean = false,
        val isServerConfigured: Boolean = false,
        val persistentFilteringEnabled: Boolean = false,
        val searchType: Int = TYPE_ALL_INDEX,
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
    }

    private companion object {
        val SEARCH_TYPES = setOf(
            TYPE_SERIES_INDEX,
            TYPE_READ_LISTS_INDEX,
            TYPE_BOOKS_INDEX,
            TYPE_ALL_INDEX,
        )
    }
}

private val KomgaLibraryScope.kind: KomgaLibraryKind
    get() = when (this) {
        KomgaLibraryScope.ALL -> KomgaLibraryKind.COMIC
        KomgaLibraryScope.COMIC -> KomgaLibraryKind.COMIC
        KomgaLibraryScope.BOOK -> KomgaLibraryKind.BOOK
    }

private data class BrowseRequest(
    val listing: KomgaLibraryScreenModel.Listing,
    val scopeEmpty: Boolean,
    val serverConfigured: Boolean,
    val cachedOnly: Boolean,
    val refreshSignal: Int,
)

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
        memo = mergeKomgaOfflineMemo(local.memo, remote.memo) ?: local.memo,
    )
}

internal fun Manga.matchesCachedOnlyQuery(query: String?): Boolean {
    val normalizedQuery = query?.trim().orEmpty()
    if (normalizedQuery.isEmpty()) return true

    return title.contains(normalizedQuery, ignoreCase = true) ||
        author?.contains(normalizedQuery, ignoreCase = true) == true ||
        artist?.contains(normalizedQuery, ignoreCase = true) == true ||
        genre.orEmpty().any { it.contains(normalizedQuery, ignoreCase = true) }
}

internal fun Manga.matchesCachedLibraryFilter(selectedLibraryIds: Set<String>): Boolean {
    if (selectedLibraryIds.isEmpty()) return true
    return cachedLibraryIds.any { it in selectedLibraryIds }
}

internal fun Manga.matchesCachedContentType(contentType: Int): Boolean {
    val path = url.substringBefore('?').trimEnd('/')
    return when (contentType) {
        TYPE_SERIES_INDEX -> path.contains("/api/v1/series/")
        TYPE_READ_LISTS_INDEX -> path.contains("/api/v1/readlists/")
        TYPE_BOOKS_INDEX -> path.contains("/api/v1/books/")
        else -> true
    }
}

internal data class CachedAdvancedFilterSelection(
    val readingStatuses: Set<String> = emptySet(),
    val statuses: Set<String> = emptySet(),
    val genres: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val publishers: Set<String> = emptySet(),
    val authors: Set<KomgaOfflineAuthor> = emptySet(),
    val oneShot: Boolean = false,
    val sortIndex: Int = 0,
    val sortAscending: Boolean = true,
) {
    val requiresReadingStatus: Boolean get() = readingStatuses.isNotEmpty()
}

internal fun FilterList.cachedAdvancedFilterSelection(): CachedAdvancedFilterSelection {
    val readingFilters = filterIsInstance<ReadingStateGroup>().firstOrNull()?.state.orEmpty()
    val contentType = filterIsInstance<TypeSelect>().firstOrNull()?.state ?: TYPE_ALL_INDEX
    val readingStatuses = buildSet {
        if (readingFilters.filterIsInstance<UnreadFilter>().firstOrNull()?.state == true) {
            add("UNREAD")
            add("IN_PROGRESS")
        }
        if (readingFilters.filterIsInstance<InProgressFilter>().firstOrNull()?.state == true) {
            add("IN_PROGRESS")
        }
        if (readingFilters.filterIsInstance<ReadFilter>().firstOrNull()?.state == true) {
            add("READ")
        }
    }
    val sort = filterIsInstance<SeriesSort>().firstOrNull()?.state
    return CachedAdvancedFilterSelection(
        readingStatuses = readingStatuses,
        statuses = selectedCachedOptions("Status"),
        genres = selectedCachedOptions("Genres"),
        tags = selectedCachedOptions("Tags"),
        publishers = selectedCachedOptions("Publishers"),
        authors = filterIsInstance<AuthorGroup>()
            .flatMap { group ->
                group.state.filter { it.state }.map { KomgaOfflineAuthor(it.author.name, it.author.role) }
            }
            .toSet(),
        oneShot = contentType == TYPE_SERIES_INDEX &&
            readingFilters.filterIsInstance<OneshotFilter>().firstOrNull()?.state == true,
        sortIndex = sort?.index ?: 0,
        sortAscending = sort?.ascending ?: true,
    )
}

internal fun Manga.matchesCachedAdvancedFilters(
    filters: CachedAdvancedFilterSelection,
    chapters: List<Chapter> = emptyList(),
): Boolean {
    val metadata = memo.offlineFilterMetadata()
    if (filters.statuses.isNotEmpty() && !(cachedSeriesStatus(metadata) inIgnoreCase filters.statuses)) return false
    if (filters.genres.isNotEmpty() && metadata?.genres.orEmpty() hasNoValueIn filters.genres) return false
    if (filters.tags.isNotEmpty() && metadata?.tags.orEmpty() hasNoValueIn filters.tags) return false
    if (filters.publishers.isNotEmpty() && setOfNotNull(cachedPublisher(metadata)) hasNoValueIn filters.publishers) {
        return false
    }
    if (filters.authors.isNotEmpty() && metadata?.authors.orEmpty().none { cachedAuthor ->
            filters.authors.any { selected ->
                cachedAuthor.name.equals(selected.name, ignoreCase = true) &&
                    cachedAuthor.role.equals(selected.role, ignoreCase = true)
            }
        }
    ) {
        return false
    }
    if (filters.oneShot && metadata?.oneShot != true) return false
    if (filters.readingStatuses.isNotEmpty() && chapters.cachedReadingStatus() !in filters.readingStatuses) return false
    return true
}

internal fun List<Manga>.sortedForCachedFilters(
    filters: CachedAdvancedFilterSelection,
    randomSeed: Int,
): List<Manga> {
    val sorted = when (filters.sortIndex) {
        1 -> sortedWith(compareBy<Manga> { it.cachedTitleSort().lowercase() }.thenBy { it.id })
        2 -> sortedWith(compareBy<Manga> { it.cachedCreatedTimestamp() }.thenBy { it.id })
        3 -> sortedWith(compareBy<Manga> { it.cachedModifiedTimestamp() }.thenBy { it.id })
        4 -> sortedWith(compareBy<Manga> { cachedRandomKey(it.id, randomSeed) }.thenBy { it.id })
        else -> this
    }
    return if (filters.sortAscending) sorted else sorted.asReversed()
}

internal fun FilterList.withCachedMetadataOptions(mangas: List<Manga>): FilterList {
    val metadata = mangas.mapNotNull { manga -> manga.memo.offlineFilterMetadata()?.let { manga to it } }
    val cachedValues = mapOf(
        "Status" to mangas.mapNotNullTo(linkedSetOf()) { it.cachedSeriesStatus(it.memo.offlineFilterMetadata()) },
        "Genres" to metadata.flatMapTo(linkedSetOf()) { it.second.genres },
        "Tags" to metadata.flatMapTo(linkedSetOf()) { it.second.tags },
        "Publishers" to mangas.mapNotNullTo(linkedSetOf()) { it.cachedPublisher(it.memo.offlineFilterMetadata()) },
    )
    val updated = map { filter ->
        if (filter is UriMultiSelectFilter && filter !is LibraryFilter) {
            val selected = filter.state.filter { it.state }.mapTo(linkedSetOf()) { it.id }
            val labels = filter.state.associate { it.id to it.name }
            val values = cachedValues[filter.name].orEmpty() + selected
            UriMultiSelectFilter(
                filter.name,
                values.sortedWith(String.CASE_INSENSITIVE_ORDER).map { id ->
                    UriMultiSelectOption(labels[id] ?: id, id).apply { state = id in selected }
                },
            )
        } else {
            filter
        }
    }.toMutableList()

    val selectedAuthors = filterIsInstance<AuthorGroup>()
        .flatMap { group -> group.state.filter { it.state }.map { it.author } }
        .associateBy { it.name to it.role }
    val cachedAuthors = metadata.flatMapTo(linkedSetOf()) { it.second.authors }
    val authors = (cachedAuthors + selectedAuthors.values.map { KomgaOfflineAuthor(it.name, it.role) })
        .groupBy(KomgaOfflineAuthor::role)
    val firstAuthorIndex = updated.indexOfFirst { it is AuthorGroup }
    updated.removeAll { it is AuthorGroup }
    val insertionIndex = firstAuthorIndex.takeIf { it >= 0 }
        ?: updated.indexOfFirst { it is SeriesSort }.takeIf { it >= 0 }
        ?: updated.size
    updated.addAll(
        insertionIndex,
        authors.toSortedMap(String.CASE_INSENSITIVE_ORDER).map { (role, roleAuthors) ->
            AuthorGroup(
                role,
                roleAuthors.sortedBy { it.name.lowercase() }.map { author ->
                    AuthorFilter(koharia.komga.api.dto.AuthorDto(author.name, author.role)).apply {
                        state = (author.name to author.role) in selectedAuthors
                    }
                },
            )
        },
    )
    return FilterList(updated)
}

private fun FilterList.withoutUnsupportedCachedSelections(): FilterList = FilterList(
    map { filter ->
        if (filter is CollectionSelect && filter.state != 0) {
            CollectionSelect(filter.collections).apply { state = 0 }
        } else {
            filter
        }
    },
)

private fun FilterList.selectedCachedOptions(name: String): Set<String> =
    filterIsInstance<UriMultiSelectFilter>()
        .firstOrNull { it.name == name }
        ?.state
        .orEmpty()
        .filter { it.state }
        .mapTo(linkedSetOf()) { it.id }

private fun Manga.cachedSeriesStatus(metadata: KomgaOfflineFilterMetadata?): String? =
    metadata?.status ?: when (status.toInt()) {
        SManga.ONGOING -> "ONGOING"
        SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> "ENDED"
        SManga.CANCELLED -> "ABANDONED"
        SManga.ON_HIATUS -> "HIATUS"
        else -> null
    }

private fun Manga.cachedPublisher(metadata: KomgaOfflineFilterMetadata?): String? =
    metadata?.publisher ?: memo["publisher"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

private fun Manga.cachedTitleSort(): String =
    memo.offlineFilterMetadata()?.titleSort ?: title

private fun Manga.cachedCreatedTimestamp(): Long =
    memo.offlineFilterMetadata()?.createdDate?.let { KomgaRepository.parseDateTime(it) }?.takeIf { it > 0L }
        ?: dateAdded

private fun Manga.cachedModifiedTimestamp(): Long =
    memo.offlineFilterMetadata()?.lastModifiedDate?.let { KomgaRepository.parseDateTime(it) }?.takeIf { it > 0L }
        ?: lastModifiedAt

private fun List<Chapter>.cachedReadingStatus(): String? = when {
    isEmpty() -> null
    all(Chapter::read) -> "READ"
    any { it.read || it.lastPageRead > 0L } -> "IN_PROGRESS"
    else -> "UNREAD"
}

private infix fun String?.inIgnoreCase(values: Set<String>): Boolean =
    this != null && values.any { equals(it, ignoreCase = true) }

private infix fun Set<String>.hasNoValueIn(values: Set<String>): Boolean =
    none { cached -> values.any { cached.equals(it, ignoreCase = true) } }

private fun cachedRandomKey(mangaId: Long, seed: Int): Long {
    var value = mangaId xor (seed.toLong() shl 32)
    value = (value xor (value ushr 33)) * -49064778989728563L
    value = (value xor (value ushr 33)) * -4265267296055464877L
    return value xor (value ushr 33)
}

private val Manga.cachedLibraryId: String?
    get() = memo[KOMGA_LIBRARY_ID_MEMO_KEY]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }

private val Manga.cachedLibraryIds: Set<String>
    get() = buildSet {
        cachedLibraryId?.let(::add)
        (memo[KOMGA_LIBRARY_IDS_MEMO_KEY] as? JsonArray)
            .orEmpty()
            .mapNotNullTo(this) { element ->
                element.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank)
            }
    }

internal val CACHED_ONLY_LOAD_STATES = LoadStates(
    refresh = LoadState.NotLoading(endOfPaginationReached = true),
    prepend = LoadState.NotLoading(endOfPaginationReached = true),
    append = LoadState.NotLoading(endOfPaginationReached = true),
)
