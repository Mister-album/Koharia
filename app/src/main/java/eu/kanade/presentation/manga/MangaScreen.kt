package eu.kanade.presentation.manga

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastMap
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterHeader
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.manga.components.MangaActionRow
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MangaInfoBox
import eu.kanade.presentation.manga.components.MangaToolbar
import eu.kanade.presentation.manga.components.MissingChapterCountListItem
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import eu.kanade.tachiyomi.util.system.copyToClipboard
import koharia.source.komga.KomgaSource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.missingChaptersCount
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.shouldExpandFAB
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
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: (() -> Unit)?,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,

    // For tags menu
    onTagSearch: (String) -> Unit,

    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
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
    val isKomgaCacheMode = state.source.id == KomgaSource.ID
    val onCopyTagToClipboard: (tag: String) -> Unit = {
        if (it.isNotEmpty()) {
            context.copyToClipboard(it, it)
        }
    }

    if (!isTabletUi) {
        MangaScreenSmallImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            chapterSwipeStartAction = chapterSwipeStartAction,
            chapterSwipeEndAction = chapterSwipeEndAction,
            chapterCoverGridColumns = chapterCoverGridColumns,
            isKomgaCacheMode = isKomgaCacheMode,
            navigateUp = navigateUp,
            onChapterClicked = onChapterClicked,
            onDownloadChapter = onDownloadChapter,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onFilterClicked = onFilterButtonClicked,
            onRefresh = onRefresh,
            onContinueReading = onContinueReading,
            onSearch = onSearch,
            onCoverClicked = onCoverClicked,
            onShareClicked = onShareClicked,
            onDownloadActionClicked = onDownloadActionClicked,
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
    } else {
        MangaScreenLargeImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            chapterSwipeStartAction = chapterSwipeStartAction,
            chapterSwipeEndAction = chapterSwipeEndAction,
            chapterCoverGridColumns = chapterCoverGridColumns,
            isKomgaCacheMode = isKomgaCacheMode,
            navigateUp = navigateUp,
            onChapterClicked = onChapterClicked,
            onDownloadChapter = onDownloadChapter,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onFilterButtonClicked = onFilterButtonClicked,
            onRefresh = onRefresh,
            onContinueReading = onContinueReading,
            onSearch = onSearch,
            onCoverClicked = onCoverClicked,
            onShareClicked = onShareClicked,
            onDownloadActionClicked = onDownloadActionClicked,
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
}

