package koharia.smanga.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import koharia.connection.ConnectionBrowseScreen
import koharia.connection.ConnectionPreferences
import koharia.connection.ui.ConnectionLibraryTabs
import koharia.connection.ui.ConnectionLibraryToolbar
import koharia.connection.ui.LibraryConnectionProfilesScreen
import koharia.source.smanga.SmangaSettingsScreen
import koharia.source.smanga.SmangaSource
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
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
import kotlin.jvm.Transient

class SmangaLibraryScreen(
    override val sourceId: Long,
    private val initialQuery: String?,
    private val showNavigationUp: Boolean,
) : Screen(), ConnectionBrowseScreen {
    override val refreshOnReselect = false

    @Transient private var runtimeModel: SmangaLibraryScreenModel? = null
    override suspend fun search(query: String) {
        runtimeModel?.search(query)
    }
    override suspend fun searchGenre(name: String) = Unit
    override suspend fun refresh() {
        runtimeModel?.refresh()
    }

    @OptIn(androidx.compose.material.ExperimentalMaterialApi::class)
    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? SmangaSource
        if (source == null || !source.hasValidConnection()) {
            EmptyScreen(stringRes = MR.strings.connection_unavailable)
            return
        }
        val epoch by source.epoch.collectAsState()
        val model = rememberScreenModel(tag = "${source.instanceKey}:$epoch") {
            SmangaLibraryScreenModel(source, initialQuery)
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
                    val local = withContext(Dispatchers.IO) { source.materialize(manga) }
                    navigator.push(MangaScreen(local.id))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    snackbar.showSnackbar(context.smangaError(error))
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
                if (snackbar.showSnackbar(context.smangaError(refreshError), retryLabel) ==
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
                        onRefresh = model::refresh,
                        onSettings = { navigator.push(SmangaSettingsScreen(sourceId)) },
                        navigateUp = if (showNavigationUp) ({ navigator.pop() }) else null,
                    )
                    ConnectionLibraryTabs(
                        entries = state.media,
                        key = { it.id },
                        label = { it.name },
                        isSelected = { it.id == state.selectedMedia },
                        onSelect = { model.selectMedia(it.id) },
                        allSelected = state.selectedMedia == 0L,
                        onSelectAll = { model.selectMedia(0) },
                    )
                    HorizontalDivider()
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).pullRefresh(pull)) {
                when {
                    shelfError != null && pages.itemCount == 0 -> {
                        SmangaShelfError(
                            error = shelfError,
                            onRetry = { if (refreshError != null) model.refresh() else pages.retry() },
                            onSettings = { navigator.push(SmangaSettingsScreen(sourceId)) },
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
                            onWebViewClick = { navigator.push(SmangaSettingsScreen(sourceId)) },
                            onHelpClick = { navigator.push(SmangaSettingsScreen(sourceId)) },
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
            SmangaFilterSheet(
                order = state.order,
                downloadedOnly = state.downloadedOnly,
                onDismiss = { filters = false },
                onApply = model::filter,
            )
        }
    }
}

@Composable
internal fun SmangaShelfError(error: Throwable, onRetry: () -> Unit, onSettings: () -> Unit) {
    EmptyScreen(
        message = LocalContext.current.smangaError(error),
        actions = persistentListOf(
            EmptyScreenAction(MR.strings.action_retry, Icons.Outlined.Refresh, onRetry),
            EmptyScreenAction(MR.strings.pref_connection_settings, Icons.Outlined.Settings, onSettings),
        ),
    )
}

@Composable
private fun SmangaFilterSheet(
    order: String,
    downloadedOnly: Boolean,
    onDismiss: () -> Unit,
    onApply: (String, Boolean) -> Unit,
) {
    val fields = listOf("mangaName", "updateTime", "createTime")
    var selected by remember { mutableStateOf(fields.indexOf(order.substringBefore(' ')).coerceAtLeast(0)) }
    var descending by remember { mutableStateOf(order.endsWith("desc")) }
    var downloads by remember { mutableStateOf(downloadedOnly) }
    AdaptiveSheet(onDismissRequest = onDismiss) {
        LazyColumn {
            item {
                SelectItem(
                    stringResource(MR.strings.action_sort),
                    arrayOf(
                        stringResource(MR.strings.smanga_sort_name),
                        stringResource(MR.strings.smanga_sort_updated),
                        stringResource(MR.strings.smanga_sort_created),
                    ),
                    selected,
                ) { selected = it }
                CheckboxItem(stringResource(MR.strings.smanga_descending), descending) { descending = !descending }
                CheckboxItem(stringResource(MR.strings.smanga_downloaded), downloads) { downloads = !downloads }
                TextButton(onClick = {
                    onApply("${fields[selected]} ${if (descending) "desc" else "asc"}", downloads)
                    onDismiss()
                }, modifier = Modifier.padding(16.dp)) { Text(stringResource(MR.strings.action_filter)) }
            }
        }
    }
}
