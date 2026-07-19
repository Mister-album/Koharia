package koharia.komga.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.more.settings.screen.KomgaLibraryClassificationScreen
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import koharia.komga.ui.library.components.KomgaLibraryToolbar
import koharia.source.komga.KomgaLibraryClassificationManager
import koharia.source.komga.KomgaLibraryScope
import koharia.source.komga.KomgaServerPreferences
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import tachiyomi.core.common.Constants
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.interactor.GetRemoteManga
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
import kotlin.math.roundToInt

data class KomgaLibraryScreen(
    val sourceId: Long,
    private val listingQuery: String?,
    private val showNavigationUp: Boolean = true,
    private val libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    // These channels are runtime event buses. Keeping them out of the
    // serializable Voyager Screen prevents Activity state saving from trying to
    // serialize kotlinx.coroutines' BufferedChannel implementation.
    @Transient
    @Volatile
    private var runtimeEvents: RuntimeEvents? = null

    private fun events(): RuntimeEvents {
        runtimeEvents?.let { return it }
        return synchronized(this) {
            runtimeEvents ?: RuntimeEvents().also { runtimeEvents = it }
        }
    }

    override fun onProvideAssistUrl() = assistUrl

    @OptIn(ExperimentalMaterialApi::class)
    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val sourceManager: SourceManager = Injekt.get()
        val sourcePreferences: SourcePreferences = Injekt.get()
        val basePreferences: BasePreferences = Injekt.get()
        val libraryPreferences: LibraryPreferences = Injekt.get()
        val komgaServerPreferences: KomgaServerPreferences = Injekt.get()
        val downloadManager: DownloadManager = Injekt.get()
        val getRemoteManga: GetRemoteManga = Injekt.get()
        val getManga: GetManga = Injekt.get()
        val getIncognitoState: GetIncognitoState = Injekt.get()
        val libraryClassificationManager: KomgaLibraryClassificationManager = Injekt.get()

        val screenModel = rememberScreenModel(tag = "$sourceId:$libraryScope:$listingQuery") {
            KomgaLibraryScreenModel(
                sourceId = sourceId,
                listingQuery = listingQuery,
                sourceManager = sourceManager,
                sourcePreferences = sourcePreferences,
                basePreferences = basePreferences,
                libraryPreferences = libraryPreferences,
                downloadManager = downloadManager,
                getRemoteManga = getRemoteManga,
                getManga = getManga,
                getIncognitoState = getIncognitoState,
                libraryScope = libraryScope,
                libraryClassificationManager = libraryClassificationManager,
            )
        }
        val state by screenModel.state.collectAsState()
        val columns by libraryPreferences.portraitColumns.collectAsState()
        val serverProfiles by remember(komgaServerPreferences) {
            komgaServerPreferences.profilesChanges()
        }.collectAsState(initial = komgaServerPreferences.getProfiles())

        val navigator = LocalNavigator.currentOrThrow
        val navigateUp: () -> Unit = {
            when {
                !state.isUserQuery && state.toolbarQuery != null -> screenModel.setToolbarQuery(null)
                else -> navigator.pop()
            }
        }

        if (screenModel.source is StubSource) {
            MissingSourceScreen(
                source = screenModel.source,
                navigateUp = navigateUp,
            )
            return
        }

        val uriHandler = LocalUriHandler.current
        val snackbarHostState = remember { SnackbarHostState() }
        val mangaList = screenModel.mangaPagerFlow.collectAsLazyPagingItems()
        val isRefreshing =
            state.isRefreshing || (mangaList.itemCount > 0 && mangaList.loadState.refresh is LoadState.Loading)
        val pullRefreshState = rememberPullRefreshState(
            refreshing = isRefreshing,
            onRefresh = screenModel::refresh,
        )
        val density = LocalDensity.current
        val pullOffsetPx by remember(isRefreshing, pullRefreshState, density) {
            derivedStateOf<Float> {
                val pullProgress = pullRefreshState.progress.coerceAtMost(1f)
                val dragOffset = with(density) { 72.dp.toPx() } * pullRefreshState.progress.coerceAtMost(1f)
                if (pullProgress > 0f) dragOffset else 0f
            }
        }

        val onHelpClick = { uriHandler.openUri(Constants.URL_HELP) }
        val onWebViewClick = f@{
            val source = screenModel.source as? HttpSource ?: return@f
            navigator.push(
                WebViewScreen(
                    url = source.baseUrl,
                    initialTitle = source.name,
                    sourceId = source.id,
                ),
            )
        }

        LaunchedEffect(screenModel.source) {
            assistUrl = (screenModel.source as? HttpSource)?.baseUrl
        }

        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {},
                ) {
                    KomgaLibraryToolbar(
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = screenModel::setToolbarQuery,
                        displayMode = screenModel.displayMode,
                        onDisplayModeChange = { screenModel.displayMode = it },
                        serverProfiles = serverProfiles,
                        activeServerId = sourceId,
                        onServerSelect = { serverId ->
                            if (serverId != sourceId) {
                                komgaServerPreferences.activeServerId.set(serverId)
                            }
                        },
                        showFilterAction = state.filters.isNotEmpty(),
                        onFilterClick = screenModel::openFilterSheet,
                        navigateUp = navigateUp.takeIf { showNavigationUp },
                        onSearch = screenModel::search,
                    )

                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = MaterialTheme.padding.small),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        if (state.komgaLibraries.isNotEmpty()) {
                            FilterChip(
                                selected = state.selectedKomgaLibraryId == null,
                                onClick = {
                                    screenModel.selectKomgaLibrary(null)
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.all))
                                },
                            )
                            state.komgaLibraries.forEach { library ->
                                FilterChip(
                                    selected = state.selectedKomgaLibraryId == library.id,
                                    onClick = { screenModel.selectKomgaLibrary(library.id) },
                                    label = {
                                        Text(text = library.name)
                                    },
                                )
                            }
                        }
                    }

                    HorizontalDivider()
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pullRefresh(pullRefreshState),
            ) {
                if (state.isLibraryScopeEmpty && libraryScope != KomgaLibraryScope.ALL) {
                    EmptyScreen(
                        stringRes = MR.strings.komga_library_classification_empty,
                        modifier = Modifier.padding(paddingValues),
                        actions = persistentListOf(
                            EmptyScreenAction(
                                stringRes = MR.strings.komga_library_classification_configure,
                                icon = Icons.Outlined.Settings,
                                onClick = { navigator.push(KomgaLibraryClassificationScreen()) },
                            ),
                        ),
                    )
                } else {
                    BrowseSourceContent(
                        source = screenModel.source,
                        mangaList = mangaList,
                        columns = libraryGridCellsForColumns(columns),
                        displayMode = screenModel.displayMode,
                        snackbarHostState = snackbarHostState,
                        contentPadding = paddingValues,
                        showLibraryBadges = false,
                        onWebViewClick = onWebViewClick,
                        onHelpClick = onHelpClick,
                        onMangaClick = {
                            navigator.push(
                                MangaScreen(
                                    mangaId = it.id,
                                    fromSource = true,
                                    sourceId = it.source,
                                    mangaUrl = it.url,
                                ),
                            )
                        },
                        onMangaLongClick = {},
                        modifier = Modifier.offset { IntOffset(x = 0, y = pullOffsetPx.roundToInt()) },
                    )
                }
                PullRefreshIndicator(
                    refreshing = isRefreshing,
                    state = pullRefreshState,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = paddingValues.calculateTopPadding()),
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    scale = true,
                )
            }
        }

        val onDismissRequest = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is KomgaLibraryScreenModel.Dialog.Filter -> {
                KomgaFilterDialog(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    persistentFilteringEnabled = state.persistentFilteringEnabled,
                    onReset = screenModel::resetFilters,
                    onFilter = { screenModel.search(filters = state.filters) },
                    onUpdate = screenModel::setFilters,
                    onPersistentFilteringChange = screenModel::setPersistentFilteringEnabled,
                )
            }
            else -> {}
        }

        LaunchedEffect(Unit) {
            events().query.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Genre -> screenModel.searchGenre(it.txt)
                        is SearchType.Text -> screenModel.search(it.txt)
                    }
                }
        }

        LaunchedEffect(Unit) {
            events().refresh.receiveAsFlow().collectLatest {
                screenModel.refresh()
            }
        }
    }

    suspend fun search(query: String) = events().query.send(SearchType.Text(query))
    suspend fun searchGenre(name: String) = events().query.send(SearchType.Genre(name))
    suspend fun refresh() = events().refresh.send(Unit)

    private class RuntimeEvents {
        val query = Channel<SearchType>()
        val refresh = Channel<Unit>(capacity = Channel.CONFLATED)
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}

private fun libraryGridCellsForColumns(columns: Int): GridCells {
    val coercedColumns = columns.coerceIn(0, 10)
    return if (coercedColumns == 0) {
        GridCells.Adaptive(128.dp)
    } else {
        GridCells.Fixed(coercedColumns)
    }
}
