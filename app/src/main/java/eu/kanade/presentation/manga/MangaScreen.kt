package eu.kanade.presentation.manga

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastMap
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.library.components.DownloadedCorner
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterHeader
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.manga.components.MangaActionRow
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MangaDetailLayout
import eu.kanade.presentation.manga.components.MangaInfoBox
import eu.kanade.presentation.manga.components.MangaToolbar
import eu.kanade.presentation.manga.components.MissingChapterCountListItem
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import eu.kanade.tachiyomi.ui.reader.pageProgressPercent
import eu.kanade.tachiyomi.util.system.copyToClipboard
import koharia.connection.ConnectionChapterMetadata
import koharia.connection.ConnectionChapterTitleAdapter
import koharia.connection.ConnectionLibraryShelfAdapter
import koharia.connection.ConnectionMangaBehavior
import koharia.connection.ConnectionMangaBehaviorAdapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.missingChaptersCount
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.foundation.lazy.grid.items as gridItems
import tachiyomi.domain.manga.model.MangaCover as MangaCoverModel

@Composable
fun MangaScreen(
    state: MangaScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    isTabletUi: Boolean,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    chapterCoverGridColumns: Int,
    showChapterReadProgress: Boolean,
    showChapterFileSize: Boolean,
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: (() -> Unit)?,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,

    // For tags menu
    onTagSearch: ((String) -> Unit)?,

    onFilterButtonClicked: () -> Unit,
    onChapterCoverDisplayModeChange: (Long) -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditSeriesDetailsClicked: (() -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onEditNotesClicked: () -> Unit,

    // For bottom action menu
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,

    // For chapter swipe
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,

    // Chapter selection
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
) {
    val context = LocalContext.current
    val isConnectionCacheMode = state.source.mangaBehavior().usesCacheTerminology
    val onCopyTagToClipboard: (tag: String) -> Unit = {
        if (it.isNotEmpty()) {
            context.copyToClipboard(it, it)
        }
    }

    MangaScreenImpl(
        isTabletUi = isTabletUi,
        state = state,
        snackbarHostState = snackbarHostState,
        chapterSwipeStartAction = chapterSwipeStartAction,
        chapterSwipeEndAction = chapterSwipeEndAction,
        chapterCoverGridColumns = chapterCoverGridColumns,
        showChapterReadProgress = showChapterReadProgress,
        showChapterFileSize = showChapterFileSize,
        isConnectionCacheMode = isConnectionCacheMode,
        navigateUp = navigateUp,
        onChapterClicked = onChapterClicked,
        onDownloadChapter = onDownloadChapter,
        onAddToLibraryClicked = onAddToLibraryClicked,
        onWebViewClicked = onWebViewClicked,
        onWebViewLongClicked = onWebViewLongClicked,
        onTagSearch = onTagSearch,
        onCopyTagToClipboard = onCopyTagToClipboard,
        onFilterClicked = onFilterButtonClicked,
        onChapterCoverDisplayModeChange = onChapterCoverDisplayModeChange,
        onRefresh = onRefresh,
        onContinueReading = onContinueReading,
        onSearch = onSearch,
        onCoverClicked = onCoverClicked,
        onShareClicked = onShareClicked,
        onDownloadActionClicked = onDownloadActionClicked,
        onEditSeriesDetailsClicked = onEditSeriesDetailsClicked,
        onEditCategoryClicked = onEditCategoryClicked,
        onMigrateClicked = onMigrateClicked,
        onEditNotesClicked = onEditNotesClicked,
        onMultiBookmarkClicked = onMultiBookmarkClicked,
        onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
        onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
        onMultiDeleteClicked = onMultiDeleteClicked,
        onChapterSwipe = onChapterSwipe,
        onChapterSelected = onChapterSelected,
        onAllChapterSelected = onAllChapterSelected,
        onInvertSelection = onInvertSelection,
    )
}

@Composable
private fun MangaScreenImpl(
    isTabletUi: Boolean,
    state: MangaScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    chapterCoverGridColumns: Int,
    showChapterReadProgress: Boolean,
    showChapterFileSize: Boolean,
    isConnectionCacheMode: Boolean,
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: (() -> Unit)?,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,

    // For tags menu
    onTagSearch: ((String) -> Unit)?,
    onCopyTagToClipboard: (tag: String) -> Unit,

    onFilterClicked: () -> Unit,
    onChapterCoverDisplayModeChange: (Long) -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditSeriesDetailsClicked: (() -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onEditNotesClicked: () -> Unit,

    // For bottom action menu
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,

    // For chapter swipe
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,

    // Chapter selection
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
) {
    val serverDownloads = koharia.connection.ui.rememberConnectionServerDownloadActions(
        state.source as? koharia.connection.ConnectionServerDownloadsAdapter,
        state.manga.url,
        snackbarHostState,
    )
    val chapters = state.processedChapters
    val listItem = state.chapterListItems
    val isAnySelected = state.isAnySelected
    BackHandler(enabled = isAnySelected) { onAllChapterSelected(false) }
    MangaDetailLayout(
        isTabletUi = isTabletUi,
        useGrid = state.source.mangaBehavior().supportsChapterCoverGrid &&
            state.manga.chapterCoverDisplayMode != Manga.CHAPTER_COVER_DISPLAY_TEXT,
        columns = chapterCoverGridColumns,
        refreshing = state.isRefreshingData,
        selectionActive = isAnySelected,
        onRefresh = onRefresh,
        snackbarHostState = snackbarHostState,
        toolbar = { titleAlpha, backgroundAlpha ->
            MangaToolbar(
                title = state.manga.title,
                hasFilters = state.filterActive,
                isConnectionCacheMode = isConnectionCacheMode,
                chapterCoverDisplayMode = state.manga.chapterCoverDisplayMode.takeIf {
                    state.source.mangaBehavior().supportsChapterCoverGrid
                },
                navigateUp = navigateUp,
                onClickFilter = onFilterClicked,
                onChapterCoverDisplayModeChange = onChapterCoverDisplayModeChange,
                onClickShare = onShareClicked,
                onClickDownload = onDownloadActionClicked,
                onClickEditSeriesDetails = onEditSeriesDetailsClicked,
                onClickConnectionActions = connectionSeriesActions(state.source, state.manga),
                onClickEditCategory = onEditCategoryClicked,
                editCategoryAsLibraryShelf = state.source is ConnectionLibraryShelfAdapter,
                onClickRefresh = onRefresh,
                onClickMigrate = onMigrateClicked,
                onClickEditNotes = onEditNotesClicked,
                actionModeCounter = chapters.count { it.selected },
                onCancelActionMode = { onAllChapterSelected(false) },
                onSelectAll = { onAllChapterSelected(true) },
                onInvertSelection = onInvertSelection,
                titleAlphaProvider = { titleAlpha },
                backgroundAlphaProvider = { backgroundAlpha },
            )
        },
        info = { tablet, topPadding, bleed ->
            MangaInfoBox(
                isTabletUi = tablet,
                appBarPadding = topPadding,
                manga = state.manga,
                sourceName = if (state.source.mangaBehavior().showSourceName) {
                    state.source.getNameForMangaInfo()
                } else {
                    ""
                },
                isStubSource = state.source is StubSource,
                onCoverClick = onCoverClicked,
                doSearch = onSearch,
                backdropHorizontalBleed = bleed,
            )
        },
        actions = {
            MangaActionRow(
                favorite = state.manga.favorite,
                onAddToLibraryClicked = onAddToLibraryClicked,
                onWebViewClicked = onWebViewClicked,
                onWebViewLongClicked = onWebViewLongClicked,
                onEditCategory = onEditCategoryClicked,
            )
        },
        summary = {
            ExpandableMangaDescription(
                description = state.manga.description,
                tagsProvider = { state.manga.genre },
                notes = state.manga.notes,
                onTagSearch = onTagSearch,
                onCopyTagToClipboard = onCopyTagToClipboard,
                onEditNotes = onEditNotesClicked,
            )
        },
        contentHeader = {
            ChapterHeader(
                enabled = !isAnySelected,
                chapterCount = chapters.size,
                missingChapterCount = if (state.hideMissingChapters) {
                    0
                } else {
                    remember(chapters) {
                        chapters.map { it.chapter.chapterNumber }.missingChaptersCount()
                    }
                },
                onClick = onFilterClicked,
            )
        },
        listContent = {
            sharedChapterItems(
                serverDownloads = serverDownloads,
                manga = state.manga, chapters = listItem, showChapterReadProgress = showChapterReadProgress,
                showChapterFileSize = showChapterFileSize, isConnectionCacheMode = isConnectionCacheMode,
                isAnyChapterSelected = isAnySelected, chapterSwipeStartAction = chapterSwipeStartAction,
                chapterSwipeEndAction = chapterSwipeEndAction, onChapterClicked = onChapterClicked,
                onDownloadChapter = onDownloadChapter, onChapterSelected = onChapterSelected,
                onChapterSwipe = onChapterSwipe,
            )
        },
        gridContent = {
            sharedChapterGridItems(
                serverDownloads = serverDownloads,
                manga = state.manga,
                chapterThumbnailUrl = state.source::connectionChapterThumbnailUrl,
                chapters = listItem,
                showChapterReadProgress = showChapterReadProgress,
                showChapterFileSize = showChapterFileSize,
                isAnyChapterSelected = isAnySelected,
                onChapterClicked = onChapterClicked,
                onChapterSelected = onChapterSelected,
            )
        },
        bottomBar = {
            val organizationAdapter = state.source as? koharia.connection.ConnectionOrganizationActionsAdapter
            val organizationNavigator = cafe.adriel.voyager.navigator.LocalNavigator.current
            SharedMangaBottomActionMenu(
                onOrganizationClicked = organizationAdapter?.let { adapter ->
                    {
                        organizationNavigator?.push(
                            adapter.chapterOrganizationScreen(
                                chapters.filter {
                                    it.selected
                                }.map { it.chapter.url },
                            ),
                        )
                    }
                },
                serverDownloads = serverDownloads,
                selected = chapters.filter { it.selected },
                isConnectionCacheMode = isConnectionCacheMode,
                onMultiBookmarkClicked = onMultiBookmarkClicked,
                onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
                onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
                onDownloadChapter = onDownloadChapter,
                onMultiDeleteClicked = onMultiDeleteClicked,
                fillFraction = if (isTabletUi) 0.5f else 1f,
            )
        },
        floatingActionButton = { expanded ->
            SmallExtendedFloatingActionButton(
                text = {
                    Text(
                        stringResource(
                            if (state.chapters.fastAny { it.chapter.read }) {
                                MR.strings.action_resume
                            } else {
                                MR.strings.action_start
                            },
                        ),
                    )
                },
                icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = onContinueReading,
                expanded = expanded,
                modifier = Modifier.animateFloatingActionButton(
                    visible = chapters.fastAny { !it.chapter.read } && !isAnySelected,
                    alignment = Alignment.BottomEnd,
                ),
            )
        },
    )
}

@Composable
private fun SharedMangaBottomActionMenu(
    onOrganizationClicked: (() -> Unit)?,
    serverDownloads: koharia.connection.ui.ConnectionServerDownloadActions?,
    selected: List<ChapterList.Item>,
    isConnectionCacheMode: Boolean,
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,
    fillFraction: Float,
    modifier: Modifier = Modifier,
) {
    MangaBottomActionMenu(
        onOrganizationClicked = onOrganizationClicked,
        onServerDownloadClicked = serverDownloads?.takeUnless { it.busy }?.let { actions ->
            { actions.enqueue(selected.map { it.chapter.url }) }
        },
        visible = selected.isNotEmpty(),
        modifier = modifier.fillMaxWidth(fillFraction),
        onBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected.fastMap { it.chapter }, true)
        }.takeIf { selected.fastAny { !it.chapter.bookmark } },
        onRemoveBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected.fastMap { it.chapter }, false)
        }.takeIf { selected.fastAll { it.chapter.bookmark } },
        onMarkAsReadClicked = {
            onMultiMarkAsReadClicked(selected.fastMap { it.chapter }, true)
        }.takeIf { selected.fastAny { !it.chapter.read } },
        onMarkAsUnreadClicked = {
            onMultiMarkAsReadClicked(selected.fastMap { it.chapter }, false)
        }.takeIf { selected.fastAny { it.chapter.read || it.chapter.lastPageRead > 0L } },
        onMarkPreviousAsReadClicked = {
            onMarkPreviousAsReadClicked(selected[0].chapter)
        }.takeIf { selected.size == 1 },
        onDownloadClicked = {
            onDownloadChapter!!(selected.toList(), ChapterDownloadAction.START)
        }.takeIf {
            onDownloadChapter != null && selected.fastAny { it.downloadState != Download.State.DOWNLOADED }
        },
        onDeleteClicked = {
            onMultiDeleteClicked(selected.fastMap { it.chapter })
        }.takeIf {
            selected.fastAny { it.downloadState == Download.State.DOWNLOADED }
        },
        isConnectionCacheMode = isConnectionCacheMode,
    )
}

