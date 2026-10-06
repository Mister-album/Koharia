package koharia.connection.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.compose.LazyPagingItems
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionSource
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class ConnectionShelfTab(val id: Long, val name: String)

/**
 * Load states for a shelf built from an already-complete list with `PagingData.from`. Without these,
 * Paging reports `refresh` as Loading for the lifetime of the list, and the shelf keeps a spinner
 * under its last row even though every item is present and no further page can arrive.
 */
val CONNECTION_SHELF_STATIC_LOAD_STATES = LoadStates(
    refresh = LoadState.NotLoading(endOfPaginationReached = true),
    prepend = LoadState.NotLoading(endOfPaginationReached = true),
    append = LoadState.NotLoading(endOfPaginationReached = true),
)

data class ConnectionPagedShelfState(
    val query: String,
    val toolbarQuery: String?,
    val order: String,
    val displayMode: LibraryDisplayMode,
    val downloadedOnly: Boolean,
    val refreshing: Boolean,
    val loaded: Boolean,
    val error: Throwable?,
    val tabs: List<ConnectionShelfTab>,
    val selectedTab: Long,
    val allTab: Long,
    /** Provider shelf conditions offered in the filter sheet; empty when the provider has none. */
    val filterRows: List<ConnectionLibraryFilterRow> = emptyList(),
    /** True when any provider condition, narrowing, or the downloaded-only mode is in effect. */
    val hasActiveFilters: Boolean = false,
    /** Whether the shelf currently keeps its filters and sort for the next visit. */
    val persistentFilters: Boolean = false,
)

