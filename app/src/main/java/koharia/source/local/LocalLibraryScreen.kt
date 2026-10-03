package koharia.source.local

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import koharia.connection.ConnectionBrowseScreen
import koharia.connection.ConnectionPreferences
import koharia.connection.EntryOpenMode
import koharia.connection.EntryOpenPreferences
import koharia.connection.LibraryContentScope
import koharia.connection.ui.ConnectionLibraryShelfDialog
import koharia.connection.ui.ConnectionSearchResults
import koharia.connection.ui.ConnectionSearchSortOption
import koharia.connection.ui.SeriesMetadataEditScreen
import koharia.domain.epub.interactor.GetEpubProgress
import koharia.epub.EpubReaderLauncher
import koharia.importing.ExternalMediaImportScreen
import koharia.importing.ImageComicScreen
import koharia.lanraragi.ui.LanraragiArchivePreviewScreen
import koharia.media.LocalMediaFormats
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.data.source.NoResultsException
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkLinearProgressIndicator
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.jvm.Transient

data class LocalLibraryScreen(
    override val sourceId: Long,
    private val scope: LibraryContentScope,
    private val initialQuery: String?,
    private val showNavigationUp: Boolean = true,
    private val parentUrl: String? = null,
) : Screen(), ConnectionBrowseScreen {

    override val refreshOnReselect: Boolean = false

    @Transient
    @Volatile
    private var runtimeEvents: RuntimeEvents? = null

    private fun events(): RuntimeEvents {
        runtimeEvents?.let { return it }
        return synchronized(this) {
            runtimeEvents ?: RuntimeEvents().also { runtimeEvents = it }
        }
    }

    @OptIn(ExperimentalMaterialApi::class)
    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val sourceManager: SourceManager = Injekt.get()
        val sourcePreferences: SourcePreferences = Injekt.get()
        val libraryPreferences: LibraryPreferences = Injekt.get()
        val connectionPreferences: ConnectionPreferences = Injekt.get()
        val mangaRepository: MangaRepository = Injekt.get()
        val chapterRepository: ChapterRepository = Injekt.get()
        val getEpubProgress: GetEpubProgress = Injekt.get()
        val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get()
        val updateManga: UpdateManga = Injekt.get()
        val coverCache: CoverCache = Injekt.get()
        val epubReaderLauncher = remember { EpubReaderLauncher() }
        val coroutineScope = rememberCoroutineScope()
        val screenModel = rememberScreenModel(tag = "$sourceId:$scope:$initialQuery:$parentUrl") {
            LocalLibraryScreenModel(
                sourceId = sourceId,
                scope = scope,
                initialQuery = initialQuery,
                parentUrl = parentUrl,
                sourceManager = sourceManager,
                sourcePreferences = sourcePreferences,
                mangaRepository = mangaRepository,
                getChaptersByMangaId = Injekt.get(),
                getEpubProgress = getEpubProgress,
                libraryPreferences = libraryPreferences,
                entryOpenManager = LocalLibraryEntryOpenManager(
                    syncChaptersWithSource = syncChaptersWithSource,
                    chapterRepository = chapterRepository,
                    epubReaderLauncher = epubReaderLauncher,
                ),
                updateManga = updateManga,
                coverCache = coverCache,
                itemActions = LocalLibraryItemActions(
                    syncChaptersWithSource = syncChaptersWithSource,
                    chapterRepository = chapterRepository,
                    setReadStatus = Injekt.get(),
                    epubProgressRepository = Injekt.get(),
                ),
            )
        }
        val state by screenModel.state.collectAsState()
        val parentManga by screenModel.parentManga.collectAsState(initial = null)
        val seriesDisplayMode by libraryPreferences.chapterCoverDisplayMode.collectAsState()
        val displayMode = if (parentUrl == null) {
            screenModel.displayMode
        } else {
            when (seriesDisplayMode) {
                Manga.CHAPTER_COVER_DISPLAY_TEXT -> LibraryDisplayMode.List
                Manga.CHAPTER_COVER_DISPLAY_COVER -> LibraryDisplayMode.CoverOnlyGrid
                Manga.CHAPTER_COVER_DISPLAY_COMFORTABLE -> LibraryDisplayMode.ComfortableGrid
                else -> LibraryDisplayMode.CompactGrid
            }
        }
        var operationRecovery by remember { mutableStateOf(false) }
        var folderOperation by remember { mutableStateOf<Pair<LocalFolderOperation, String?>?>(null) }
        val localFolderSource = screenModel.source as? LocalFolderSource
        val selectedIds = state.selectedMangas.mapTo(mutableSetOf()) { it.id }
        BackHandler(enabled = selectedIds.isNotEmpty() || state.isBusy) {
            screenModel.clearSelection()
        }
        val coverUpdatedMessage = stringResource(MR.strings.cover_updated)
        val readProgressPreference = if (parentUrl == null) {
            libraryPreferences.showLibraryReadProgress
        } else {
            libraryPreferences.showChapterReadProgress
        }
        val showLibraryReadProgress by readProgressPreference.collectAsState()
        val readProgressByUrl by screenModel.readProgressByUrl.collectAsState()
        val configuration = LocalConfiguration.current
        val columnsPreference = if (parentUrl != null) {
            if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                libraryPreferences.chapterCoverGridLandscapeColumns
            } else {
                libraryPreferences.chapterCoverGridColumns
            }
        } else if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            libraryPreferences.landscapeColumns
        } else {
            libraryPreferences.portraitColumns
        }
        val columns by columnsPreference.collectAsState()
        val connectionProfiles by connectionPreferences.profilesChanges()
            .collectAsState(initial = connectionPreferences.getProfiles())
        val mangaList = screenModel.mangaPagerFlow.collectAsLazyPagingItems()
        val navigator = LocalNavigator.currentOrThrow
        val entryOpenPreferences = remember { Injekt.get<EntryOpenPreferences>() }
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val snackbarHostState = remember { SnackbarHostState() }
        val importFiles = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            navigator.push(
                ExternalMediaImportScreen(
                    uriValues = uris.map(android.net.Uri::toString),
                    startAtImportConfiguration = true,
                    restrictedConnectionId = sourceId,
                    allowCrossConnectionForEpub = parentUrl == null,
                    targetFolderUrl = parentUrl,
                    preferredShelfId = state.selectedBookshelfId,
                    returnToCallerAfterImport = true,
                ),
            )
        }
        val canMergeCurrentFolderImageSeries by produceState(false, parentUrl, state.isRefreshing) {
            value = parentUrl != null && localFolderSource?.canMergeImageSeries(parentUrl) == true
        }
        fun convertCurrentFolderToComic() {
            val folder = parentManga ?: return
            coroutineScope.launch {
                if (localFolderSource?.setImageComic(folder.url, true)?.isSuccess == true) {
                    // The folder remains a container; its merged image entry is refreshed alongside its children.
                } else {
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.local_library_image_mode_failed))
                }
            }
        }

        val openImportPicker = {
            val mimeTypes = if (parentUrl != null || localFolderSource?.folderRoots()?.isNotEmpty() == true) {
                LocalMediaFormats.allMimeTypes + "application/octet-stream"
            } else {
                LocalMediaFormats.documentImportMimeTypes
            }
            importFiles.launch(mimeTypes.toTypedArray())
        }

        val mergeImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                navigator.push(
                    ImageComicScreen(uris.map(android.net.Uri::toString), sourceId, state.selectedBookshelfId),
                )
            }
        }

        DisposableEffect(lifecycleOwner, screenModel, showLibraryReadProgress) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && (parentUrl != null || showLibraryReadProgress)) {
                    screenModel.refreshReadProgress()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val isRefreshing = state.isRefreshing
        val pullRefreshState = rememberPullRefreshState(
            refreshing = isRefreshing,
            onRefresh = screenModel::refresh,
        )
        val navigateUp: () -> Unit = { navigator.pop() }
        val openSettings = {
            navigator.push(
                LocalFolderSettingsScreen(
                    sourceId = sourceId,
                    profileName = screenModel.source.name,
                    titleOverride = null,
                ),
            )
        }

        if (screenModel.source is StubSource) {
            MissingSourceScreen(
                source = screenModel.source,
                navigateUp = navigateUp,
            )
            return
        }

        val openEntry: (Manga) -> Unit = {
            if (state.isBusy) {
                // Wait until the deletion snapshot or operation is complete.
            } else if (selectedIds.isNotEmpty()) {
                screenModel.toggleSelection(it)
            } else if (localFolderSource?.indexedEntry(it.url)?.let { entry ->
                    entry.kind == LocalLibraryItem.Kind.FOLDER && !entry.imageComic
                } == true
            ) {
                navigator.push(LocalLibraryScreen(sourceId, scope, null, parentUrl = it.url))
            } else {
                val localSource = screenModel.source as? LocalFolderSource
                val imageComic = localSource?.indexedEntry(it.url)?.imageComic == true
                val mode = when {
                    imageComic -> EntryOpenMode.READER
                    localSource?.isIndividualBookEntry(it.url) == true ->
                        entryOpenPreferences.localBookMode()
                    localSource?.isIndividualFileEntry(it.url) == true ->
                        entryOpenPreferences.localMode()
                    else -> EntryOpenMode.DETAILS
                }
                when (mode) {
                    EntryOpenMode.READER -> screenModel.openLibraryEntry(it)
                    EntryOpenMode.PAGE_PREVIEW ->
                        navigator.push(LanraragiArchivePreviewScreen(it.id, it.source))
                    EntryOpenMode.DETAILS -> navigator.push(
                        MangaScreen(
                            mangaId = it.id,
                            fromSource = true,
                            sourceId = it.source,
                            mangaUrl = it.url,
                        ),
                    )
                }
            }
        }
        var showFolderSearch by remember { mutableStateOf(false) }
        var folderSearchQuery by remember { mutableStateOf(state.submittedQuery) }
        if (showFolderSearch) {
            AlertDialog(
                onDismissRequest = { showFolderSearch = false },
                title = { Text(stringResource(MR.strings.action_search)) },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        value = folderSearchQuery,
                        onValueChange = { folderSearchQuery = it },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        screenModel.search(folderSearchQuery)
                        showFolderSearch = false
                    }) {
                        Text(stringResource(MR.strings.action_search))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        screenModel.search("")
                        showFolderSearch = false
                    }) {
                        Text(stringResource(MR.strings.action_reset))
                    }
                },
            )
        }
        if (parentUrl != null && localFolderSource != null) {
            val folder = parentManga
            val showChapterProgress by libraryPreferences.showChapterReadProgress.collectAsState()
            val showFileSize by libraryPreferences.showChapterFileSize.collectAsState()
            if (folder == null) {
                LoadingScreen()
            } else {
                LocalFolderDetailContent(
                    folder = folder, mangaList = mangaList, source = localFolderSource,
                    selectedIds = selectedIds, displayMode = seriesDisplayMode,
                    columns = columns.takeIf { it >= 0 } ?: libraryPreferences.chapterCoverGridColumns.get(),
                    readProgress = readProgressByUrl,
                    showReadProgress = showChapterProgress,
                    showFileSize = showFileSize,
                    refreshing = isRefreshing || mangaList.loadState.refresh is LoadState.Loading,
                    hasFilters = state.filters.isActive || state.submittedQuery.isNotBlank(),
                    error = (state.refreshError ?: (mangaList.loadState.refresh as? LoadState.Error)?.error)
                        ?.takeUnless { it is NoResultsException }
                        ?.let { with(context) { it.formattedMessage } },
                    snackbarHostState = snackbarHostState, navigateUp = navigateUp,
                    onDisplayModeChange = libraryPreferences.chapterCoverDisplayMode::set,
                    onFilter = screenModel::openFilterDialog,
                    onRefresh = screenModel::refresh,
                    onImport = openImportPicker,
                    onEditSeriesDetails = { navigator.push(SeriesMetadataEditScreen(folder.id)) },
                    onEditNotes = { navigator.push(eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen(folder)) },
                    onCoverClick = { screenModel.openEntryActions(folder) }, onSearch = screenModel::search,
                    onSearchClick = {
                        folderSearchQuery = state.submittedQuery
                        showFolderSearch = true
                    },
                    onReadAsComic = if (canMergeCurrentFolderImageSeries) ::convertCurrentFolderToComic else null,
                    onRecoverOperation = if (localFolderSource.hasPendingFolderOperation()) {
                        { operationRecovery = true }
                    } else {
                        null
                    },
                    onContinueReading = screenModel::openLibraryEntry,
                    onEntryClick = openEntry, onEntryLongClick = screenModel::openEntryActions,
                    onSelectAll = {
                        screenModel.selectAll(
                            mangaList.itemSnapshotList.items.map { it.value },
                        )
                    },
                    onInvertSelection = {
                        mangaList.itemSnapshotList.items.forEach { screenModel.toggleSelection(it.value) }
                    },
                    onClearSelection = screenModel::clearSelection,
                    onMarkRead = { screenModel.markRead(state.selectedMangas, read = it) },
                    onDelete = { screenModel.requestDeletion(state.selectedMangas) },
                )
            }
        } else {
            Scaffold(
                topBar = {
                    Column(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .pointerInput(Unit) {},
                    ) {
                        if (selectedIds.isNotEmpty()) {
                            AppBar(
                                title = null,
                                actionModeCounter = selectedIds.size,
                                onCancelActionMode = screenModel::clearSelection,
                                actionModeActions = {
                                    AppBarActions(
                                        actions = persistentListOf(
                                            AppBar.Action(
                                                title = stringResource(MR.strings.action_select_all),
                                                icon = Icons.Outlined.SelectAll,
                                                onClick = {
                                                    screenModel.selectAll(
                                                        mangaList.itemSnapshotList.items.map { it.value },
                                                    )
                                                },
                                            ),
                                            AppBar.OverflowAction(
                                                title = stringResource(MR.strings.local_library_move_to_bookshelf),
                                                onClick = {
                                                    screenModel.openMoveToBookshelfDialog(state.selectedMangas)
                                                },
                                            ),
                                            AppBar.OverflowAction(
                                                title = stringResource(MR.strings.action_mark_as_read),
                                                onClick = { screenModel.markRead(state.selectedMangas, read = true) },
                                            ),
                                            AppBar.OverflowAction(
                                                title = stringResource(MR.strings.action_mark_as_unread),
                                                onClick = { screenModel.markRead(state.selectedMangas, read = false) },
                                            ),
                                            AppBar.Action(
                                                title = stringResource(MR.strings.local_library_delete_files),
                                                icon = Icons.Outlined.Delete,
                                                onClick = { screenModel.requestDeletion(state.selectedMangas) },
                                            ),
                                        ),
                                    )
                                },
                            )
                        } else {
                            LocalLibraryToolbar(
                                searchQuery = state.toolbarQuery,
                                onSearchQueryChange = screenModel::setToolbarQuery,
                                displayMode = displayMode,
                                onDisplayModeChange = {
                                    if (parentUrl == null) {
                                        screenModel.displayMode = it
                                    } else {
                                        val mode = when (it) {
                                            LibraryDisplayMode.List -> Manga.CHAPTER_COVER_DISPLAY_TEXT
                                            LibraryDisplayMode.CoverOnlyGrid -> Manga.CHAPTER_COVER_DISPLAY_COVER
                                            LibraryDisplayMode.ComfortableGrid ->
                                                Manga.CHAPTER_COVER_DISPLAY_COMFORTABLE
                                            else -> Manga.CHAPTER_COVER_DISPLAY_COVER_AND_TITLE
                                        }
                                        libraryPreferences.chapterCoverDisplayMode.set(mode)
                                    }
                                },
                                connectionProfiles = connectionProfiles,
                                activeConnectionId = sourceId,
                                onConnectionSelect = connectionPreferences.activeConnectionId::set,
                                hasActiveFilters = state.filters.isActive,
                                onImportClick = openImportPicker,
                                onMergeImagesClick = if (parentUrl == null) {
                                    { mergeImages.launch(LocalMediaFormats.images.mimeTypes.toTypedArray()) }
                                } else {
                                    null
                                },
                                folderTitle = parentUrl?.let { parentManga?.title.orEmpty() },
                                onReadAsComicClick = if (canMergeCurrentFolderImageSeries) {
                                    { convertCurrentFolderToComic() }
                                } else {
                                    null
                                },
                                onRecoverOperation = if (localFolderSource?.hasPendingFolderOperation() == true) {
                                    { operationRecovery = true }
                                } else {
                                    null
                                },
                                onFilterClick = screenModel::openFilterDialog,
                                onSettingsClick = openSettings,
                                onSearch = screenModel::search,
                                onClickCloseSearch = screenModel::exitSearch,
                                navigateUp = navigateUp.takeIf { showNavigationUp || parentUrl != null },
                            )
                        }

                        if (state.submittedQuery.isNotBlank()) {
                            ConnectionSearchResults(
                                options = listOf(
                                    MR.strings.title,
                                    MR.strings.local_library_sort_added,
                                    MR.strings.local_library_sort_modified,
                                ).mapIndexed { index, label ->
                                    ConnectionSearchSortOption(
                                        value = index,
                                        label = stringResource(label),
                                        defaultAscending = index == 0,
                                    )
                                },
                                selected = state.filters.sort,
                                ascending = !state.filters.descending,
                                onSelect = screenModel::selectSearchSort,
                            )
                        } else if (
                            parentUrl == null && state.toolbarQuery == null && state.bookshelves.isNotEmpty()
                        ) {
                            Row(
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = MaterialTheme.padding.small),
                                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                            ) {
                                FilterChip(
                                    selected = state.selectedBookshelfId == null,
                                    onClick = { screenModel.selectBookshelf(null) },
                                    label = { Text(stringResource(MR.strings.all)) },
                                )
                                state.bookshelves.forEach { bookshelf ->
                                    FilterChip(
                                        selected = state.selectedBookshelfId == bookshelf.id,
                                        onClick = { screenModel.selectBookshelf(bookshelf.id) },
                                        label = { Text(bookshelf.name) },
                                    )
                                }
                            }
                        }

                        HorizontalDivider()
                        if (state.isPreparingDeletion || state.isUpdatingItems) EInkLinearProgressIndicator()
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pullRefresh(pullRefreshState),
                ) {
                    val refreshError = state.refreshError
                        ?: (mangaList.loadState.refresh as? LoadState.Error)?.error
                    when {
                        mangaList.itemCount == 0 &&
                            (state.isRefreshing || mangaList.loadState.refresh is LoadState.Loading) -> {
                            LoadingScreen(Modifier.padding(paddingValues))
                        }
                        mangaList.itemCount == 0 && state.submittedQuery.isBlank() && !state.filters.isActive &&
                            (refreshError == null || refreshError is NoResultsException) -> {
                            EmptyScreen(
                                stringRes = MR.strings.local_library_empty_scan_cache,
                                modifier = Modifier.padding(paddingValues),
                                actions = localLibraryEmptyActions(
                                    onRefresh = screenModel::refresh,
                                    onManageDirectories = openSettings,
                                ),
                            )
                        }
                        mangaList.itemCount == 0 && refreshError is NoResultsException -> {
                            EmptyScreen(
                                stringRes = MR.strings.no_results_found,
                                modifier = Modifier.padding(paddingValues),
                                actions = persistentListOf(
                                    EmptyScreenAction(
                                        stringRes = MR.strings.action_retry,
                                        icon = Icons.Outlined.Refresh,
                                        onClick = mangaList::retry,
                                    ),
                                ),
                            )
                        }
                        mangaList.itemCount == 0 && refreshError != null -> {
                            EmptyScreen(
                                message = with(context) { refreshError.formattedMessage },
                                modifier = Modifier.padding(paddingValues),
                                actions = localLibraryEmptyActions(
                                    onRefresh = screenModel::refresh,
                                    onManageDirectories = openSettings,
                                ),
                            )
                        }
                        else -> {
                            BrowseSourceContent(
                                source = screenModel.source,
                                mangaList = mangaList,
                                columns = libraryGridCellsForColumns(columns),
                                displayMode = displayMode,
                                snackbarHostState = snackbarHostState,
                                contentPadding = paddingValues,
                                showLibraryBadges = false,
                                selectedMangaIds = selectedIds,
                                readProgress = if (showLibraryReadProgress) {
                                    { manga ->
                                        readProgressByUrl[manga.url.trimEnd('/')]
                                    }
                                } else {
                                    null
                                },
                                showPagingLoadingIndicator = false,
                                onWebViewClick = {},
                                onHelpClick = {},
                                onMangaClick = openEntry,
                                onMangaLongClick = screenModel::openEntryActions,
                            )
                        }
                    }

                    PullRefreshIndicator(
                        refreshing = isRefreshing,
                        state = pullRefreshState,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = paddingValues.calculateTopPadding()),
                    )
                }
            }
        }

        if (operationRecovery && localFolderSource != null) {
            AlertDialog(
                onDismissRequest = { operationRecovery = false },
                title = { Text(stringResource(MR.strings.local_library_pending_operation)) },
                text = { Text(stringResource(MR.strings.local_library_pending_operation_detail)) },
                confirmButton = {
                    TextButton(onClick = {
                        operationRecovery = false
                        coroutineScope.launch {
                            if (localFolderSource.resumeFolderOperation().isFailure) {
                                snackbarHostState.showSnackbar(
                                    context.stringResource(MR.strings.local_library_file_operation_failed),
                                )
                            }
                        }
                    }) { Text(stringResource(MR.strings.action_retry)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        operationRecovery = false
                        coroutineScope.launch {
                            if (localFolderSource.acceptCurrentFolderState().isFailure) {
                                snackbarHostState.showSnackbar(
                                    context.stringResource(MR.strings.local_library_file_operation_failed),
                                )
                            }
                        }
                    }) { Text(stringResource(MR.strings.local_library_accept_current_state)) }
                },
            )
        }
        folderOperation?.let { (operation, url) ->
            localFolderSource?.let { local ->
                LocalFolderOperationDialog(
                    local,
                    operation,
                    url,
                    parentUrl,
                    onDismiss = {
                        folderOperation = null
                    },
                )
            }
        }
        when (state.dialog) {
            LocalLibraryScreenModel.Dialog.Filter -> {
                LocalLibraryFilterDialog(
                    filters = state.filters,
                    rememberFilters = state.rememberFilters,
                    onDismissRequest = screenModel::dismissDialog,
                    onApply = screenModel::applyFilters,
                )
            }
            is LocalLibraryScreenModel.Dialog.MoveToBookshelf -> {
                val dialog = state.dialog as LocalLibraryScreenModel.Dialog.MoveToBookshelf
                ConnectionLibraryShelfDialog(
                    title = stringResource(MR.strings.local_library_move_to_bookshelf),
                    shelves = dialog.bookshelves,
                    currentShelfId = dialog.currentBookshelfId,
                    onDismissRequest = screenModel::dismissDialog,
                    onConfirm = { screenModel.moveToBookshelf(dialog.mangas, it) },
                )
            }
            is LocalLibraryScreenModel.Dialog.EntryActions -> {
                val dialog = state.dialog as LocalLibraryScreenModel.Dialog.EntryActions
                AlertDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = screenModel::dismissDialog) {
                            Text(text = stringResource(MR.strings.action_cancel))
                        }
                    },
                    title = { Text(text = dialog.manga.title) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            val entry = localFolderSource?.indexedEntry(dialog.manga.url)
                            if (entry != null && localFolderSource.folderRoots().any { it.id == entry.rootId }) {
                                val canMove by produceState(false, dialog.manga.url) {
                                    value = localFolderSource.canMoveEntry(dialog.manga.url)
                                }
                                val canReadAsComic by produceState(false, dialog.manga.url) {
                                    value = entry.format == "directory" &&
                                        localFolderSource.canMergeImageSeries(dialog.manga.url)
                                }
                                Text(entry.relativePath, style = MaterialTheme.typography.bodySmall)
                                TextButton(
                                    onClick = {
                                        screenModel.dismissDialog()
                                        navigator.push(
                                            LocalLibraryScreen(
                                                sourceId,
                                                scope,
                                                null,
                                                parentUrl =
                                                localFolderSource.containingFolder(
                                                    dialog.manga.url,
                                                )?.let(localFolderSource::entryUrl),
                                            ),
                                        )
                                    },
                                ) {
                                    Text(stringResource(MR.strings.local_library_open_parent))
                                }
                                TextButton(
                                    onClick = {
                                        screenModel.dismissDialog()
                                        folderOperation = LocalFolderOperation.RENAME to dialog.manga.url
                                    },
                                ) {
                                    Text(stringResource(MR.strings.local_library_rename))
                                }
                                TextButton(
                                    enabled = canMove,
                                    onClick = {
                                        screenModel.dismissDialog()
                                        folderOperation = LocalFolderOperation.MOVE to dialog.manga.url
                                    },
                                ) {
                                    Text(stringResource(MR.strings.local_library_move_file))
                                }
                                if (entry.kind == LocalLibraryItem.Kind.FOLDER &&
                                    entry.format == "directory" && canReadAsComic
                                ) {
                                    val imageSeriesEnabled = entry.imageComicOverride != false
                                    TextButton(
                                        onClick = {
                                            screenModel.dismissDialog()
                                            coroutineScope.launch {
                                                if (localFolderSource.setImageComic(
                                                        dialog.manga.url,
                                                        !imageSeriesEnabled,
                                                    ).isFailure
                                                ) {
                                                    snackbarHostState.showSnackbar(
                                                        context.stringResource(
                                                            MR.strings.local_library_image_mode_failed,
                                                        ),
                                                    )
                                                }
                                            }
                                        },
                                    ) {
                                        val modeLabel = if (imageSeriesEnabled) {
                                            MR.strings.local_library_show_as_folder
                                        } else {
                                            MR.strings.local_library_show_as_comic
                                        }
                                        Text(stringResource(modeLabel))
                                    }
                                }
                            }
                            if (localFolderSource?.isMetadataEditable(dialog.manga.url) == true) {
                                TextButton(
                                    onClick = {
                                        screenModel.dismissDialog()
                                        navigator.push(SeriesMetadataEditScreen(dialog.manga.id))
                                    },
                                ) {
                                    Text(text = stringResource(MR.strings.action_edit_series_details))
                                }
                            }
                            TextButton(onClick = { screenModel.useFirstItemAsCover(dialog.manga) }) {
                                Text(text = stringResource(MR.strings.local_library_use_first_page_as_cover))
                            }
                            if (localFolderSource?.isLibraryShelfAssignable(dialog.manga.url) == true) {
                                TextButton(
                                    onClick = {
                                        screenModel.dismissDialog()
                                        screenModel.openMoveToBookshelfDialog(listOf(dialog.manga))
                                    },
                                ) {
                                    Text(text = stringResource(MR.strings.local_library_move_to_bookshelf))
                                }
                            }
                            TextButton(onClick = { screenModel.markRead(listOf(dialog.manga), read = true) }) {
                                Text(text = stringResource(MR.strings.action_mark_as_read))
                            }
                            TextButton(onClick = { screenModel.markRead(listOf(dialog.manga), read = false) }) {
                                Text(text = stringResource(MR.strings.action_mark_as_unread))
                            }
                            TextButton(onClick = { screenModel.toggleSelection(dialog.manga) }) {
                                Text(text = stringResource(MR.strings.local_library_select_items))
                            }
                            TextButton(onClick = { screenModel.requestDeletion(listOf(dialog.manga)) }) {
                                Text(
                                    text = stringResource(MR.strings.local_library_delete_files),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                )
            }
            is LocalLibraryScreenModel.Dialog.DeleteFiles -> {
                val dialog = state.dialog as LocalLibraryScreenModel.Dialog.DeleteFiles
                AlertDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    title = { Text(stringResource(MR.strings.local_library_delete_files)) },
                    text = {
                        Column {
                            Text(
                                stringResource(
                                    MR.strings.local_library_delete_files_confirm,
                                    dialog.plan.entries.size,
                                    dialog.plan.entries.sumOf { it.deletion.fileCount },
                                ),
                            )
                            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                                dialog.plan.entries.forEach { entry ->
                                    Text("• ${entry.manga.title}")
                                    Text(entry.item.relativePath, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (state.isDeleting) EInkLinearProgressIndicator()
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = screenModel::confirmDeletion, enabled = !state.isDeleting) {
                            Text(stringResource(MR.strings.action_delete), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = screenModel::dismissDialog, enabled = !state.isDeleting) {
                            Text(stringResource(MR.strings.action_cancel))
                        }
                    },
                )
            }
            null -> Unit
        }

        LaunchedEffect(Unit) {
            events().query.receiveAsFlow().collectLatest(screenModel::search)
        }
        LaunchedEffect(Unit) {
            events().refresh.receiveAsFlow().collectLatest { screenModel.refresh() }
        }
        LaunchedEffect(state.refreshError) {
            state.refreshError?.let { error ->
                snackbarHostState.showSnackbar(with(context) { error.formattedMessage })
            }
        }
        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    is LocalLibraryScreenModel.Event.OpenChapter -> {
                        epubReaderLauncher.launch(
                            coroutineScope,
                            context,
                            event.chapter.mangaId,
                            event.chapter.id,
                        )
                    }
                    is LocalLibraryScreenModel.Event.OpenFailed -> {
                        snackbarHostState.showSnackbar(with(context) { event.error.formattedMessage })
                    }
                    LocalLibraryScreenModel.Event.CoverUpdated -> {
                        snackbarHostState.showSnackbar(coverUpdatedMessage)
                    }
                    is LocalLibraryScreenModel.Event.CoverFailed -> {
                        snackbarHostState.showSnackbar(with(context) { event.error.formattedMessage })
                    }
                    LocalLibraryScreenModel.Event.ItemActionFailed -> {
                        snackbarHostState.showSnackbar(
                            context.stringResource(MR.strings.local_library_item_action_failed),
                        )
                    }
                    LocalLibraryScreenModel.Event.NoCompatibleShelf -> {
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.local_library_no_common_shelf))
                    }
                    is LocalLibraryScreenModel.Event.ItemsUpdated -> {
                        snackbarHostState.showSnackbar(
                            if (event.failed == 0) {
                                context.stringResource(MR.strings.local_library_items_updated, event.updated)
                            } else {
                                context.stringResource(
                                    MR.strings.local_library_items_update_partial,
                                    event.updated,
                                    event.failed,
                                )
                            },
                        )
                    }
                    LocalLibraryScreenModel.Event.DeleteFailed -> {
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.local_library_delete_failed))
                    }
                    is LocalLibraryScreenModel.Event.FilesDeleted -> {
                        snackbarHostState.showSnackbar(
                            if (event.failed == 0) {
                                context.stringResource(MR.strings.local_library_delete_success, event.deleted)
                            } else {
                                context.stringResource(
                                    MR.strings.local_library_delete_result,
                                    event.deleted,
                                    event.failed,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    override suspend fun search(query: String) {
        events().query.send(query)
    }

    override suspend fun searchGenre(name: String) {
        events().query.send(name)
    }

    override suspend fun refresh() {
        events().refresh.send(Unit)
    }

    private class RuntimeEvents {
        val query = Channel<String>(capacity = Channel.CONFLATED)
        val refresh = Channel<Unit>(capacity = Channel.CONFLATED)
    }
}

private fun localLibraryEmptyActions(
    onRefresh: () -> Unit,
    onManageDirectories: () -> Unit,
) = persistentListOf(
    EmptyScreenAction(
        stringRes = MR.strings.action_webview_refresh,
        icon = Icons.Outlined.Refresh,
        onClick = onRefresh,
    ),
    EmptyScreenAction(
        stringRes = MR.strings.local_library_manage_bookshelves,
        icon = Icons.Outlined.Settings,
        onClick = onManageDirectories,
    ),
)

private fun libraryGridCellsForColumns(columns: Int): GridCells {
    val coercedColumns = columns.coerceIn(0, 10)
    return if (coercedColumns == 0) {
        GridCells.Adaptive(128.dp)
    } else {
        GridCells.Fixed(coercedColumns)
    }
}