private fun LazyListScope.sharedChapterItems(
    serverDownloads: koharia.connection.ui.ConnectionServerDownloadActions?,
    manga: Manga,
    chapters: List<ChapterList>,
    showChapterReadProgress: Boolean,
    showChapterFileSize: Boolean,
    isConnectionCacheMode: Boolean,
    isAnyChapterSelected: Boolean,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,
) {
    items(
        items = chapters,
        key = { item ->
            when (item) {
                is ChapterList.MissingCount -> "missing-count-${item.id}"
                is ChapterList.Item -> "chapter-${item.id}"
            }
        },
        contentType = { MangaScreenItem.CHAPTER },
    ) { item ->
        val haptic = LocalHapticFeedback.current

        when (item) {
            is ChapterList.MissingCount -> {
                MissingChapterCountListItem(count = item.count)
            }
            is ChapterList.Item -> {
                MangaChapterListItem(
                    serverDownloadAction = serverDownloads?.let { actions ->
                        { actions.ChapterButton(item.chapter.url, !isAnyChapterSelected) }
                    },
                    title = chapterTitleWithFileSize(manga, item, showChapterFileSize),
                    date = relativeDateText(item.chapter.dateUpload),
                    readProgress = chapterReadProgress(item).takeIf { showChapterReadProgress },
                    scanlator = item.chapter.scanlator.takeIf { !it.isNullOrBlank() },
                    read = item.chapter.read,
                    showReadStatus = showChapterReadProgress,
                    bookmark = item.chapter.bookmark,
                    selected = item.selected,
                    downloadIndicatorEnabled = !isAnyChapterSelected,
                    isConnectionCacheMode = isConnectionCacheMode,
                    downloadStateProvider = { item.downloadState },
                    downloadProgressProvider = { item.downloadProgress },
                    chapterSwipeStartAction = chapterSwipeStartAction,
                    chapterSwipeEndAction = chapterSwipeEndAction,
                    onLongClick = {
                        onChapterSelected(item, !item.selected, true)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onClick = {
                        onChapterItemClick(
                            chapterItem = item,
                            isAnyChapterSelected = isAnyChapterSelected,
                            onToggleSelection = { onChapterSelected(item, !item.selected, false) },
                            onChapterClicked = onChapterClicked,
                        )
                    },
                    onDownloadClick = if (onDownloadChapter != null) {
                        { onDownloadChapter(listOf(item), it) }
                    } else {
                        null
                    },
                    onChapterSwipe = {
                        onChapterSwipe(item, it)
                    },
                )
            }
        }
    }
}

private fun LazyGridScope.sharedChapterGridItems(
    serverDownloads: koharia.connection.ui.ConnectionServerDownloadActions?,
    manga: Manga,
    chapterThumbnailUrl: (String) -> String?,
    chapters: List<ChapterList>,
    showChapterReadProgress: Boolean,
    showChapterFileSize: Boolean,
    isAnyChapterSelected: Boolean,
    onChapterClicked: (Chapter) -> Unit,
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
) {
    gridItems(
        items = chapters,
        key = { item ->
            when (item) {
                is ChapterList.MissingCount -> "missing-count-${item.id}"
                is ChapterList.Item -> "chapter-${item.id}"
            }
        },
        span = { item ->
            when (item) {
                is ChapterList.MissingCount -> GridItemSpan(maxLineSpan)
                is ChapterList.Item -> GridItemSpan(1)
            }
        },
        contentType = { MangaScreenItem.CHAPTER },
    ) { item ->
        val haptic = LocalHapticFeedback.current

        when (item) {
            is ChapterList.MissingCount -> {
                MissingChapterCountListItem(count = item.count)
            }
            is ChapterList.Item -> {
                val coverData = chapterThumbnailUrl(item.chapter.url)?.let { thumbnailUrl ->
                    MangaCoverModel(
                        mangaId = item.chapter.id,
                        sourceId = manga.source,
                        isMangaFavorite = false,
                        url = thumbnailUrl,
                        lastModified = item.chapter.dateUpload,
                        useCustomCover = false,
                    )
                }
                val onLongClick = {
                    onChapterSelected(item, !item.selected, true)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                val onClick = {
                    onChapterItemClick(
                        chapterItem = item,
                        isAnyChapterSelected = isAnyChapterSelected,
                        onToggleSelection = { onChapterSelected(item, !item.selected, false) },
                        onChapterClicked = onChapterClicked,
                    )
                }
                val readProgress = chapterGridProgress(item)
                    .takeIf { showChapterReadProgress }
                val isRead = showChapterReadProgress && item.chapter.read
                val isDownloaded = item.downloadState == Download.State.DOWNLOADED
                val coverOverlay: (@Composable BoxScope.() -> Unit)? = if (readProgress != null || isRead ||
                    isDownloaded || serverDownloads != null
                ) {
                    {
                        when {
                            readProgress != null -> {
                                ChapterProgressCorner(
                                    progress = readProgress,
                                    modifier = Modifier.align(Alignment.TopEnd),
                                )
                            }
                            isRead -> ChapterReadCorner(modifier = Modifier.align(Alignment.TopEnd))
                        }
                        if (serverDownloads != null) {
                            Box(Modifier.align(Alignment.BottomStart)) {
                                serverDownloads.ChapterButton(item.chapter.url, !isAnyChapterSelected)
                            }
                        }
                        if (isDownloaded) {
                            DownloadedCorner(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp),
                            )
                        }
                    }
                } else {
                    null
                }

                if (manga.chapterCoverDisplayMode == Manga.CHAPTER_COVER_DISPLAY_COMFORTABLE) {
                    MangaComfortableGridItem(
                        coverData = coverData,
                        title = chapterTitleWithFileSize(manga, item, showChapterFileSize),
                        isSelected = item.selected,
                        coverOverlay = coverOverlay,
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                } else {
                    MangaCompactGridItem(
                        coverData = coverData,
                        title = if (
                            manga.chapterCoverDisplayMode == Manga.CHAPTER_COVER_DISPLAY_COVER_AND_TITLE
                        ) {
                            chapterTitleWithFileSize(manga, item, showChapterFileSize)
                        } else {
                            null
                        },
                        isSelected = item.selected,
                        coverOverlay = coverOverlay,
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                }
            }
        }
    }
}

private fun Source.mangaBehavior(): ConnectionMangaBehavior {
    return (this as? ConnectionMangaBehaviorAdapter)?.mangaBehavior ?: ConnectionMangaBehavior.Default
}

private fun Source.connectionChapterThumbnailUrl(chapterUrl: String): String? {
    return (this as? ConnectionMangaBehaviorAdapter)?.chapterThumbnailUrl(chapterUrl)
}

@Composable
internal fun ChapterProgressCorner(
    progress: String,
    modifier: Modifier = Modifier,
) {
    val cornerColor = MaterialTheme.colorScheme.primaryContainer
    val progressColor = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.size(CHAPTER_STATUS_CORNER_SIZE)) {
        ChapterStatusCornerBackground(color = cornerColor)
        Text(
            text = progress,
            color = progressColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                lineHeight = 9.sp,
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-1).dp, y = 5.dp)
                .rotate(45f),
        )
    }
}