@Composable
private fun MangaScreenSmallImpl(
    state: MangaScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    chapterCoverGridColumns: Int,
    isKomgaCacheMode: Boolean,
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: (() -> Unit)?,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,

    // For tags menu
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,

    onFilterClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
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
    val chapterListState = rememberLazyListState()
    val chapterGridState = rememberLazyGridState()
    val useChapterCoverGrid = state.manga.source == KomgaSource.ID &&
        state.manga.chapterCoverDisplayMode != Manga.CHAPTER_COVER_DISPLAY_TEXT

    val (chapters, listItem, isAnySelected) = remember(state) {
        Triple(
            first = state.processedChapters,
            second = state.chapterListItems,
            third = state.isAnySelected,
        )
    }

    BackHandler(enabled = isAnySelected) {
        onAllChapterSelected(false)
    }

    Scaffold(
        topBar = {
            val selectedChapterCount: Int = remember(chapters) {
                chapters.count { it.selected }
            }
            val isFirstItemVisible by remember(useChapterCoverGrid) {
                derivedStateOf {
                    if (useChapterCoverGrid) {
                        chapterGridState.firstVisibleItemIndex == 0
                    } else {
                        chapterListState.firstVisibleItemIndex == 0
                    }
                }
            }
            val isFirstItemScrolled by remember(useChapterCoverGrid) {
                derivedStateOf {
                    if (useChapterCoverGrid) {
                        chapterGridState.firstVisibleItemScrollOffset > 0
                    } else {
                        chapterListState.firstVisibleItemScrollOffset > 0
                    }
                }
            }
            val titleAlpha by animateFloatAsState(
                if (!isFirstItemVisible) 1f else 0f,
                label = "Top Bar Title",
            )
            val backgroundAlpha by animateFloatAsState(
                if (!isFirstItemVisible || isFirstItemScrolled) 1f else 0f,
                label = "Top Bar Background",
            )
            MangaToolbar(
                title = state.manga.title,
                hasFilters = state.filterActive,
                isKomgaCacheMode = isKomgaCacheMode,
                navigateUp = navigateUp,
                onClickFilter = onFilterClicked,
                onClickShare = onShareClicked,
                onClickDownload = onDownloadActionClicked,
                onClickEditCategory = onEditCategoryClicked,
                onClickRefresh = onRefresh,
                onClickMigrate = onMigrateClicked,
                onClickEditNotes = onEditNotesClicked,
                actionModeCounter = selectedChapterCount,
                onCancelActionMode = { onAllChapterSelected(false) },
                onSelectAll = { onAllChapterSelected(true) },
                onInvertSelection = { onInvertSelection() },
                titleAlphaProvider = { titleAlpha },
                backgroundAlphaProvider = { backgroundAlpha },
            )
        },
        bottomBar = {
            val selectedChapters = remember(chapters) {
                chapters.filter { it.selected }
            }
            SharedMangaBottomActionMenu(
                selected = selectedChapters,
                isKomgaCacheMode = isKomgaCacheMode,
                onMultiBookmarkClicked = onMultiBookmarkClicked,
                onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
                onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
                onDownloadChapter = onDownloadChapter,
                onMultiDeleteClicked = onMultiDeleteClicked,
                fillFraction = 1f,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            val isFABVisible = remember(chapters) {
                chapters.fastAny { !it.chapter.read } && !isAnySelected
            }
            SmallExtendedFloatingActionButton(
                text = {
                    val isReading = remember(state.chapters) {
                        state.chapters.fastAny { it.chapter.read }
                    }
                    Text(
                        text = stringResource(if (isReading) MR.strings.action_resume else MR.strings.action_start),
                    )
                },
                icon = { Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = onContinueReading,
                expanded = if (useChapterCoverGrid) {
                    chapterGridState.lastScrolledBackward ||
                        !chapterGridState.canScrollForward ||
                        !chapterGridState.canScrollBackward
                } else {
                    chapterListState.shouldExpandFAB()
                },
                modifier = Modifier.animateFloatingActionButton(
                    visible = isFABVisible,
                    alignment = Alignment.BottomEnd,
                ),
            )
        },
    ) { contentPadding ->
        val topPadding = contentPadding.calculateTopPadding()

        PullRefresh(
            refreshing = state.isRefreshingData,
            onRefresh = onRefresh,
            enabled = !isAnySelected,
            indicatorPadding = PaddingValues(top = topPadding),
        ) {
            val layoutDirection = LocalLayoutDirection.current
            if (useChapterCoverGrid) {
                FastScrollLazyVerticalGrid(
                    columns = GridCells.Fixed(chapterCoverGridColumns.coerceIn(2, 6)),
                    modifier = Modifier.fillMaxHeight(),
                    state = chapterGridState,
                    contentPadding = PaddingValues(
                        start = contentPadding.calculateStartPadding(layoutDirection) + 12.dp,
                        end = contentPadding.calculateEndPadding(layoutDirection) + 12.dp,
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                    topContentPadding = topPadding,
                    endContentPadding = contentPadding.calculateEndPadding(layoutDirection),
                    verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
                    horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
                ) {
                    sharedMangaDetailHeaderGridItems(
                        state = state,
                        topPadding = topPadding,
                        isAnySelected = isAnySelected,
                        chapters = chapters,
                        onAddToLibraryClicked = onAddToLibraryClicked,
                        onWebViewClicked = onWebViewClicked,
                        onWebViewLongClicked = onWebViewLongClicked,
                        onEditCategoryClicked = onEditCategoryClicked,
                        onSearch = onSearch,
                        onCoverClicked = onCoverClicked,
                        onTagSearch = onTagSearch,
                        onCopyTagToClipboard = onCopyTagToClipboard,
                        onEditNotesClicked = onEditNotesClicked,
                        onFilterClicked = onFilterClicked,
                    )
                    sharedChapterGridItems(
                        manga = state.manga,
                        chapters = listItem,
                        isAnyChapterSelected = chapters.fastAny { it.selected },
                        onChapterClicked = onChapterClicked,
                        onChapterSelected = onChapterSelected,
                    )
                }
            } else {
                VerticalFastScroller(
                    listState = chapterListState,
                    topContentPadding = topPadding,
                    endContentPadding = contentPadding.calculateEndPadding(layoutDirection),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxHeight(),
                        state = chapterListState,
                        contentPadding = PaddingValues(
                            start = contentPadding.calculateStartPadding(layoutDirection),
                            end = contentPadding.calculateEndPadding(layoutDirection),
                            bottom = contentPadding.calculateBottomPadding(),
                        ),
                    ) {
                        sharedMangaDetailHeaderListItems(
                            state = state,
                            topPadding = topPadding,
                            isAnySelected = isAnySelected,
                            chapters = chapters,
                            onAddToLibraryClicked = onAddToLibraryClicked,
                            onWebViewClicked = onWebViewClicked,
                            onWebViewLongClicked = onWebViewLongClicked,
                            onEditCategoryClicked = onEditCategoryClicked,
                            onSearch = onSearch,
                            onCoverClicked = onCoverClicked,
                            onTagSearch = onTagSearch,
                            onCopyTagToClipboard = onCopyTagToClipboard,
                            onEditNotesClicked = onEditNotesClicked,
                            onFilterClicked = onFilterClicked,
                        )

                        sharedChapterItems(
                            manga = state.manga,
                            chapters = listItem,
                            isKomgaCacheMode = isKomgaCacheMode,
                            isAnyChapterSelected = chapters.fastAny { it.selected },
                            chapterSwipeStartAction = chapterSwipeStartAction,
                            chapterSwipeEndAction = chapterSwipeEndAction,
                            onChapterClicked = onChapterClicked,
                            onDownloadChapter = onDownloadChapter,
                            onChapterSelected = onChapterSelected,
                            onChapterSwipe = onChapterSwipe,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MangaScreenLargeImpl(
    state: MangaScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    chapterCoverGridColumns: Int,
    isKomgaCacheMode: Boolean,
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: (() -> Unit)?,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,

    // For tags menu
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,

    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onEditNotesClicked: () -> Unit,

    // For bottom action menu
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,

    // For swipe actions
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,

    // Chapter selection
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val useChapterCoverGrid = state.manga.source == KomgaSource.ID &&
        state.manga.chapterCoverDisplayMode != Manga.CHAPTER_COVER_DISPLAY_TEXT

    val (chapters, listItem, isAnySelected) = remember(state) {
        Triple(
            first = state.processedChapters,
            second = state.chapterListItems,
            third = state.isAnySelected,
        )
    }

    val insetPadding = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal).asPaddingValues()
    var topBarHeight by remember { mutableIntStateOf(0) }

    val chapterListState = rememberLazyListState()
    val chapterGridState = rememberLazyGridState()

    BackHandler(enabled = isAnySelected) {
        onAllChapterSelected(false)
    }

    Scaffold(
        topBar = {
            val selectedChapterCount = remember(chapters) {
                chapters.count { it.selected }
            }
            MangaToolbar(
                modifier = Modifier.onSizeChanged { topBarHeight = it.height },
                title = state.manga.title,
                hasFilters = state.filterActive,
                isKomgaCacheMode = isKomgaCacheMode,
                navigateUp = navigateUp,
                onClickFilter = onFilterButtonClicked,
                onClickShare = onShareClicked,
                onClickDownload = onDownloadActionClicked,
                onClickEditCategory = onEditCategoryClicked,
                onClickRefresh = onRefresh,
                onClickMigrate = onMigrateClicked,
                onClickEditNotes = onEditNotesClicked,
                onCancelActionMode = { onAllChapterSelected(false) },
                actionModeCounter = selectedChapterCount,
                onSelectAll = { onAllChapterSelected(true) },
                onInvertSelection = { onInvertSelection() },
                titleAlphaProvider = { 1f },
                backgroundAlphaProvider = { 1f },
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomEnd,
            ) {
                val selectedChapters = remember(chapters) {
                    chapters.filter { it.selected }
                }
                SharedMangaBottomActionMenu(
                    selected = selectedChapters,
                    isKomgaCacheMode = isKomgaCacheMode,
                    onMultiBookmarkClicked = onMultiBookmarkClicked,
                    onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
                    onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
                    onDownloadChapter = onDownloadChapter,
                    onMultiDeleteClicked = onMultiDeleteClicked,
                    fillFraction = 0.5f,
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            val isFABVisible = remember(chapters) {
                chapters.fastAny { !it.chapter.read } && !isAnySelected
            }
            SmallExtendedFloatingActionButton(
                text = {
                    val isReading = remember(state.chapters) {
                        state.chapters.fastAny { it.chapter.read }
                    }
                    Text(
                        text = stringResource(
                            if (isReading) MR.strings.action_resume else MR.strings.action_start,
                        ),
                    )
                },
                icon = { Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = onContinueReading,
                expanded = if (useChapterCoverGrid) {
                    chapterGridState.lastScrolledBackward ||
                        !chapterGridState.canScrollForward ||
                        !chapterGridState.canScrollBackward
                } else {
                    chapterListState.shouldExpandFAB()
                },
                modifier = Modifier.animateFloatingActionButton(
                    visible = isFABVisible,
                    alignment = Alignment.BottomEnd,
                ),
            )
        },
    ) { contentPadding ->
        PullRefresh(
            refreshing = state.isRefreshingData,
            onRefresh = onRefresh,
            enabled = !isAnySelected,
            indicatorPadding = PaddingValues(
                start = insetPadding.calculateStartPadding(layoutDirection),
                top = with(density) { topBarHeight.toDp() },
                end = insetPadding.calculateEndPadding(layoutDirection),
            ),
        ) {
            TwoPanelBox(
                modifier = Modifier.padding(
                    start = contentPadding.calculateStartPadding(layoutDirection),
                    end = contentPadding.calculateEndPadding(layoutDirection),
                ),
                startContent = {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = contentPadding.calculateBottomPadding()),
                    ) {
                        MangaInfoBox(
                            isTabletUi = true,
                            appBarPadding = contentPadding.calculateTopPadding(),
                            manga = state.manga,
                            sourceName = remember {
                                if (state.source.id == KomgaSource.ID) "" else state.source.getNameForMangaInfo()
                            },
                            isStubSource = remember { state.source is StubSource },
                            onCoverClick = onCoverClicked,
                            doSearch = onSearch,
                        )
                        MangaActionRow(
                            favorite = state.manga.favorite,
                            onAddToLibraryClicked = onAddToLibraryClicked,
                            onWebViewClicked = onWebViewClicked,
                            onWebViewLongClicked = onWebViewLongClicked,
                            onEditCategory = onEditCategoryClicked,
                        )
                        ExpandableMangaDescription(
                            defaultExpandState = true,
                            description = state.manga.description,
                            tagsProvider = { state.manga.genre },
                            notes = state.manga.notes,
                            onTagSearch = onTagSearch,
                            onCopyTagToClipboard = onCopyTagToClipboard,
                            onEditNotes = onEditNotesClicked,
                        )
                    }
                },
                endContent = {
                    if (useChapterCoverGrid) {
                        FastScrollLazyVerticalGrid(
                            columns = GridCells.Fixed(chapterCoverGridColumns.coerceIn(2, 6)),
                            modifier = Modifier.fillMaxHeight(),
                            state = chapterGridState,
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                top = contentPadding.calculateTopPadding(),
                                end = 12.dp,
                                bottom = contentPadding.calculateBottomPadding(),
                            ),
                            topContentPadding = contentPadding.calculateTopPadding(),
                            verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
                            horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
                        ) {
                            chapterHeaderGridItem(
                                enabled = !isAnySelected,
                                chapters = chapters,
                                onClick = onFilterButtonClicked,
                            )
                            sharedChapterGridItems(
                                manga = state.manga,
                                chapters = listItem,
                                isAnyChapterSelected = chapters.fastAny { it.selected },
                                onChapterClicked = onChapterClicked,
                                onChapterSelected = onChapterSelected,
                            )
                        }
                    } else {
                        VerticalFastScroller(
                            listState = chapterListState,
                            topContentPadding = contentPadding.calculateTopPadding(),
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxHeight(),
                                state = chapterListState,
                                contentPadding = PaddingValues(
                                    top = contentPadding.calculateTopPadding(),
                                    bottom = contentPadding.calculateBottomPadding(),
                                ),
                            ) {
                                chapterHeaderListItem(
                                    enabled = !isAnySelected,
                                    chapters = chapters,
                                    onClick = onFilterButtonClicked,
                                )

                                sharedChapterItems(
                                    manga = state.manga,
                                    chapters = listItem,
                                    isKomgaCacheMode = isKomgaCacheMode,
                                    isAnyChapterSelected = chapters.fastAny { it.selected },
                                    chapterSwipeStartAction = chapterSwipeStartAction,
                                    chapterSwipeEndAction = chapterSwipeEndAction,
                                    onChapterClicked = onChapterClicked,
                                    onDownloadChapter = onDownloadChapter,
                                    onChapterSelected = onChapterSelected,
                                    onChapterSwipe = onChapterSwipe,
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun SharedMangaBottomActionMenu(
    selected: List<ChapterList.Item>,
    isKomgaCacheMode: Boolean,
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,
    fillFraction: Float,
    modifier: Modifier = Modifier,
) {
    MangaBottomActionMenu(
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
        isKomgaCacheMode = isKomgaCacheMode,
    )
}

private fun LazyListScope.sharedChapterItems(
    manga: Manga,
    chapters: List<ChapterList>,
    isKomgaCacheMode: Boolean,
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
                    title = if (manga.displayMode == Manga.CHAPTER_DISPLAY_NUMBER) {
                        stringResource(
                            MR.strings.display_mode_chapter,
                            formatChapterNumber(item.chapter.chapterNumber),
                        )
                    } else {
                        item.chapter.name
                    },
                    date = relativeDateText(item.chapter.dateUpload),
                    readProgress = item.chapter.lastPageRead
                        .takeIf { !item.chapter.read && it > 0L }
                        ?.let {
                            stringResource(
                                MR.strings.chapter_progress,
                                it + 1,
                            )
                        },
                    scanlator = item.chapter.scanlator.takeIf { !it.isNullOrBlank() },
                    read = item.chapter.read,
                    bookmark = item.chapter.bookmark,
                    selected = item.selected,
                    downloadIndicatorEnabled = !isAnyChapterSelected,
                    isKomgaCacheMode = isKomgaCacheMode,
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

private fun LazyListScope.sharedMangaDetailHeaderListItems(
    state: MangaScreenModel.State.Success,
    topPadding: androidx.compose.ui.unit.Dp,
    isAnySelected: Boolean,
    chapters: List<ChapterList.Item>,
    onAddToLibraryClicked: (() -> Unit)?,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onSearch: (query: String, global: Boolean) -> Unit,
    onCoverClicked: () -> Unit,
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,
    onEditNotesClicked: () -> Unit,
    onFilterClicked: () -> Unit,
) {
    item(
        key = MangaScreenItem.INFO_BOX,
        contentType = MangaScreenItem.INFO_BOX,
    ) {
        MangaInfoBox(
            isTabletUi = false,
            appBarPadding = topPadding,
            manga = state.manga,
            sourceName = remember {
                if (state.source.id == KomgaSource.ID) "" else state.source.getNameForMangaInfo()
            },
            isStubSource = remember { state.source is StubSource },
            onCoverClick = onCoverClicked,
            doSearch = onSearch,
        )
    }

    item(
        key = MangaScreenItem.ACTION_ROW,
        contentType = MangaScreenItem.ACTION_ROW,
    ) {
        MangaActionRow(
            favorite = state.manga.favorite,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onEditCategory = onEditCategoryClicked,
        )
    }

    item(
        key = MangaScreenItem.DESCRIPTION_WITH_TAG,
        contentType = MangaScreenItem.DESCRIPTION_WITH_TAG,
    ) {
        ExpandableMangaDescription(
            defaultExpandState = state.isFromSource,
            description = state.manga.description,
            tagsProvider = { state.manga.genre },
            notes = state.manga.notes,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onEditNotes = onEditNotesClicked,
        )
    }

    chapterHeaderListItem(
        enabled = !isAnySelected,
        chapters = chapters,
        onClick = onFilterClicked,
    )
}

private fun LazyGridScope.sharedMangaDetailHeaderGridItems(
    state: MangaScreenModel.State.Success,
    topPadding: androidx.compose.ui.unit.Dp,
    isAnySelected: Boolean,
    chapters: List<ChapterList.Item>,
    onAddToLibraryClicked: (() -> Unit)?,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onSearch: (query: String, global: Boolean) -> Unit,
    onCoverClicked: () -> Unit,
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,
    onEditNotesClicked: () -> Unit,
    onFilterClicked: () -> Unit,
) {
    item(
        key = MangaScreenItem.INFO_BOX,
        span = { GridItemSpan(maxLineSpan) },
        contentType = MangaScreenItem.INFO_BOX,
    ) {
        MangaInfoBox(
            isTabletUi = false,
            appBarPadding = topPadding,
            manga = state.manga,
            sourceName = remember {
                if (state.source.id == KomgaSource.ID) "" else state.source.getNameForMangaInfo()
            },
            isStubSource = remember { state.source is StubSource },
            onCoverClick = onCoverClicked,
            doSearch = onSearch,
        )
    }

    item(
        key = MangaScreenItem.ACTION_ROW,
        span = { GridItemSpan(maxLineSpan) },
        contentType = MangaScreenItem.ACTION_ROW,
    ) {
        MangaActionRow(
            favorite = state.manga.favorite,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onEditCategory = onEditCategoryClicked,
        )
    }

    item(
        key = MangaScreenItem.DESCRIPTION_WITH_TAG,
        span = { GridItemSpan(maxLineSpan) },
        contentType = MangaScreenItem.DESCRIPTION_WITH_TAG,
    ) {
        ExpandableMangaDescription(
            defaultExpandState = state.isFromSource,
            description = state.manga.description,
            tagsProvider = { state.manga.genre },
            notes = state.manga.notes,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onEditNotes = onEditNotesClicked,
        )
    }

    chapterHeaderGridItem(
        enabled = !isAnySelected,
        chapters = chapters,
        onClick = onFilterClicked,
    )
}

private fun LazyListScope.chapterHeaderListItem(
    enabled: Boolean,
    chapters: List<ChapterList.Item>,
    onClick: () -> Unit,
) {
    item(
        key = MangaScreenItem.CHAPTER_HEADER,
        contentType = MangaScreenItem.CHAPTER_HEADER,
    ) {
        val missingChapterCount = remember(chapters) {
            chapters.map { it.chapter.chapterNumber }.missingChaptersCount()
        }
        ChapterHeader(
            enabled = enabled,
            chapterCount = chapters.size,
            missingChapterCount = missingChapterCount,
            onClick = onClick,
        )
    }
}

private fun LazyGridScope.chapterHeaderGridItem(
    enabled: Boolean,
    chapters: List<ChapterList.Item>,
    onClick: () -> Unit,
) {
    item(
        key = MangaScreenItem.CHAPTER_HEADER,
        span = { GridItemSpan(maxLineSpan) },
        contentType = MangaScreenItem.CHAPTER_HEADER,
    ) {
        val missingChapterCount = remember(chapters) {
            chapters.map { it.chapter.chapterNumber }.missingChaptersCount()
        }
        ChapterHeader(
            enabled = enabled,
            chapterCount = chapters.size,
            missingChapterCount = missingChapterCount,
            onClick = onClick,
        )
    }
}

private fun LazyGridScope.sharedChapterGridItems(
    manga: Manga,
    chapters: List<ChapterList>,
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
                MangaCompactGridItem(
                    coverData = MangaCoverModel(
                        mangaId = item.chapter.id,
                        sourceId = manga.source,
                        isMangaFavorite = false,
                        url = item.chapter.url
                            .takeIf { manga.source == KomgaSource.ID }
                            ?.let { "$it/thumbnail" },
                        lastModified = item.chapter.dateUpload,
                    ),
                    title = if (manga.chapterCoverDisplayMode == Manga.CHAPTER_COVER_DISPLAY_COVER_AND_TITLE) {
                        chapterTitle(manga, item)
                    } else {
                        null
                    },
                    isSelected = item.selected,
                    coverAlpha = if (item.chapter.read) 0.38f else 1f,
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
                )
            }
        }
    }
}

@Composable
private fun chapterTitle(
    manga: Manga,
    item: ChapterList.Item,
): String {
    return if (manga.displayMode == Manga.CHAPTER_DISPLAY_NUMBER) {
        stringResource(
            MR.strings.display_mode_chapter,
            formatChapterNumber(item.chapter.chapterNumber),
        )
    } else {
        item.chapter.name
    }
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
