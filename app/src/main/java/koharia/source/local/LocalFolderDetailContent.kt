package koharia.source.local

import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.presentation.library.components.displayText
import eu.kanade.presentation.manga.ChapterProgressCorner
import eu.kanade.presentation.manga.ChapterReadCorner
import eu.kanade.presentation.manga.components.ChapterHeader
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MangaDetailLayout
import eu.kanade.presentation.manga.components.MangaInfoBox
import eu.kanade.presentation.manga.components.MangaToolbar
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
internal fun LocalFolderDetailContent(
    folder: Manga,
    mangaList: LazyPagingItems<StateFlow<Manga>>,
    source: LocalFolderSource,
    selectedIds: Set<Long>,
    displayMode: Long,
    columns: Int,
    readProgress: Map<String, MangaReadProgress>,
    showReadProgress: Boolean,
    showFileSize: Boolean,
    refreshing: Boolean,
    hasFilters: Boolean,
    error: String?,
    snackbarHostState: SnackbarHostState,
    navigateUp: () -> Unit,
    onDisplayModeChange: (Long) -> Unit,
    onFilter: () -> Unit,
    onRefresh: () -> Unit,
    onImport: () -> Unit,
    onEditSeriesDetails: () -> Unit,
    onEditNotes: () -> Unit,
    onCoverClick: () -> Unit,
    onSearch: (String) -> Unit,
    onSearchClick: () -> Unit,
    onReadAsComic: (() -> Unit)?,
    onRecoverOperation: (() -> Unit)?,
    onEntryClick: (Manga) -> Unit,
    onContinueReading: (Manga) -> Unit,
    onEntryLongClick: (Manga) -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onClearSelection: () -> Unit,
    onMarkRead: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val tablet = isTabletUi()
    val entries = mangaList.itemSnapshotList.items.map { it.value }
    val nextEntry = entries.firstOrNull {
        source.indexedEntry(it.url)?.let { entry ->
            entry.kind != LocalLibraryItem.Kind.FOLDER || entry.imageComic
        } == true &&
            readProgress[it.url.trimEnd('/')]?.let { progress ->
                progress.totalChapterCount == 0L || progress.readCount < progress.totalChapterCount
            } != false
    }
    val folderProgress = readProgress[folder.url.trimEnd('/')]
    val useGrid = displayMode != Manga.CHAPTER_COVER_DISPLAY_TEXT
    MangaDetailLayout(
        isTabletUi = tablet,
        useGrid = useGrid,
        columns = columns,
        refreshing = refreshing,
        selectionActive = selectedIds.isNotEmpty(),
        onRefresh = onRefresh,
        snackbarHostState = snackbarHostState,
        toolbar = { titleAlpha, backgroundAlpha ->
            MangaToolbar(
                title = folder.title,
                hasFilters = hasFilters,
                isConnectionCacheMode = false,
                chapterCoverDisplayMode = displayMode,
                navigateUp = navigateUp,
                onClickFilter = onFilter,
                onChapterCoverDisplayModeChange = onDisplayModeChange,
                onClickShare = null,
                onClickDownload = null,
                onClickEditSeriesDetails = onEditSeriesDetails,
                onClickEditCategory = null,
                editCategoryAsLibraryShelf = true,
                onClickRefresh = onRefresh,
                onClickMigrate = null,
                onClickEditNotes = onEditNotes,
                actionModeCounter = selectedIds.size,
                onCancelActionMode = onClearSelection,
                onSelectAll = onSelectAll,
                onInvertSelection = onInvertSelection,
                titleAlphaProvider = { titleAlpha },
                backgroundAlphaProvider = { backgroundAlpha },
                additionalActions = listOfNotNull(
                    AppBar.Action(
                        stringResource(MR.strings.local_library_import_files),
                        Icons.Outlined.UploadFile,
                        onClick = onImport,
                    ),
                    AppBar.OverflowAction(stringResource(MR.strings.action_search), onSearchClick),
                    onReadAsComic?.let {
                        AppBar.OverflowAction(stringResource(MR.strings.local_library_show_as_comic), it)
                    },
                    onRecoverOperation?.let {
                        AppBar.OverflowAction(stringResource(MR.strings.local_library_pending_operation), it)
                    },
                ),
            )
        },
        info = { isTablet, topPadding, bleed ->
            MangaInfoBox(
                isTabletUi = isTablet,
                appBarPadding = topPadding,
                manga = folder,
                sourceName = "",
                isStubSource = false,
                onCoverClick = onCoverClick,
                doSearch = { query, _ -> onSearch(query) },
                backdropHorizontalBleed = bleed,
            )
        },
        summary = {
            ExpandableMangaDescription(
                description = folder.description,
                tagsProvider = { folder.genre },
                notes = folder.notes,
                onTagSearch = onSearch,
                onCopyTagToClipboard = { tag -> context.copyToClipboard(tag, tag) },
                onEditNotes = onEditNotes,
            )
        },
        contentHeader = {
            ChapterHeader(
                enabled = selectedIds.isEmpty(),
                chapterCount = null,
                missingChapterCount = 0,
                onClick = onFilter,
                countLabel = stringResource(
                    MR.strings.local_library_folder_item_count,
                    folderProgress?.totalChapterCount?.toInt() ?: mangaList.itemCount,
                ),
            )
        },
        listContent = {
            if (mangaList.itemCount == 0) item { FolderEmptyContent(refreshing, error, hasFilters) }
            items(
                mangaList.itemCount,
                key = { mangaList.peek(it)?.value?.id ?: "loading-$it" },
            ) { index ->
                val manga by mangaList[index]?.collectAsState() ?: return@items
                val entry = source.indexedEntry(manga.url)
                val progress = readProgress[manga.url.trimEnd('/')].takeIf { showReadProgress }
                val progressText = progress?.takeIf {
                    entry?.let { it.kind == LocalLibraryItem.Kind.FOLDER && !it.imageComic } == true ||
                        it.readCount > 0
                }?.displayText()
                MangaChapterListItem(
                    title = folderEntryTitle(manga, entry, showFileSize),
                    date = entry?.modifiedAt?.let { relativeDateText(it) },
                    readProgress = progressText,
                    scanlator = null,
                    read = progress.isComplete(),
                    showReadStatus = showReadProgress && progress != null,
                    bookmark = false,
                    selected = manga.id in selectedIds,
                    downloadIndicatorEnabled = false,
                    isConnectionCacheMode = false,
                    downloadStateProvider = { Download.State.NOT_DOWNLOADED },
                    downloadProgressProvider = { 0 },
                    chapterSwipeStartAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                    chapterSwipeEndAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                    onLongClick = { onEntryLongClick(manga) },
                    onClick = { onEntryClick(manga) },
                    onDownloadClick = null,
                    onChapterSwipe = {},
                )
            }
        },
        gridContent = {
            if (mangaList.itemCount == 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FolderEmptyContent(refreshing, error, hasFilters)
                }
            }
            items(
                mangaList.itemCount,
                key = { mangaList.peek(it)?.value?.id ?: "loading-$it" },
            ) { index ->
                val manga by mangaList[index]?.collectAsState() ?: return@items
                val entry = source.indexedEntry(manga.url)
                val progress = readProgress[manga.url.trimEnd('/')].takeIf { showReadProgress }
                val progressText = progress?.takeIf {
                    entry?.let { it.kind == LocalLibraryItem.Kind.FOLDER && !it.imageComic } == true ||
                        it.readCount > 0
                }?.displayText()
                val overlay: (@Composable BoxScope.() -> Unit)? = if (progress.isComplete() || progressText != null) {
                    {
                        if (progress.isComplete()) {
                            ChapterReadCorner(Modifier.align(Alignment.TopEnd))
                        } else {
                            ChapterProgressCorner(checkNotNull(progressText), Modifier.align(Alignment.TopEnd))
                        }
                    }
                } else {
                    null
                }
                val cover =
                    MangaCover(manga.id, manga.source, manga.favorite, manga.thumbnailUrl, manga.coverLastModified)
                if (displayMode == Manga.CHAPTER_COVER_DISPLAY_COMFORTABLE) {
                    MangaComfortableGridItem(
                        title = folderEntryTitle(manga, entry, showFileSize),
                        coverData = cover,
                        isSelected = manga.id in selectedIds,
                        coverOverlay = overlay,
                        onClick = { onEntryClick(manga) },
                        onLongClick = { onEntryLongClick(manga) },
                    )
                } else {
                    MangaCompactGridItem(
                        title = if (displayMode == Manga.CHAPTER_COVER_DISPLAY_COVER_AND_TITLE) {
                            folderEntryTitle(manga, entry, showFileSize)
                        } else {
                            null
                        },
                        coverData = cover,
                        isSelected = manga.id in selectedIds,
                        coverOverlay = overlay,
                        onClick = { onEntryClick(manga) },
                        onLongClick = { onEntryLongClick(manga) },
                    )
                }
            }
        },
        bottomBar = {
            MangaBottomActionMenu(
                visible = selectedIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(if (tablet) 0.5f else 1f),
                onBookmarkClicked = null,
                onRemoveBookmarkClicked = null,
                onMarkAsReadClicked = { onMarkRead(true) },
                onMarkAsUnreadClicked = { onMarkRead(false) },
                onMarkPreviousAsReadClicked = null,
                onDownloadClicked = null,
                onDeleteClicked = onDelete,
                isConnectionCacheMode = false,
            )
        },
        floatingActionButton = { expanded ->
            if (nextEntry != null) {
                SmallExtendedFloatingActionButton(
                    text = {
                        Text(
                            stringResource(
                                if (entries.any { (readProgress[it.url]?.readCount ?: 0L) > 0 }) {
                                    MR.strings.action_resume
                                } else {
                                    MR.strings.action_start
                                },
                            ),
                        )
                    },
                    icon = {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                        )
                    },
                    onClick = { onContinueReading(nextEntry) },
                    expanded = expanded,
                    modifier = Modifier.animateFloatingActionButton(selectedIds.isEmpty(), Alignment.BottomEnd),
                )
            }
        },
    )
}

private fun MangaReadProgress?.isComplete(): Boolean =
    this != null && totalChapterCount > 0 && readCount >= totalChapterCount

@Composable
private fun folderEntryTitle(manga: Manga, entry: LocalLibraryItem?, showSize: Boolean): String {
    val size = entry?.sizeBytes?.takeIf { showSize && it > 0 && entry.kind != LocalLibraryItem.Kind.FOLDER }
    return if (size != null) "${manga.title} (${Formatter.formatFileSize(LocalContext.current, size)})" else manga.title
}

@Composable
private fun FolderEmptyContent(refreshing: Boolean, error: String?, filtered: Boolean) {
    Box(Modifier.fillMaxWidth().height(240.dp)) {
        when {
            refreshing -> LoadingScreen()
            error != null -> EmptyScreen(message = error)
            else -> EmptyScreen(
                stringRes = if (filtered) MR.strings.no_results_found else MR.strings.local_library_folder_empty,
            )
        }
    }
}