@Composable
private fun ChapterStatusCornerBackground(color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawPath(
            path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                close()
            },
            color = color,
        )
    }
}

private val CHAPTER_STATUS_CORNER_SIZE = 32.dp

@Composable
internal fun ChapterReadCorner(modifier: Modifier = Modifier) {
    val cornerColor = MaterialTheme.colorScheme.primaryContainer
    val checkColor = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.size(CHAPTER_STATUS_CORNER_SIZE)) {
        ChapterStatusCornerBackground(color = cornerColor)
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = checkColor,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 2.dp, end = 2.dp)
                .size(14.dp),
        )
    }
}

@Composable
private fun chapterReadProgress(
    item: ChapterList.Item,
): String? {
    if (item.chapter.read) return null

    item.epubProgressPercent
        ?.takeIf { it > 0 }
        ?.let { progress ->
            return stringResource(
                MR.strings.epub_chapter_progress,
                progress,
            )
        }

    return item.chapter.lastPageRead
        .takeIf { it > 0L }
        ?.let { lastPageRead ->
            stringResource(
                MR.strings.chapter_progress,
                lastPageRead + 1,
            )
        }
}

@Composable
private fun chapterGridProgress(item: ChapterList.Item): String? {
    if (item.chapter.read) return null

    val progressPercent = item.epubProgressPercent
        ?.takeIf { it > 0 }
        ?: run {
            val totalPages = ConnectionChapterMetadata.pagesCount(item.chapter.memo)
                ?: return chapterReadProgress(item)
            item.chapter.lastPageRead
                .takeIf { it > 0L }
                ?.let { pageIndex -> pageProgressPercent(pageIndex.toInt(), totalPages) }
                ?.takeIf { it > 0 }
        }

    return progressPercent?.let { progress ->
        stringResource(MR.strings.epub_chapter_progress_short, progress)
    }
}

