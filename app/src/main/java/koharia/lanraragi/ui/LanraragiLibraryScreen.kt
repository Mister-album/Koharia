package koharia.lanraragi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.paging.compose.collectAsLazyPagingItems
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import koharia.connection.ConnectionBrowseScreen
import koharia.connection.ConnectionPreferences
import koharia.connection.ui.LibraryConnectionProfilesScreen
import koharia.lanraragi.LanraragiEntryDestination
import koharia.lanraragi.LanraragiEntryOpenManager
import koharia.lanraragi.isLanraragiConnectivityFailure
import koharia.lanraragi.lanraragiEntryDestination
import koharia.source.lanraragi.LanraragiSettingsScreen
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.jvm.Transient

class LanraragiLibraryScreen(
    override val sourceId: Long,
    private val initialQuery: String?,
    private val showNavigationUp: Boolean,
) : Screen(), ConnectionBrowseScreen {
    override val refreshOnReselect = false

    @Transient private var runtimeModel: LanraragiLibraryScreenModel? = null
    override suspend fun search(query: String) {
        runtimeModel?.search(query)
    }
    override suspend fun searchGenre(
        name: String,
    ) {
        runtimeModel?.let {
            it.exitSearch()
            it.filter(it.state.value.filter.copy(tag = name, query = ""))
        }
    }
    override suspend fun refresh() {
        runtimeModel?.refresh()
    }

    @OptIn(androidx.compose.material.ExperimentalMaterialApi::class)
    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? LanraragiSource
        if (source == null) {
            EmptyScreen(stringRes = MR.strings.connection_unavailable)
            return
        }
        val model =
            rememberScreenModel(tag = "$sourceId:${source.name}") { LanraragiLibraryScreenModel(source, initialQuery) }
        runtimeModel = model
        val state by model.state.collectAsState()
        val status by source.status.collectAsState()
        val pages = model.pages.collectAsLazyPagingItems()
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val opener = remember { LanraragiEntryOpenManager() }
        var opening by remember { mutableStateOf(false) }
        fun openEntry(manga: Manga, longClick: Boolean) {
            if (opening) return
            opening = true
            scope.launch {
                try {
                    val local = withContext(Dispatchers.IO) { model.materialize(manga) }
                    when (
                        lanraragiEntryDestination(
                            local.url,
                            sourceId,
                            source.preferences.archiveOpenMode,
                            longClick,
                        )
                    ) {
                        LanraragiEntryDestination.READER -> {
                            val chapter = withContext(Dispatchers.IO) { opener.prepareChapter(source, local) }
                            context.startActivity(opener.readerIntent(context, local, chapter))
                        }
                        LanraragiEntryDestination.PAGE_PREVIEW ->
                            navigator.push(LanraragiArchivePreviewScreen(local.id, sourceId))
                        LanraragiEntryDestination.DETAILS -> navigator.push(MangaScreen(local.id))
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    snackbar.showSnackbar(context.lanraragiError(error))
                } finally {
                    opening = false
                }
            }
        }
        var filters by remember { mutableStateOf(false) }
        val connectionPreferences = remember { Injekt.get<ConnectionPreferences>() }
        val connectionProfiles by connectionPreferences.profilesChanges()
            .collectAsState(initial = connectionPreferences.getProfiles())
        BackHandler(enabled = state.toolbarQuery != null) { model.exitSearch() }
        val error = state.error ?: status.error?.takeUnless {
            it.isLanraragiConnectivityFailure() && state.entries.isNotEmpty()
        }
        LaunchedEffect(error) { error?.let { snackbar.showSnackbar(context.lanraragiError(it)) } }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, model) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) model.refresh(automatic = true)
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                model.refresh(automatic = true)
            }
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val refreshing = status.running || state.searching
        val pullState = rememberPullRefreshState(refreshing, { model.refresh() })
        Scaffold(
            topBar = {
                Column {
                    LanraragiLibraryToolbar(
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = model::setToolbarQuery,
                        onSearch = model::search,
                        onCloseSearch = model::exitSearch,
                        displayMode = state.displayMode,
                        onDisplayModeChange = model::setDisplayMode,
                        connectionProfiles = connectionProfiles,
                        activeConnectionId = sourceId,
                        onConnectionSelect = { connectionId ->
                            if (connectionId != sourceId) connectionPreferences.activeConnectionId.set(connectionId)
                        },
                        onManageConnections = { navigator.push(LibraryConnectionProfilesScreen()) },
                        onFilterClick = { filters = true },
                        onRefresh = { model.refresh() },
                        onSettings = { navigator.push(LanraragiSettingsScreen(sourceId)) },
                        navigateUp = if (showNavigationUp) ({ navigator.pop() }) else null,
                    )
                    LanraragiCategoryTabs(
                        categories = state.categories,
                        selectedId = state.filter.category,
                        onSelect = model::selectCategory,
                    )
                    HorizontalDivider()
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).pullRefresh(pullState)) {
                Column(Modifier.fillMaxSize()) {
                    BrowseSourceContent(
                        modifier = Modifier.weight(
                            1f,
                        ),
                        source = source, mangaList = pages, columns = GridCells.Adaptive(120.dp),
                        displayMode = state.displayMode,
                        snackbarHostState = snackbar, contentPadding = PaddingValues(0.dp), showLibraryBadges = false,
                        onWebViewClick = {
                            navigator.push(LanraragiSettingsScreen(sourceId))
                        }, onHelpClick = { navigator.push(LanraragiSettingsScreen(sourceId)) },
                        onMangaClick = { openEntry(it, false) },
                        onMangaLongClick = { openEntry(it, true) },
                        onRefresh = { model.refresh() },
                    )
                }
                PullRefreshIndicator(
                    refreshing = refreshing,
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    scale = true,
                )
            }
        }
        if (filters) {
            LanraragiFilterSheet(
                initial = state.filter,
                downloadedOnly = state.downloadedOnly,
                onDismissRequest = { filters = false },
                onApply = { filter, downloaded -> model.applyFilters(filter, downloaded) },
            )
        }
    }
}
