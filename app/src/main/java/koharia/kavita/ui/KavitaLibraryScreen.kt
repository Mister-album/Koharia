package koharia.kavita.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import koharia.connection.ConnectionBrowseScreen
import koharia.connection.ConnectionPreferences
import koharia.connection.ui.ConnectionLibraryTabs
import koharia.connection.ui.ConnectionLibraryToolbar
import koharia.connection.ui.ConnectionSearchResults
import koharia.connection.ui.ConnectionSearchSortOption
import koharia.connection.ui.LibraryConnectionProfilesScreen
import koharia.source.kavita.KavitaSettingsScreen
import koharia.source.kavita.KavitaSource
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.jvm.Transient

class KavitaLibraryScreen(
    override val sourceId: Long,
    private val initialQuery: String?,
    private val showNavigationUp: Boolean,
    private val filterJson: String? = null,
    private val selection: KavitaMemberSelection? = null,
    private val contentScope: koharia.connection.LibraryContentScope = koharia.connection.LibraryContentScope.ALL,
) : Screen(), ConnectionBrowseScreen {
    override val refreshOnReselect = false

    @Transient private var runtimeModel: KavitaLibraryScreenModel? = null
    override suspend fun search(query: String) {
        runtimeModel?.search(query)
    }
    override suspend fun searchGenre(name: String) {
        runtimeModel?.searchGenre(name)
    }
    override suspend fun refresh() {
        runtimeModel?.refresh()
    }

    @OptIn(androidx.compose.material.ExperimentalMaterialApi::class)
    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? KavitaSource
        if (source == null || !source.hasValidConnection()) {
            EmptyScreen(stringRes = MR.strings.connection_unavailable)
            return
        }
        val epoch by source.epoch.collectAsState()
        val model = rememberScreenModel(tag = "${source.instanceKey}:$epoch") {
            KavitaLibraryScreenModel(source, initialQuery, filterJson, contentScope)
        }
        runtimeModel = model
        val state by model.state.collectAsState()
        val pages = model.pages.collectAsLazyPagingItems()
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
        val orientation = LocalConfiguration.current.orientation
        val columnPreference = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            libraryPreferences.landscapeColumns
        } else {
            libraryPreferences.portraitColumns
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
                    if (selection != null) {
                        withContext(Dispatchers.IO) {
                            val session = source.session()
                            val id = session.identity.seriesId(manga.url)
                            if (selection.kind == "Collection") {
                                session.organization.addCollection(id, selection.id)
                            } else {
                                session.organization.addListMember(selection.id, id)
                            }
                        }
                        navigator.pop()
                    } else {
                        val local = withContext(Dispatchers.IO) { source.materialize(manga) }
                        navigator.push(MangaScreen(local.id))
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    snackbar.showSnackbar(context.kavitaError(error))
                } finally {
                    opening = false
                }
            }
        }
        BackHandler(state.toolbarQuery != null) { model.exitSearch() }
        val pageError = (pages.loadState.refresh as? LoadState.Error)?.error
            ?: (pages.loadState.append as? LoadState.Error)?.error
        val refreshError = state.error?.takeUnless { state.downloadedOnly }
        val shelfError = refreshError ?: pageError
        val retryLabel = stringResource(MR.strings.action_retry)
        LaunchedEffect(refreshError, pages.itemCount) {
            if (refreshError != null && pages.itemCount > 0) {
                if (snackbar.showSnackbar(context.kavitaError(refreshError), retryLabel) ==
                    SnackbarResult.ActionPerformed
                ) {
                    model.refresh()
                }
            }
        }
        val pull = rememberPullRefreshState(state.refreshing, model::refresh)
        Scaffold(
            topBar = {
                Column {
                    ConnectionLibraryToolbar(
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = model::setToolbarQuery,
                        onSearch = model::search,
                        onCloseSearch = model::exitSearch,
                        displayMode = state.displayMode,
                        onDisplayModeChange = model::setDisplayMode,
                        connectionProfiles = profiles,
                        activeConnectionId = sourceId,
                        onConnectionSelect = { connections.activeConnectionId.set(it) },
                        onManageConnections = { navigator.push(LibraryConnectionProfilesScreen()) },
                        onFilterClick = { filters = true },
                        hasActiveFilters =
                        state.appliedFilter?.statements?.isNotEmpty() == true || state.downloadedOnly,
                        showConnectionAction = profiles.size > 1,
                        searchActions = {
                            koharia.connection.ui.ConnectionSearchScope(
                                choices =
                                listOf(
                                    koharia.connection.ui.ConnectionSearchChoice(0L, stringResource(MR.strings.all)),
                                ) +
                                    state.media.map { koharia.connection.ui.ConnectionSearchChoice(it.id, it.name) },
                                selected = state.selectedMedia,
                                onSelect = model::selectMedia,
                            )
                        },
                        onRefresh = model::refresh,
                        onSettings = { navigator.push(KavitaSettingsScreen(sourceId)) },
                        additionalActions = listOf(
                            eu.kanade.presentation.components.AppBar.OverflowAction(
                                stringResource(MR.strings.kavita_downloaded),
                            ) { model.filter(state.order, !state.downloadedOnly) },
                            eu.kanade.presentation.components.AppBar.OverflowAction(
                                stringResource(MR.strings.kavita_explore),
                            ) { navigator.push(KavitaExploreScreen(sourceId)) },
                        ),
                        navigateUp = if (showNavigationUp) ({ navigator.pop() }) else null,
                    )
                    if (state.query.isNotBlank()) {
                        ConnectionSearchResults(
                            options = if (state.downloadedOnly) {
                                emptyList()
                            } else {
                                listOf(
                                    "1" to MR.strings.kavita_sort_name,
                                    "3" to MR.strings.kavita_sort_updated,
                                    "2" to MR.strings.kavita_sort_created,
                                ).map { (field, label) ->
                                    ConnectionSearchSortOption(
                                        value = field,
                                        label = stringResource(label),
                                        defaultAscending = field == "1",
                                    )
                                }
                            },
                            selected = state.order.substringBefore(' '),
                            ascending = !state.order.endsWith("desc"),
                            onSelect = model::selectSearchSort,
                        )
                    }
                    if (state.downloadedOnly) {
                        Text(
                            stringResource(MR.strings.kavita_offline_scope),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    if (state.toolbarQuery == null) {
                        ConnectionLibraryTabs(
                            entries = state.media,
                            key = { it.id },
                            label = { it.name },
                            isSelected = { it.id == state.selectedMedia },
                            onSelect = { model.selectMedia(it.id) },
                            allSelected = state.selectedMedia == 0L,
                            onSelectAll = { model.selectMedia(0) },
                        )
                    }
                    HorizontalDivider()
                    if (selection !=
                        null
                    ) {
                        Text(stringResource(MR.strings.kavita_select_series_to_add), Modifier.padding(16.dp))
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).pullRefresh(pull)) {
                when {
                    shelfError != null && pages.itemCount == 0 -> {
                        KavitaShelfError(
                            error = shelfError,
                            onRetry = { if (refreshError != null) model.refresh() else pages.retry() },
                            onSettings = { navigator.push(KavitaSettingsScreen(sourceId)) },
                        )
                    }
                    !state.mediaLoaded && !state.downloadedOnly -> LoadingScreen()
                    else -> {
                        BrowseSourceContent(
                            modifier = Modifier.fillMaxSize(),
                            source = source,
                            mangaList = pages,
                            columns = if (columns > 0) {
                                GridCells.Fixed(columns)
                            } else {
                                GridCells.Adaptive(120.dp)
                            },
                            displayMode = state.displayMode,
                            snackbarHostState = snackbar,
                            contentPadding = PaddingValues(0.dp),
                            showLibraryBadges = false,
                            onWebViewClick = { navigator.push(KavitaSettingsScreen(sourceId)) },
                            onHelpClick = { navigator.push(KavitaSettingsScreen(sourceId)) },
                            onMangaClick = ::open,
                            onMangaLongClick = ::open,
                            onRefresh = model::refresh,
                        )
                    }
                }
                PullRefreshIndicator(state.refreshing, pull, Modifier.align(Alignment.TopCenter))
            }
        }
        if (filters) {
            KavitaFilterDialog(
                source = source,
                initial = model.editableFilter(),
                libraries = state.media,
                cachedOnly = state.downloadedOnly,
                onDismissRequest = { filters = false },
                onApply = model::applyFilter,
            )
        }
    }
}

@Composable
internal fun KavitaShelfError(error: Throwable, onRetry: () -> Unit, onSettings: () -> Unit) {
    EmptyScreen(
        message = LocalContext.current.kavitaError(error),
        actions = persistentListOf(
            EmptyScreenAction(MR.strings.action_retry, Icons.Outlined.Refresh, onRetry),
            EmptyScreenAction(MR.strings.pref_connection_settings, Icons.Outlined.Settings, onSettings),
        ),
    )
}
