package koharia.source.local

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import koharia.connection.ConnectionBrowseScreen
import koharia.connection.ConnectionPreferences
import koharia.connection.LibraryContentScope
import koharia.connection.ui.ConnectionLibraryShelfDialog
import koharia.connection.ui.SeriesMetadataEditScreen
import koharia.domain.epub.interactor.GetEpubProgress
import koharia.epub.EpubReaderLauncher
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import tachiyomi.data.source.NoResultsException
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
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
        val screenModel = rememberScreenModel(tag = "$sourceId:$scope:$initialQuery") {
            LocalLibraryScreenModel(
                sourceId = sourceId,
                scope = scope,
                initialQuery = initialQuery,
                sourceManager = sourceManager,
                sourcePreferences = sourcePreferences,
                mangaRepository = mangaRepository,
                getChaptersByMangaId = Injekt.get(),
                updateChapter = Injekt.get(),
                getEpubProgress = getEpubProgress,
                libraryPreferences = libraryPreferences,
                entryOpenManager = LocalLibraryEntryOpenManager(
                    syncChaptersWithSource = syncChaptersWithSource,
                    chapterRepository = chapterRepository,
                    epubReaderLauncher = epubReaderLauncher,
                ),
                updateManga = updateManga,
                coverCache = coverCache,
            )
        }
        val state by screenModel.state.collectAsState()
        val coverUpdatedMessage = stringResource(MR.strings.cover_updated)
        val showLibraryReadProgress by libraryPreferences.showLibraryReadProgress.collectAsState()
        val readProgressByUrl by screenModel.readProgressByUrl.collectAsState()
        val columns by libraryPreferences.portraitColumns.collectAsState()
        val connectionProfiles by connectionPreferences.profilesChanges()
            .collectAsState(initial = connectionPreferences.getProfiles())
        val mangaList = screenModel.mangaPagerFlow.collectAsLazyPagingItems()
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val snackbarHostState = remember { SnackbarHostState() }

        DisposableEffect(lifecycleOwner, screenModel, showLibraryReadProgress) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && showLibraryReadProgress) {
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

        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {},
                ) {
                    LocalLibraryToolbar(
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = screenModel::setToolbarQuery,
                        displayMode = screenModel.displayMode,
                        onDisplayModeChange = { screenModel.displayMode = it },
                        connectionProfiles = connectionProfiles,
                        activeConnectionId = sourceId,
                        onConnectionSelect = connectionPreferences.activeConnectionId::set,
                        hasActiveFilters = state.filters.isActive,
                        onFilterClick = screenModel::openFilterDialog,
                        onRefreshClick = screenModel::refresh,
                        onSettingsClick = openSettings,
                        onSearch = screenModel::search,
                        onClickCloseSearch = screenModel::exitSearch,
                        navigateUp = navigateUp.takeIf { showNavigationUp },
                    )

                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = MaterialTheme.padding.small),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        if (state.submittedQuery.isNotBlank()) {
                            Surface(
                                modifier = Modifier.padding(vertical = 8.dp),
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Text(
                                    text = stringResource(MR.strings.search_results),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        } else if (state.bookshelves.isNotEmpty()) {
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
                    mangaList.itemCount == 0 && state.isRefreshing -> {
                        LoadingScreen(Modifier.padding(paddingValues))
                    }
                    mangaList.itemCount == 0 && state.submittedQuery.isBlank() && !state.filters.isActive &&
                        (refreshError == null || refreshError is NoResultsException) -> {
                        EmptyScreen(
                            stringRes = MR.strings.local_library_empty_import_hint,
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
                            displayMode = screenModel.displayMode,
                            snackbarHostState = snackbarHostState,
                            contentPadding = paddingValues,
                            showLibraryBadges = false,
                            readProgress = if (showLibraryReadProgress) {
                                { manga -> readProgressByUrl[manga.url.trimEnd('/')] }
                            } else {
                                null
                            },
                            showPagingLoadingIndicator = false,
                            onWebViewClick = {},
                            onHelpClick = {},
                            onMangaClick = {
                                if (!screenModel.openLibraryEntry(it)) {
                                    navigator.push(
                                        MangaScreen(
                                            mangaId = it.id,
                                            fromSource = true,
                                            sourceId = it.source,
                                            mangaUrl = it.url,
                                        ),
                                    )
                                }
                            },
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

        when (state.dialog) {
            LocalLibraryScreenModel.Dialog.Filter -> {
                LocalLibraryFilterDialog(
                    filters = state.filters,
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
                    onConfirm = { screenModel.moveToBookshelf(dialog.manga, it) },
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
                        Column {
                            TextButton(
                                onClick = {
                                    screenModel.dismissDialog()
                                    navigator.push(SeriesMetadataEditScreen(dialog.manga))
                                },
                            ) {
                                Text(text = stringResource(MR.strings.local_library_edit_item_details))
                            }
                            TextButton(onClick = { screenModel.useFirstItemAsCover(dialog.manga) }) {
                                Text(text = stringResource(MR.strings.local_library_use_first_page_as_cover))
                            }
                            TextButton(
                                onClick = {
                                    screenModel.dismissDialog()
                                    screenModel.openMoveToBookshelfDialog(dialog.manga)
                                },
                            ) {
                                Text(text = stringResource(MR.strings.local_library_move_to_bookshelf))
                            }
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
        stringRes = MR.strings.local_library_directories,
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