/** Shared presentation and details orchestration; providers retain their catalogue and search protocols. */
@OptIn(androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
fun ConnectionPagedShelf(
    source: ConnectionSource,
    state: ConnectionPagedShelfState,
    pages: LazyPagingItems<StateFlow<Manga>>,
    sortOptions: List<ConnectionSearchSortOption<String>>,
    showNavigationUp: Boolean,
    settings: () -> Screen,
    errorMessage: (Throwable) -> String,
    materialize: suspend (Manga) -> Manga,
    onQueryChange: (String?) -> Unit,
    onSearch: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    onSelectTab: (Long) -> Unit,
    onSelectSort: (String, Boolean) -> Unit,
    /**
     * Applied when the sheet is confirmed: the provider conditions, the order, downloaded-only, and
     * whether to remember them. Required so a provider cannot accept the conditions in the sheet and
     * then drop them.
     */
    onFilterStates: (Map<String, ConnectionTriState>, String, Boolean, Boolean) -> Unit,
    /** Provider narrowing rendered below the shared conditions, such as text and genre filters. */
    filterExtras: (@Composable (ConnectionFilterExtrasScope) -> Unit)? = null,
    onRefresh: () -> Unit,
    readingUnitCount: ((Manga) -> Long?)? = null,
    readProgress: ((Manga) -> MangaReadProgress?)? = null,
) {
    val navigator = LocalNavigator.currentOrThrow
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val preferences = remember { Injekt.get<LibraryPreferences>() }
    val columnPreference = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        preferences.landscapeColumns
    } else {
        preferences.portraitColumns
    }
    val columns by columnPreference.collectAsState()
    val connections = remember { Injekt.get<ConnectionPreferences>() }
    val profiles by connections.profilesChanges().collectAsState(initial = connections.getProfiles())
    var filters by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    fun open(manga: Manga) {
        if (opening) return
        opening = true
        scope.launch {
            try {
                val local = withContext(Dispatchers.IO) { materialize(manga) }
                navigator.push(MangaScreen(local.id))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                snackbar.showSnackbar(errorMessage(error))
            } finally {
                opening = false
            }
        }
    }
    BackHandler(state.toolbarQuery != null, onCloseSearch)
    LaunchedEffect(pages.loadState.refresh, pages.loadState.append, pages.itemCount) {
        logcat(LogPriority.DEBUG) {
            "Connection shelf loadState refresh=${pages.loadState.refresh::class.simpleName}" +
                " append=${pages.loadState.append::class.simpleName} items=${pages.itemCount}"
        }
    }
    val pageError = (pages.loadState.refresh as? LoadState.Error)?.error
        ?: (pages.loadState.append as? LoadState.Error)?.error
    val refreshError = state.error?.takeUnless { state.downloadedOnly }
    val error = refreshError ?: pageError
    val retryLabel = stringResource(MR.strings.action_retry)
    LaunchedEffect(refreshError, pages.itemCount) {
        if (refreshError != null && pages.itemCount > 0 &&
            snackbar.showSnackbar(errorMessage(refreshError), retryLabel) == SnackbarResult.ActionPerformed
        ) {
            onRefresh()
        }
    }
    val pull = rememberPullRefreshState(state.refreshing, onRefresh)
    Scaffold(
        topBar = {
            Column {
                ConnectionLibraryToolbar(
                    searchQuery = state.toolbarQuery,
                    onSearchQueryChange = onQueryChange,
                    onSearch = onSearch,
                    onCloseSearch = onCloseSearch,
                    displayMode = state.displayMode,
                    onDisplayModeChange = onDisplayModeChange,
                    connectionProfiles = profiles,
                    activeConnectionId = source.id,
                    onConnectionSelect = { connections.activeConnectionId.set(it) },
                    onManageConnections = { navigator.push(LibraryConnectionProfilesScreen()) },
                    onFilterClick = { filters = true },
                    onRefresh = onRefresh,
                    onSettings = { navigator.push(settings()) },
                    navigateUp = if (showNavigationUp) ({ navigator.pop() }) else null,
                    hasActiveFilters = state.hasActiveFilters || state.downloadedOnly,
                )
                if (state.query.isNotBlank()) {
                    ConnectionSearchResults(
                        options = if (state.downloadedOnly) emptyList() else sortOptions,
                        selected = state.order.substringBefore(' '),
                        ascending = !state.order.endsWith("desc"),
                        onSelect = onSelectSort,
                    )
                }
                if (state.toolbarQuery == null) {
                    ConnectionLibraryTabs(
                        entries = state.tabs,
                        key = { it.id },
                        label = { it.name },
                        isSelected = { it.id == state.selectedTab },
                        onSelect = { onSelectTab(it.id) },
                        allSelected = state.selectedTab == state.allTab,
                        onSelectAll = { onSelectTab(state.allTab) },
                    )
                }
                HorizontalDivider()
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).pullRefresh(pull)) {
            when {
                error != null && pages.itemCount == 0 -> ConnectionShelfError(
                    message = errorMessage(error),
                    onRetry = { if (refreshError != null) onRefresh() else pages.retry() },
                    onSettings = { navigator.push(settings()) },
                )
                !state.loaded && !state.downloadedOnly -> LoadingScreen()
                else -> BrowseSourceContent(
                    modifier = Modifier.fillMaxSize(), source = source, mangaList = pages,
                    columns = if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(120.dp),
                    displayMode = state.displayMode, snackbarHostState = snackbar,
                    contentPadding = PaddingValues(0.dp), showLibraryBadges = false,
                    readingUnitCount = readingUnitCount, readProgress = readProgress,
                    onWebViewClick = { navigator.push(settings()) }, onHelpClick = { navigator.push(settings()) },
                    onMangaClick = ::open, onMangaLongClick = ::open, onRefresh = onRefresh,
                )
            }
            PullRefreshIndicator(state.refreshing, pull, Modifier.align(Alignment.TopCenter))
        }
    }
    if (filters) {
        ConnectionLibraryFilterSheet(
            rows = state.filterRows,
            sortOptions = sortOptions,
            currentOrder = state.order,
            downloadedOnly = state.downloadedOnly,
            persistentFilters = state.persistentFilters,
            onDismissRequest = { filters = false },
            onApply = { states, order, downloaded, persistent ->
                onFilterStates(states, order, downloaded, persistent)
                filters = false
            },
            onReset = { onFilterStates(emptyMap(), state.order, false, state.persistentFilters) },
            extras = filterExtras,
        )
    }
}

@Composable
fun ConnectionShelfError(message: String, onRetry: () -> Unit, onSettings: () -> Unit) {
    EmptyScreen(
        message = message,
        actions = persistentListOf(
            EmptyScreenAction(MR.strings.action_retry, Icons.Outlined.Refresh, onRetry),
            EmptyScreenAction(MR.strings.pref_connection_settings, Icons.Outlined.Settings, onSettings),
        ),
    )
}
