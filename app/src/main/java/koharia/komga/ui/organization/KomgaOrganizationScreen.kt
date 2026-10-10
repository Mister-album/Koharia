package koharia.komga.ui.organization

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionReadingQueueController
import koharia.connection.EntryOpenMode
import koharia.connection.EntryOpenPreferences
import koharia.connection.LocalOrganizationScreenOwner
import koharia.connection.ui.CONNECTION_SHELF_STATIC_LOAD_STATES
import koharia.connection.ui.ConnectionLibraryToolbar
import koharia.epub.EpubReaderLauncher
import koharia.komga.api.KomgaOrganization
import koharia.komga.api.KomgaOrganizationKind
import koharia.komga.domain.repository.KomgaOrganizationException
import koharia.komga.domain.repository.KomgaOrganizationFailure
import koharia.komga.domain.repository.KomgaOrganizationRepository
import koharia.source.komga.KomgaSource
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.i18n.stringResource as contextStringResource

data class KomgaOrganizationScreen(
    val sourceId: Long,
    val kind: KomgaOrganizationKind,
    val organizationId: String? = null,
    val showNavigationUp: Boolean = true,
    val addingMembers: List<String> = emptyList(),
) : Screen() {
    @Composable
    override fun Content() = Content(pageTabs = {})

    @OptIn(ExperimentalMaterialApi::class)
    @Composable
    fun Content(pageTabs: @Composable () -> Unit, onOrganizationRefresh: () -> Unit = {}) {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }
        val source = Injekt.get<SourceManager>().get(sourceId) as? KomgaSource ?: return
        val repository =
            remember(source, source.shelfCacheNamespace()) { source.organizationRepository() }
        val modelOwner = LocalOrganizationScreenOwner.current ?: this
        val model =
            modelOwner.rememberScreenModel(tag = "${repository.namespace}:$kind:$organizationId") {
                KomgaOrganizationScreenModel(repository, kind, organizationId)
            }
        val state by model.state.collectAsState()
        BackHandler(state.selected.isNotEmpty(), model::clearSelection)
        val bookStates by
            remember(repository, state.books) { repository.observeBooks(state.books) }
                .collectAsState(emptyMap())
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val openBook = openBookAction()
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        val displayMode by preferences.displayMode.collectAsState()
        val columns by
            (
                if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    preferences.landscapeColumns
                } else {
                    preferences.portraitColumns
                }
                )
                .collectAsState()
        val connections = remember { Injekt.get<ConnectionPreferences>() }
        val profiles by
            connections.profilesChanges().collectAsState(initial = connections.getProfiles())
        val snackbar = remember { SnackbarHostState() }
        var filters by remember { mutableStateOf(false) }
        var delete by remember { mutableStateOf(false) }
        val title = state.organization?.name ?: stringResource(kind.titleResource())
        val createTitle = stringResource(MR.strings.action_create)
        val editTitle = stringResource(MR.strings.action_edit)
        val deleteTitle = stringResource(MR.strings.action_delete)
        val readTitle = stringResource(MR.strings.action_resume)
        val downloadTitle = stringResource(MR.strings.action_download)
        val addCollectionTitle = stringResource(MR.strings.komga_add_collection)
        val addReadListTitle = stringResource(MR.strings.komga_add_readlist)
        val importTitle = stringResource(MR.strings.komga_import_readlist)
        val actions =
            buildList<AppBar.AppBarAction> {
                if (state.admin && organizationId == null) {
                    add(
                        AppBar.Action(
                            createTitle,
                            Icons.Outlined.Add,
                            enabled = !state.busy,
                            onClick = {
                                navigator.push(
                                    KomgaOrganizationEditScreen(
                                        sourceId,
                                        kind,
                                        initialMembers = addingMembers,
                                    ),
                                )
                            },
                        ),
                    )
                    if (kind == KomgaOrganizationKind.READ_LIST && addingMembers.isEmpty()) {
                        add(
                            AppBar.OverflowAction(importTitle) {
                                navigator.push(KomgaReadListImportScreen(sourceId))
                            },
                        )
                    }
                }
                if (state.admin && organizationId != null) {
                    add(
                        AppBar.OverflowAction(editTitle) {
                            navigator.push(
                                KomgaOrganizationEditScreen(sourceId, kind, organizationId),
                            )
                        },
                    )
                    add(AppBar.OverflowAction(deleteTitle) { delete = true })
                }
            }
        val export =
            rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/zip"),
            ) { uri ->
                if (uri != null && organizationId != null) {
                    model.action {
                        repository.authorize("FILE_DOWNLOAD")
                        try {
                            context.contentResolver.openOutputStream(uri)?.use {
                                repository.api.export(organizationId, it)
                            }
                                ?: error(
                                    context.contextStringResource(
                                        MR.strings.komga_organization_file_error,
                                    ),
                                )
                        } catch (error: Exception) {
                            runCatching {
                                android.provider.DocumentsContract.deleteDocument(
                                    context.contentResolver,
                                    uri,
                                )
                            }
                            throw error
                        }
                    }
                }
            }
        LaunchedEffect(state.error) {
            state.error?.let { snackbar.showSnackbar(organizationError(context, it)) }
        }
        val refresh = {
            model.load(true)
            onOrganizationRefresh()
        }
        val pull = rememberPullRefreshState(state.loading, refresh)
        Scaffold(
            topBar = {
                Column {
                    if (state.selected.isEmpty()) {
                        ConnectionLibraryToolbar(
                            title = title,
                            searchQuery = state.toolbarQuery,
                            onSearchQueryChange = model::toolbarQuery,
                            onSearch = model::search,
                            onCloseSearch = {
                                model.toolbarQuery(null)
                                model.search("")
                            },
                            displayMode = displayMode,
                            onDisplayModeChange = preferences.displayMode::set,
                            connectionProfiles = profiles,
                            activeConnectionId = sourceId,
                            onConnectionSelect = {
                                connections.activeConnectionId.set(it)
                                if (showNavigationUp) navigator.popUntilRoot()
                            },
                            onFilterClick = { filters = true },
                            onRefresh = refresh,
                            navigateUp = if (showNavigationUp) ({ navigator.pop() }) else null,
                            additionalActions = actions,
                            hasActiveFilters = state.query.filters.isNotEmpty(),
                        )
                    } else {
                        val selectedActions =
                            buildList<AppBar.AppBarAction> {
                                if (organizationId == null) {
                                    if (state.admin) {
                                        add(
                                            AppBar.Action(
                                                deleteTitle,
                                                Icons.Outlined.Delete,
                                                onClick = { delete = true },
                                            ),
                                        )
                                    }
                                } else {
                                    add(
                                        AppBar.OverflowAction(
                                            stringResource(MR.strings.action_mark_as_read),
                                        ) {
                                            model.action {
                                                repository.markRead(model.selectedBooks(), true)
                                            }
                                        },
                                    )
                                    add(
                                        AppBar.OverflowAction(
                                            stringResource(MR.strings.action_mark_as_unread),
                                        ) {
                                            model.action {
                                                repository.markRead(model.selectedBooks(), false)
                                            }
                                        },
                                    )
                                    if (state.downloadAllowed) {
                                        add(
                                            AppBar.OverflowAction(downloadTitle) {
                                                model.action {
                                                    repository.download(model.selectedBooks())
                                                }
                                            },
                                        )
                                    }
                                    if (state.admin) {
                                        if (
                                            kind == KomgaOrganizationKind.COLLECTION ||
                                            state.books
                                                .filter { it.id in state.selected }
                                                .all { it.oneshot }
                                        ) {
                                            add(
                                                AppBar.OverflowAction(addCollectionTitle) {
                                                    val ids =
                                                        if (
                                                            kind == KomgaOrganizationKind.COLLECTION
                                                        ) {
                                                            state.selected.toList()
                                                        } else {
                                                            state.books
                                                                .filter { it.id in state.selected }
                                                                .map { it.seriesId }
                                                                .distinct()
                                                        }
                                                    navigator.push(
                                                        KomgaAddToOrganizationScreen(
                                                            sourceId,
                                                            KomgaOrganizationKind.COLLECTION,
                                                            ids,
                                                        ),
                                                    )
                                                },
                                            )
                                        }
                                        add(
                                            AppBar.OverflowAction(addReadListTitle) {
                                                model.action {
                                                    val ids = model.selectedBooks().map { it.id }
                                                    withContext(Dispatchers.Main) {
                                                        navigator.push(
                                                            KomgaAddToOrganizationScreen(
                                                                sourceId,
                                                                KomgaOrganizationKind.READ_LIST,
                                                                ids,
                                                            ),
                                                        )
                                                    }
                                                }
                                            },
                                        )
                                    }
                                    if (kind == KomgaOrganizationKind.READ_LIST) {
                                        add(
                                            AppBar.OverflowAction(readTitle) {
                                                openBook(model, state.selected.first())
                                            },
                                        )
                                    }
                                }
                            }
                        AppBar(
                            title = title,
                            actionModeCounter = state.selected.size,
                            onCancelActionMode = model::clearSelection,
                            actionModeActions = { AppBarActions(selectedActions.toImmutableList()) },
                        )
                    }
                    pageTabs()
                    HorizontalDivider()
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().pullRefresh(pull)) {
                if (organizationId == null) {
                    if (state.loading && state.objects.isEmpty()) {
                        LoadingScreen(Modifier.padding(padding))
                    } else if (state.objects.isEmpty()) {
                        EmptyScreen(
                            stringResource(MR.strings.no_results_found),
                            Modifier.padding(padding),
                        )
                    } else {
                        OrganizationCards(
                            state.objects,
                            kind,
                            sourceId,
                            displayMode,
                            organizationGridColumns(columns),
                            padding,
                            state.selected,
                            { item ->
                                if (addingMembers.isNotEmpty()) {
                                    model.action {
                                        repository.add(kind, item.id, addingMembers)
                                        withContext(Dispatchers.Main) { navigator.pop() }
                                    }
                                } else if (state.selected.isNotEmpty()) {
                                    model.select(item.id)
                                } else {
                                    navigator.push(
                                        copy(organizationId = item.id, showNavigationUp = true),
                                    )
                                }
                            },
                            { model.select(it.id) },
                            { OrganizationPageControls(state, model::page) },
                        )
                    }
                } else {
                    val mangas = model.mangas()
                    val paging =
                        remember(mangas) {
                            MutableStateFlow(
                                PagingData.from(
                                    mangas.map {
                                        MutableStateFlow(it)
                                            as StateFlow<tachiyomi.domain.manga.model.Manga>
                                    },
                                    CONNECTION_SHELF_STATIC_LOAD_STATES,
                                ),
                            )
                        }
                    val pages = paging.collectAsLazyPagingItems()
                    BrowseSourceContent(
                        source = source,
                        mangaList = pages,
                        columns = organizationGridColumns(columns),
                        displayMode = displayMode,
                        snackbarHostState = snackbar,
                        contentPadding = padding,
                        showLibraryBadges = false,
                        selectedMangaIds =
                        state.selected.map(KomgaOrganizationRepository::presentationId).toSet(),
                        readProgress = { manga ->
                            state.books
                                .firstOrNull { manga.url.endsWith("/${it.id}") }
                                ?.let {
                                    MangaReadProgress(
                                        bookStates[it.id]?.read
                                            ?: if (it.readProgress?.completed == true) 1 else 0,
                                        1,
                                    )
                                }
                        },
                        readingUnitCount =
                        if (kind == KomgaOrganizationKind.READ_LIST) ({ 1L }) else null,
                        manualDownloadState =
                        if (kind == KomgaOrganizationKind.READ_LIST) {
                            (
                                { manga ->
                                    bookStates[manga.url.substringAfterLast('/')]?.downloads(1)
                                }
                                )
                        } else {
                            null
                        },
                        onWebViewClick = {},
                        onHelpClick = {},
                        onRefresh = refresh,
                        onMangaClick = { manga ->
                            val id = manga.url.substringAfterLast('/')
                            if (state.selected.isNotEmpty()) {
                                model.select(id)
                            } else if (
                                kind == KomgaOrganizationKind.READ_LIST &&
                                Injekt.get<EntryOpenPreferences>().komgaMode() ==
                                EntryOpenMode.READER
                            ) {
                                openBook(model, id)
                            } else {
                                model.action {
                                    val localId =
                                        if (kind == KomgaOrganizationKind.COLLECTION) {
                                            repository
                                                .materialize(state.series.first { it.id == id })
                                                .id
                                        } else {
                                            repository
                                                .resolveBook(state.books.first { it.id == id })
                                                .mangaId
                                        }
                                    withContext(Dispatchers.Main) {
                                        if (
                                            kind == KomgaOrganizationKind.READ_LIST &&
                                            Injekt.get<EntryOpenPreferences>().komgaMode() ==
                                            EntryOpenMode.PAGE_PREVIEW
                                        ) {
                                            navigator.push(
                                                koharia.lanraragi.ui.LanraragiArchivePreviewScreen(
                                                    localId,
                                                    sourceId,
                                                ),
                                            )
                                        } else {
                                            navigator.push(MangaScreen(localId))
                                        }
                                    }
                                }
                            }
                        },
                        onMangaLongClick = { model.select(it.url.substringAfterLast('/')) },
                        contentHeader = {
                            Column(Modifier.padding(8.dp)) {
                                state.organization
                                    ?.summary
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                if (kind == KomgaOrganizationKind.READ_LIST) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            enabled = !state.busy && state.books.isNotEmpty(),
                                            onClick = {
                                                openBook(
                                                    model,
                                                    repository.lastReading(organizationId)
                                                        ?: state.books
                                                            .firstOrNull {
                                                                it.readProgress?.completed != true
                                                            }
                                                            ?.id
                                                        ?: state.books.first().id,
                                                )
                                            },
                                        ) {
                                            Text(readTitle)
                                        }
                                        if (state.downloadAllowed) {
                                            TextButton(
                                                enabled = !state.busy,
                                                onClick = { export.launch("$title.zip") },
                                            ) {
                                                Text(stringResource(MR.strings.komga_export_zip))
                                            }
                                        }
                                    }
                                }
                                OrganizationPageControls(state, model::page)
                            }
                        },
                    )
                }
                PullRefreshIndicator(
                    state.loading,
                    pull,
                    Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
                )
            }
        }
        if (filters) {
            KomgaOrganizationFilterSheet(
                kind,
                organizationId,
                state,
                repository,
                { filters = false },
            ) { values, sort ->
                filters = false
                model.filter(values, sort)
            }
        }
        if (delete) {
            AlertDialog(
                onDismissRequest = { delete = false },
                title = { Text(deleteTitle) },
                text = { Text(stringResource(MR.strings.komga_delete_organization_message)) },
                confirmButton = {
                    TextButton(
                        enabled = !state.busy,
                        onClick = {
                            delete = false
                            model.action {
                                val failed =
                                    repository.delete(
                                        kind,
                                        if (organizationId != null) {
                                            listOf(organizationId)
                                        } else {
                                            state.selected.toList()
                                        },
                                    )
                                if (failed.isNotEmpty()) {
                                    error(
                                        failed.joinToString("\n") { (id, error) ->
                                            val name = state.objects.firstOrNull { it.id == id }?.name ?: title
                                            "$name: ${organizationError(context, error)}"
                                        },
                                    )
                                }
                                if (organizationId != null) {
                                    withContext(Dispatchers.Main) { navigator.pop() }
                                }
                            }
                        },
                    ) {
                        Text(deleteTitle)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { delete = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
    }

    @Composable
    private fun openBookAction(): (KomgaOrganizationScreenModel, String) -> Unit {
        val context = LocalContext.current
        return { model, id ->
            model.action {
                val list = requireNotNull(model.state.value.organization)
                val books = model.allBooks()
                val book =
                    books.firstOrNull { it.id == id }
                        ?: books.firstOrNull { it.readProgress?.completed != true }
                        ?: books.first()
                val resolved = model.repository.resolveBook(book)
                val queue = model.repository.createQueue(list, books)
                model.repository.rememberReading(list.id, book.id)
                withContext(Dispatchers.Main) {
                    val intent =
                        EpubReaderLauncher()
                            .resolveIntent(context, resolved.mangaId, resolved.chapterId)
                    ConnectionReadingQueueController.attach(
                        intent,
                        sourceId,
                        queue,
                        resolved.chapterUrl,
                    )
                    context.startActivity(intent)
                }
            }
        }
    }
}

@Composable
internal fun OrganizationCards(
    items: List<KomgaOrganization>,
    kind: KomgaOrganizationKind,
    sourceId: Long,
    mode: LibraryDisplayMode,
    columns: GridCells,
    padding: PaddingValues,
    selected: Set<String>,
    onClick: (KomgaOrganization) -> Unit,
    onLongClick: (KomgaOrganization) -> Unit,
    header: @Composable () -> Unit,
    cover: (KomgaOrganization) -> MangaCover = { organizationCover(it, kind, sourceId) },
) {
    if (mode == LibraryDisplayMode.List) {
        LazyColumn(contentPadding = padding) {
            item { header() }
            items(items, key = { it.id }) { item ->
                MangaListItem(
                    cover(item),
                    item.name,
                    { onClick(item) },
                    { onLongClick(item) },
                    badge = { Text(item.members(kind).size.toString()) },
                    isSelected = item.id in selected,
                )
            }
        }
    } else {
        LazyVerticalGrid(
            columns = columns,
            contentPadding =
            PaddingValues(8.dp).let {
                PaddingValues(
                    it.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    padding.calculateTopPadding() + 8.dp,
                    8.dp,
                    padding.calculateBottomPadding() + 8.dp,
                )
            },
            horizontalArrangement =
            Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
            verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(items, key = { it.id }) { item ->
                if (mode == LibraryDisplayMode.ComfortableGrid) {
                    MangaComfortableGridItem(
                        cover(item),
                        item.name,
                        { onClick(item) },
                        { onLongClick(item) },
                        isSelected = item.id in selected,
                        coverBadgeStart = { Text(item.members(kind).size.toString()) },
                    )
                } else {
                    MangaCompactGridItem(
                        cover(item),
                        { onClick(item) },
                        { onLongClick(item) },
                        isSelected = item.id in selected,
                        title = item.name.takeUnless { mode == LibraryDisplayMode.CoverOnlyGrid },
                        coverBadgeStart = { Text(item.members(kind).size.toString()) },
                    )
                }
            }
        }
    }
}

private fun organizationCover(
    item: KomgaOrganization,
    kind: KomgaOrganizationKind,
    sourceId: Long,
): MangaCover {
    val source = Injekt.get<SourceManager>().get(sourceId) as KomgaSource
    return MangaCover(
        KomgaOrganizationRepository.presentationId(item.id),
        sourceId,
        false,
        "${source.baseUrl}/api/v1/${kind.path}/${item.id}/thumbnail" +
            "?revision=${source.organizationRevision()}&account=${source.shelfCacheNamespace()}",
        0L,
    )
}

@Composable
private fun OrganizationPageControls(state: KomgaOrganizationState, page: (Int) -> Unit) {
    if (state.totalPages <= 1) return
    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            enabled = state.query.page > 0 && !state.loading,
            onClick = { page(state.query.page - 1) },
        ) {
            Text(stringResource(MR.strings.komga_previous_page))
        }
        Text(stringResource(MR.strings.komga_page_number, state.query.page + 1, state.totalPages))
        TextButton(
            enabled = state.query.page + 1 < state.totalPages && !state.loading,
            onClick = { page(state.query.page + 1) },
        ) {
            Text(stringResource(MR.strings.komga_next_page))
        }
    }
}

internal fun KomgaOrganizationKind.titleResource() =
    if (this == KomgaOrganizationKind.COLLECTION) {
        MR.strings.komga_collections
    } else {
        MR.strings.komga_filter_read_lists
    }

private fun organizationGridColumns(columns: Int): GridCells =
    if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns.coerceIn(1, 10))

internal fun organizationError(context: android.content.Context, error: Throwable): String =
    if (error is KomgaOrganizationException) {
        context.contextStringResource(
            when (error.reason) {
                KomgaOrganizationFailure.ACCOUNT_CHANGED ->
                    MR.strings.komga_organization_account_changed
                KomgaOrganizationFailure.PERMISSION -> MR.strings.komga_organization_permission
                KomgaOrganizationFailure.CONFLICT -> MR.strings.komga_organization_conflict
                KomgaOrganizationFailure.FILTERED -> MR.strings.komga_organization_filtered
                KomgaOrganizationFailure.EMPTY -> MR.strings.komga_organization_empty
                KomgaOrganizationFailure.UNSUPPORTED -> MR.strings.komga_organization_unsupported
                KomgaOrganizationFailure.UNKNOWN_WRITE ->
                    MR.strings.komga_organization_unknown_write
            },
        )
    } else if (error is eu.kanade.tachiyomi.network.HttpException && error.code == 403) {
        context.contextStringResource(MR.strings.komga_organization_permission)
    } else {
        with(context) { error.formattedMessage }
    }
