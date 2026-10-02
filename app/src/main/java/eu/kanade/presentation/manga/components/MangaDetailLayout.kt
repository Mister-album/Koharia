package eu.kanade.presentation.manga.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.motion.eInkAnimationSpec
import tachiyomi.presentation.core.util.shouldExpandFAB

/** The series and directory detail pages share insets, scrolling and responsive layout. */
@Composable
fun MangaDetailLayout(
    isTabletUi: Boolean,
    useGrid: Boolean,
    columns: Int,
    refreshing: Boolean,
    selectionActive: Boolean,
    onRefresh: () -> Unit,
    snackbarHostState: SnackbarHostState,
    toolbar: @Composable (titleAlpha: Float, backgroundAlpha: Float) -> Unit,
    info: @Composable (tablet: Boolean, topPadding: Dp, horizontalBleed: Dp) -> Unit,
    summary: @Composable () -> Unit,
    contentHeader: @Composable () -> Unit,
    listContent: LazyListScope.() -> Unit,
    gridContent: LazyGridScope.() -> Unit,
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable (expanded: Boolean) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val firstVisible by remember(useGrid) {
        derivedStateOf { if (useGrid) gridState.firstVisibleItemIndex == 0 else listState.firstVisibleItemIndex == 0 }
    }
    val firstScrolled by remember(useGrid) {
        derivedStateOf {
            if (useGrid) gridState.firstVisibleItemScrollOffset > 0 else listState.firstVisibleItemScrollOffset > 0
        }
    }
    val titleAlpha by animateFloatAsState(
        if (isTabletUi || !firstVisible) 1f else 0f,
        animationSpec = eInkAnimationSpec(spring()),
        label = "Top Bar Title",
    )
    val backgroundAlpha by animateFloatAsState(
        if (isTabletUi || !firstVisible || firstScrolled) 1f else 0f,
        animationSpec = eInkAnimationSpec(spring()),
        label = "Top Bar Background",
    )
    Scaffold(
        topBar = { toolbar(titleAlpha, backgroundAlpha) },
        bottomBar = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) { bottomBar() }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            floatingActionButton(
                if (useGrid) {
                    gridState.lastScrolledBackward || !gridState.canScrollForward || !gridState.canScrollBackward
                } else {
                    listState.shouldExpandFAB()
                },
            )
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        val top = padding.calculateTopPadding()
        val start = padding.calculateStartPadding(direction)
        val end = padding.calculateEndPadding(direction)
        PullRefresh(
            refreshing = refreshing,
            onRefresh = onRefresh,
            enabled = !selectionActive,
            indicatorPadding = PaddingValues(start = start, top = top, end = end),
        ) {
            val contents: @Composable () -> Unit = {
                if (useGrid) {
                    FastScrollLazyVerticalGrid(
                        columns = if (columns == 0) {
                            GridCells.Adaptive(128.dp)
                        } else {
                            GridCells.Fixed(columns.coerceIn(2, 6))
                        },
                        modifier = Modifier.fillMaxHeight(),
                        state = gridState,
                        contentPadding = PaddingValues(
                            start = if (isTabletUi) 12.dp else start + 12.dp,
                            end = if (isTabletUi) 12.dp else end + 12.dp,
                            top = if (isTabletUi) top else 0.dp,
                            bottom = padding.calculateBottomPadding(),
                        ),
                        topContentPadding = top,
                        endContentPadding = if (isTabletUi) 0.dp else end,
                        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
                        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
                    ) {
                        if (!isTabletUi) {
                            item(key = "detail-info", span = { GridItemSpan(maxLineSpan) }) { info(false, top, 16.dp) }
                            item(key = "detail-actions", span = { GridItemSpan(maxLineSpan) }) { actions() }
                            item(key = "detail-summary", span = { GridItemSpan(maxLineSpan) }) { Column { summary() } }
                        }
                        item(key = "detail-count", span = { GridItemSpan(maxLineSpan) }) { contentHeader() }
                        gridContent()
                    }
                } else {
                    VerticalFastScroller(listState = listState, topContentPadding = top, endContentPadding = end) {
                        LazyColumn(
                            modifier = Modifier.fillMaxHeight(),
                            state = listState,
                            contentPadding = PaddingValues(
                                start = if (isTabletUi) 0.dp else start,
                                end = if (isTabletUi) 0.dp else end,
                                top = if (isTabletUi) top else 0.dp,
                                bottom = padding.calculateBottomPadding(),
                            ),
                        ) {
                            if (!isTabletUi) {
                                item(key = "detail-info") { info(false, top, 0.dp) }
                                item(key = "detail-actions") { actions() }
                                item(key = "detail-summary") { Column { summary() } }
                            }
                            item(key = "detail-count") { contentHeader() }
                            listContent()
                        }
                    }
                }
            }
            if (isTabletUi) {
                TwoPanelBox(
                    modifier = Modifier.padding(start = start, end = end),
                    startContent = {
                        Column(
                            Modifier.verticalScroll(
                                rememberScrollState(),
                            ).padding(bottom = padding.calculateBottomPadding()),
                        ) {
                            info(true, top, 0.dp)
                            actions()
                            summary()
                        }
                    },
                    endContent = { contents() },
                )
            } else {
                contents()
            }
        }
    }
}
