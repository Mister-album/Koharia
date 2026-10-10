package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.browse.components.BrowseSourceComfortableGrid
import eu.kanade.presentation.browse.components.BrowseSourceCompactGrid
import eu.kanade.presentation.browse.components.BrowseSourceList
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.presentation.library.components.MangaReadProgressDisplay
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.source.Source
import koharia.connection.ConnectionSeriesMetadata
import koharia.connection.MangaDownloadState
import koharia.connection.rememberConnectionShelfEntries
import koharia.connection.resolveShelfReadProgress
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun BrowseSourceContent(
    modifier: Modifier = Modifier,
    source: Source?,
    mangaList: LazyPagingItems<StateFlow<Manga>>,
    columns: GridCells,
    displayMode: LibraryDisplayMode,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    selectedMangaIds: Set<Long> = emptySet(),
    showLibraryBadges: Boolean = true,
    readProgress: ((Manga) -> MangaReadProgress?)? = null,
    readingUnitCount: ((Manga) -> Long?)? = null,
    showPagingLoadingIndicator: Boolean = true,
    onWebViewClick: () -> Unit,
    onHelpClick: () -> Unit,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    onRefresh: (() -> Unit)? = null,
    entryLabel: ((Manga) -> String)? = null,
    contentHeader: (@Composable () -> Unit)? = null,
    entryBadge: (@Composable (Manga) -> Unit)? = null,
    manualDownloadState: ((Manga) -> MangaDownloadState?)? = null,
) {
    val context = LocalContext.current
    val localEntries = rememberConnectionShelfEntries(source, mangaList)
    val showReadProgress by Injekt.get<LibraryPreferences>().showLibraryReadProgress.collectAsState()
    val expectedTotal: (Manga) -> Long? = { manga ->
        readingUnitCount?.invoke(manga)
            ?: ConnectionSeriesMetadata.fromMemo(manga.memo).booksCount?.toLong()
            ?: readProgress?.invoke(manga)
                ?.takeIf { it.display == MangaReadProgressDisplay.CHAPTERS }?.totalChapterCount
    }
    val progress: ((Manga) -> MangaReadProgress?)? = if (showReadProgress) {
        { manga ->
            resolveShelfReadProgress(localEntries[manga.url], readProgress?.invoke(manga), expectedTotal(manga))
        }
    } else {
        null
    }
    val downloadState: (Manga) -> MangaDownloadState? = { manga ->
        val local = localEntries[manga.url]
        manualDownloadState?.invoke(manga) ?: local?.downloads(expectedTotal(manga) ?: local.total)
    }

    val errorState = mangaList.loadState.refresh.takeIf { it is LoadState.Error }
        ?: mangaList.loadState.append.takeIf { it is LoadState.Error }

    val getErrorMessage: (LoadState.Error) -> String = { state ->
        with(context) { state.error.formattedMessage }
    }

    LaunchedEffect(errorState) {
        if (mangaList.itemCount > 0 && errorState != null && errorState is LoadState.Error) {
            val result = snackbarHostState.showSnackbar(
                message = getErrorMessage(errorState),
                actionLabel = context.stringResource(MR.strings.action_retry),
                duration = SnackbarDuration.Indefinite,
            )
            when (result) {
                SnackbarResult.Dismissed -> snackbarHostState.currentSnackbarData?.dismiss()
                SnackbarResult.ActionPerformed -> mangaList.retry()
            }
        }
    }

    if (
        showPagingLoadingIndicator &&
        mangaList.itemCount == 0 &&
        mangaList.loadState.refresh is LoadState.Loading
    ) {
        LoadingScreen(modifier.padding(contentPadding))
        return
    }

    if (mangaList.itemCount == 0) {
        EmptyScreen(
            modifier = modifier.padding(contentPadding),
            message = when (errorState) {
                is LoadState.Error -> getErrorMessage(errorState)
                else -> stringResource(MR.strings.no_results_found)
            },
            actions = persistentListOf(
                EmptyScreenAction(
                    stringRes = MR.strings.action_retry,
                    icon = Icons.Outlined.Refresh,
                    onClick = onRefresh ?: mangaList::refresh,
                ),
                EmptyScreenAction(
                    stringRes = MR.strings.action_open_in_web_view,
                    icon = Icons.Outlined.Public,
                    onClick = onWebViewClick,
                ),
                EmptyScreenAction(
                    stringRes = MR.strings.label_help,
                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                    onClick = onHelpClick,
                ),
            ),
        )

        return
    }

    when (displayMode) {
        LibraryDisplayMode.ComfortableGrid -> {
            BrowseSourceComfortableGrid(
                modifier = modifier,
                mangaList = mangaList,
                columns = columns,
                contentPadding = contentPadding,
                selectedMangaIds = selectedMangaIds,
                showLibraryBadges = showLibraryBadges,
                readProgress = progress,
                downloadState = downloadState,
                showPagingLoadingIndicator = showPagingLoadingIndicator,
                entryLabel = entryLabel,
                contentHeader = contentHeader,
                entryBadge = entryBadge,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }
        LibraryDisplayMode.List -> {
            BrowseSourceList(
                modifier = modifier,
                mangaList = mangaList,
                contentPadding = contentPadding,
                selectedMangaIds = selectedMangaIds,
                showLibraryBadges = showLibraryBadges,
                readProgress = progress,
                downloadState = downloadState,
                showPagingLoadingIndicator = showPagingLoadingIndicator,
                entryLabel = entryLabel,
                contentHeader = contentHeader,
                entryBadge = entryBadge,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
            BrowseSourceCompactGrid(
                modifier = modifier,
                mangaList = mangaList,
                columns = columns,
                contentPadding = contentPadding,
                showTitle = displayMode is LibraryDisplayMode.CompactGrid,
                selectedMangaIds = selectedMangaIds,
                showLibraryBadges = showLibraryBadges,
                readProgress = progress,
                downloadState = downloadState,
                showPagingLoadingIndicator = showPagingLoadingIndicator,
                entryLabel = entryLabel,
                contentHeader = contentHeader,
                entryBadge = entryBadge,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }
    }
}

@Composable
internal fun MissingSourceScreen(
    source: StubSource,
    navigateUp: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = source.name,
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        EmptyScreen(
            message = stringResource(MR.strings.source_not_installed, source.toString()),
            modifier = Modifier.padding(paddingValues),
        )
    }
}