@Composable
private fun chapterTitle(
    manga: Manga,
    item: ChapterList.Item,
): String {
    val adapter = Injekt.get<tachiyomi.domain.source.service.SourceManager>()
        .get(manga.source) as? ConnectionChapterTitleAdapter
    val memo = item.chapter.memo
    val sourceTitle = adapter?.detailsChapterTitle(memo)
        ?: item.chapter.name.withoutEmbeddedFileSize(memo)
    return when (manga.displayMode) {
        Manga.CHAPTER_DISPLAY_NUMBER -> adapter?.detailsChapterNumber(memo) ?: stringResource(
            MR.strings.display_mode_chapter,
            formatChapterNumber(item.chapter.chapterNumber),
        )
        Manga.CHAPTER_DISPLAY_FILE_NAME -> adapter?.detailsChapterFileName(memo) ?: sourceTitle
        else -> sourceTitle
    }
}

@Composable
private fun chapterTitleWithFileSize(
    manga: Manga,
    item: ChapterList.Item,
    showChapterFileSize: Boolean,
): String {
    val title = chapterTitle(manga, item)
    val fileSize = chapterFileSize(item).takeIf { showChapterFileSize } ?: return title
    return "$title ($fileSize)"
}

@Composable
private fun chapterFileSize(item: ChapterList.Item): String? {
    val sizeBytes = ConnectionChapterMetadata.sizeBytes(item.chapter.memo) ?: return null
    val context = LocalContext.current
    return remember(sizeBytes, context) {
        Formatter.formatFileSize(context, sizeBytes)
    }
}

private fun String.withoutEmbeddedFileSize(memo: kotlinx.serialization.json.JsonObject): String {
    return ConnectionChapterMetadata.removeTrailingEmbeddedFileSize(this, memo)
}

private fun onChapterItemClick(
    chapterItem: ChapterList.Item,
    isAnyChapterSelected: Boolean,
    onToggleSelection: (Boolean) -> Unit,
    onChapterClicked: (Chapter) -> Unit,
) {
    when {
        chapterItem.selected -> onToggleSelection(false)
        isAnyChapterSelected -> onToggleSelection(true)
        else -> onChapterClicked(chapterItem.chapter)
    }
}

@Composable
private fun connectionSeriesActions(source: Source, manga: Manga): (() -> Unit)? {
    val adapter = source as? koharia.connection.ConnectionSeriesActionsAdapter ?: return null
    val navigator = cafe.adriel.voyager.navigator.LocalNavigator.current ?: return null
    return { navigator.push(adapter.seriesActionsScreen(manga)) }
}
