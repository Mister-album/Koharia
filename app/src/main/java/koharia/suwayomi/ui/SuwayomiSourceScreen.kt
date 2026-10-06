package koharia.suwayomi.ui

import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.system.LocaleHelper
import koharia.connection.ConnectionBrowseScreen
import koharia.source.suwayomi.SuwayomiSettingsScreen
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiSourceInfo
import koharia.suwayomi.SuwayomiSourceMangaType
import koharia.suwayomi.suwayomiFiltersRequireSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.jvm.Transient
import tachiyomi.presentation.core.util.collectAsState as collectPreferenceAsState

/**
 * One Suwayomi source's remote listing. Popular, latest and per-source search pages use the
 * server's `fetchSourceManga` modes and the shared connection shelf presentation. Only ids are
 * held by the screen so Voyager can persist it; the remote source identity loads per session.
 */
class SuwayomiSourceScreen(
    override val sourceId: Long,
    private val sourceInfoId: Long,
    private val initialMode: SuwayomiSourceMangaType = SuwayomiSourceMangaType.POPULAR,
) : Screen(), ConnectionBrowseScreen {
    override val refreshOnReselect = false

    @Transient private var runtimeModel: SuwayomiSourceScreenModel? = null

    override suspend fun search(query: String) {
        runtimeModel?.search(query)
    }

    override suspend fun searchGenre(name: String) {
        runtimeModel?.search(name)
    }

    override suspend fun refresh() {
        runtimeRefresh?.invoke()
    }

    @Volatile @Transient
    private var runtimeRefresh: (() -> Unit)? = null

    @OptIn(androidx.compose.material.ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? SuwayomiSource
        if (source == null || !source.hasValidConnection()) {
            EmptyScreen(stringRes = MR.strings.connection_unavailable)
            return
        }
        val epoch by source.epoch.collectAsState()
        val model =
            rememberScreenModel(
                tag = "${source.instanceKey}:$epoch:suwayomi-source-$sourceId-$sourceInfoId-${initialMode.name}",
            ) {
                SuwayomiSourceScreenModel(source, sourceInfoId, initialMode)
            }
        runtimeModel = model
        val state by model.state.collectAsState()
        val entries = model.pager(state.listingGeneration).collectAsLazyPagingItems()
        runtimeRefresh = { model.refreshListing() }
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        val columnPreference = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            preferences.landscapeColumns
        } else {
            preferences.portraitColumns
        }
        val columns by columnPreference.collectPreferenceAsState()
        var searching by remember { mutableStateOf(false) }
        var selectingDisplayMode by remember { mutableStateOf(false) }
        var showSourceNotice by remember { mutableStateOf(false) }
        var showFilters by remember { mutableStateOf(false) }
        var opening by remember { mutableStateOf(false) }

        LaunchedEffect(model) {
            model.errors.collect { error -> snackbar.showSnackbar(context.suwayomiError(error)) }
        }

        fun open(manga: Manga) {
            if (opening) return
            opening = true
            scope.launch {
                try {
                    model.prepareOpen(manga)
                    val local = model.materialize(manga)
                    navigator.push(MangaScreen(local.id))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    snackbar.showSnackbar(context.suwayomiError(error))
                } finally {
                    opening = false
                }
            }
        }

        val info = state.info
        if (info == null) {
            val failure = state.infoError
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = { AppBar(title = stringResource(MR.strings.browse), navigateUp = navigator::pop) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    if (failure == null) {
                        LoadingScreen()
                    } else {
                        SuwayomiShelfError(
                            error = failure,
                            onRetry = model::retryInfo,
                            onSettings = { navigator.push(SuwayomiSettingsScreen(sourceId)) },
                        )
                    }
                }
            }
            return
        }
        val refreshing = state.refreshing || (entries.loadState.refresh is LoadState.Loading && entries.itemCount > 0)
        val pull = rememberPullRefreshState(refreshing = refreshing, onRefresh = { model.refreshListing() })
        val closeSearch = {
            searching = false
            model.clearSearch()
        }
        BackHandler(searching) { closeSearch() }
        Scaffold(
            topBar = {
                if (searching) {
                    SearchToolbar(
                        titleContent = { Text(info.displayName(context)) },
                        navigateUp = closeSearch,
                        searchQuery = state.query,
                        onChangeSearchQuery = { model.applyQuery(it.orEmpty()) },
                        onSearch = { model.search(it) },
                        onClickCloseSearch = closeSearch,
                        placeholderText = stringResource(MR.strings.suwayomi_source_search),
                    )
                } else {
                    AppBar(
                        title = info.displayName(context),
                        navigateUp = navigator::pop,
                        actions = {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Outlined.Search, stringResource(MR.strings.action_search))
                            }
                            IconButton(onClick = { selectingDisplayMode = true }) {
                                Icon(
                                    imageVector = if (state.displayMode == LibraryDisplayMode.List) {
                                        Icons.AutoMirrored.Filled.ViewList
                                    } else {
                                        Icons.Filled.ViewModule
                                    },
                                    contentDescription = stringResource(MR.strings.action_display_mode),
                                )
                            }
                            if (info.isConfigurable) {
                                IconButton(
                                    onClick = {
                                        navigator.push(
                                            SuwayomiSourcePreferencesScreen(
                                                sourceId = sourceId,
                                                sourceInfoId = info.id,
                                                sourceName = info.displayName(context),
                                            ),
                                        )
                                    },
                                ) {
                                    Icon(Icons.Outlined.Settings, stringResource(MR.strings.source_settings))
                                }
                            }
                            IconButton(onClick = { model.setPinned(!state.pinned) }) {
                                Icon(
                                    imageVector = if (state.pinned) {
                                        Icons.Filled.PushPin
                                    } else {
                                        Icons.Outlined.PushPin
                                    },
                                    contentDescription = stringResource(
                                        if (state.pinned) MR.strings.action_unpin else MR.strings.action_pin,
                                    ),
                                )
                            }
                            DropdownMenu(selectingDisplayMode, { selectingDisplayMode = false }) {
                                displayModes.forEach { (mode, label) ->
                                    RadioMenuItem(
                                        text = { Text(stringResource(label)) },
                                        isChecked = state.displayMode == mode,
                                    ) {
                                        selectingDisplayMode = false
                                        model.setDisplayMode(mode)
                                    }
                                }
                            }
                        },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                SuwayomiSourceModeRow(
                    state = state,
                    onSelectMode = model::setMode,
                    onShowFilters = {
                        if (state.hasFilters) showFilters = true else showSourceNotice = true
                    },
                )
                HorizontalDivider()
                Box(Modifier.fillMaxSize().pullRefresh(pull)) {
                    BrowseSourceContent(
                        modifier = Modifier.fillMaxSize(),
                        source = source,
                        mangaList = entries,
                        columns = GridCells.Fixed(columns.takeIf { it > 0 } ?: 3),
                        displayMode = state.displayMode,
                        snackbarHostState = snackbar,
                        contentPadding = PaddingValues(0.dp),
                        showLibraryBadges = false,
                        // These actions only surface on the empty/error screen, where the fix is the
                        // connection itself rather than a source setting.
                        onWebViewClick = { navigator.push(SuwayomiSettingsScreen(sourceId)) },
                        onHelpClick = { navigator.push(SuwayomiSettingsScreen(sourceId)) },
                        onMangaClick = ::open,
                        onMangaLongClick = ::open,
                        onRefresh = { model.refreshListing() },
                    )
                    PullRefreshIndicator(refreshing, pull, Modifier.align(Alignment.TopCenter))
                }
            }
        }
        if (showSourceNotice) {
            AdaptiveSheet(onDismissRequest = { showSourceNotice = false }) {
                Text(
                    text = stringResource(MR.strings.suwayomi_source_filters_unavailable),
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        if (showFilters) {
            SuwayomiSourceFilterSheet(
                filters = state.filters,
                browseFilters = state.browseFilters,
                sort = state.sort,
                filtersNeedSearch = state.appliedFilters.isNotEmpty() &&
                    suwayomiFiltersRequireSearch(state.mode),
                onDismissRequest = { showFilters = false },
                onApply = {
                    model.applyFilters(it)
                    showFilters = false
                },
                onReset = model::resetFilters,
            )
        }
    }
}

private val displayModes = listOf(
    LibraryDisplayMode.ComfortableGrid to MR.strings.action_display_comfortable_grid,
    LibraryDisplayMode.CompactGrid to MR.strings.action_display_grid,
    LibraryDisplayMode.CoverOnlyGrid to MR.strings.action_display_cover_only_grid,
    LibraryDisplayMode.List to MR.strings.action_display_list,
)

@Composable
private fun SuwayomiSourceModeRow(
    state: SuwayomiSourceScreenModel.State,
    onSelectMode: (SuwayomiSourceMangaType) -> Unit,
    onShowFilters: () -> Unit,
) {
    val modes = buildList {
        add(SuwayomiSourceMangaType.POPULAR to MR.strings.popular)
        if (state.supportsLatest) add(SuwayomiSourceMangaType.LATEST to MR.strings.latest)
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(modes) { (mode, label) ->
            FilterChip(
                selected = state.appliedQuery.isEmpty() && state.mode == mode,
                onClick = { onSelectMode(mode) },
                label = { Text(stringResource(label)) },
            )
        }
        item {
            FilterChip(
                selected = state.hasActiveFilters,
                onClick = onShowFilters,
                label = {
                    Text(
                        text = if (state.activeFilterCount > 0) {
                            stringResource(MR.strings.suwayomi_filters_active, state.activeFilterCount)
                        } else {
                            stringResource(MR.strings.action_filter)
                        },
                    )
                },
                leadingIcon = { Icon(Icons.Outlined.FilterList, null) },
            )
        }
    }
}

private fun SuwayomiSourceInfo.displayName(context: Context): String {
    val language = LocaleHelper.getSourceDisplayName(lang, context)
    return if (language.isBlank()) name else "$name ($language)"
}
