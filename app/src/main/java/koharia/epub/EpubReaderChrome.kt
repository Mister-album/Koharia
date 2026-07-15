package koharia.epub

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import koharia.domain.epub.model.EpubBookmark
import koharia.epub.model.EpubTocEntry
import koharia.epub.settings.EpubLayoutPreferences
import koharia.epub.settings.EpubReaderPreferences
import koharia.epub.settings.EpubReaderSettingsContent
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

private val readerBarsSlideAnimationSpec = tween<IntOffset>(200)
private val readerBarsFadeAnimationSpec = tween<Float>(150)

internal enum class EpubBottomPanel {
    NONE,
    SETTINGS,
    MORE,
}

@Composable
internal fun EpubReaderTopBar(
    visible: Boolean,
    title: String?,
    subtitle: String?,
    isSearchable: Boolean,
    isBookmarked: Boolean,
    bookmarkEnabled: Boolean,
    modifier: Modifier = Modifier,
    onNavigateUp: () -> Unit,
    onClick: () -> Unit,
    onSearch: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    val backgroundColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(3.dp)
        .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)
    AnimatedVisibility(
        modifier = modifier,
        visible = visible,
        enter = slideInVertically(readerBarsSlideAnimationSpec) { -it } + fadeIn(readerBarsFadeAnimationSpec),
        exit = slideOutVertically(readerBarsSlideAnimationSpec) { -it } + fadeOut(readerBarsFadeAnimationSpec),
    ) {
        AppBar(
            title = title,
            subtitle = subtitle,
            navigateUp = onNavigateUp,
            modifier = Modifier
                .background(backgroundColor)
                .clickable(onClick = onClick),
            backgroundColor = Color.Transparent,
            actions = {
                if (isSearchable) {
                    IconButton(onClick = onSearch) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = stringResource(MR.strings.action_search),
                        )
                    }
                }
                IconButton(
                    enabled = bookmarkEnabled,
                    onClick = onToggleBookmark,
                ) {
                    Icon(
                        imageVector = if (isBookmarked) {
                            Icons.Outlined.Bookmark
                        } else {
                            Icons.Outlined.BookmarkBorder
                        },
                        contentDescription = stringResource(
                            if (isBookmarked) {
                                MR.strings.epub_reader_remove_bookmark
                            } else {
                                MR.strings.epub_reader_add_bookmark
                            },
                        ),
                    )
                }
            },
        )
    }
}

@Composable
internal fun EpubReaderBottomArea(
    visible: Boolean,
    activePanel: EpubBottomPanel,
    preferences: EpubLayoutPreferences,
    readerPreferences: ReaderPreferences,
    epubReaderPreferences: EpubReaderPreferences,
    chapterNavigatorType: ChapterNavigatorType,
    currentPosition: Int,
    totalPositions: Int,
    progression: Double,
    currentVisualPage: Int?,
    totalVisualPages: Int?,
    enabledPreviousChapter: Boolean,
    enabledNextChapter: Boolean,
    onPositionChange: (Int) -> Unit,
    onProgressionChange: (Double) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenContents: () -> Unit,
    onToggleNightMode: () -> Unit,
    onToggleSettings: () -> Unit,
    onToggleMore: () -> Unit,
    morePanel: @Composable () -> Unit,
) {
    val backgroundColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(3.dp)
        .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)
    val visualPagePair = currentVisualPage?.let { currentPage ->
        totalVisualPages?.let { totalPages -> currentPage to totalPages }
    }

    Column {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(readerBarsSlideAnimationSpec) { it } + fadeIn(readerBarsFadeAnimationSpec),
            exit = slideOutVertically(readerBarsSlideAnimationSpec) { it } + fadeOut(readerBarsFadeAnimationSpec),
        ) {
            Column {
                ChapterNavigator(
                    type = chapterNavigatorType,
                    onNextChapter = onNextChapter,
                    enabledNext = enabledNextChapter,
                    onPreviousChapter = onPreviousChapter,
                    enabledPrevious = enabledPreviousChapter,
                    currentPage = currentPosition.coerceIn(1, totalPositions.coerceAtLeast(1)),
                    totalPages = totalPositions.coerceAtLeast(1),
                    onPageIndexChange = {},
                    onPageIndexChangeFinished = onPositionChange,
                    sliderProgress = progression.toFloat().coerceIn(0f, 1f),
                    onProgressChangeFinished = { onProgressionChange(it.toDouble()) },
                    displayCurrentText = visualPagePair?.first?.toString()
                        ?: "${(progression * 100).roundToInt().coerceIn(0, 100)}%",
                    displayTotalText = visualPagePair?.second?.toString().orEmpty(),
                )
                AnimatedVisibility(
                    visible = activePanel != EpubBottomPanel.NONE,
                    modifier = Modifier.padding(top = 8.dp),
                    enter = expandVertically(
                        animationSpec = tween(200),
                        expandFrom = Alignment.Bottom,
                    ) + slideInVertically(readerBarsSlideAnimationSpec) { it } +
                        fadeIn(readerBarsFadeAnimationSpec),
                    exit = shrinkVertically(
                        animationSpec = tween(200),
                        shrinkTowards = Alignment.Bottom,
                    ) + slideOutVertically(readerBarsSlideAnimationSpec) { it } +
                        fadeOut(readerBarsFadeAnimationSpec),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(backgroundColor),
                    ) {
                        when (activePanel) {
                            EpubBottomPanel.SETTINGS -> {
                                EpubReaderSettingsContent(
                                    preferences = preferences,
                                    readerPreferences = readerPreferences,
                                    epubReaderPreferences = epubReaderPreferences,
                                    modifier = Modifier.heightIn(max = 320.dp),
                                )
                            }
                            EpubBottomPanel.MORE -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 320.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    morePanel()
                                }
                            }
                            EpubBottomPanel.NONE -> Unit
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (activePanel == EpubBottomPanel.NONE) 8.dp else 0.dp)
                        .background(backgroundColor)
                        .padding(horizontal = 8.dp)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EpubActionButton(
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.List,
                                contentDescription = stringResource(MR.strings.epub_reader_toc),
                            )
                        },
                        onClick = onOpenContents,
                    )
                    EpubActionButton(
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.DarkMode,
                                contentDescription = stringResource(MR.strings.epub_reader_quick_night_mode),
                            )
                        },
                        onClick = onToggleNightMode,
                    )
                    EpubActionButton(
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = stringResource(MR.strings.epub_reader_settings),
                            )
                        },
                        selected = activePanel == EpubBottomPanel.SETTINGS,
                        onClick = onToggleSettings,
                    )
                    EpubActionButton(
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.MoreHoriz,
                                contentDescription = stringResource(MR.strings.label_more),
                            )
                        },
                        selected = activePanel == EpubBottomPanel.MORE,
                        onClick = onToggleMore,
                    )
                }
            }
        }
    }
}

@Composable
private fun EpubActionButton(
    icon: @Composable () -> Unit,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val alpha = if (selected) 1f else 0.82f
    IconButton(onClick = onClick) {
        Box(modifier = Modifier.alpha(alpha)) {
            icon()
        }
    }
}

@Composable
internal fun EpubReadingProgressIndicator(
    progressPercent: Int?,
    modifier: Modifier = Modifier,
) {
    if (progressPercent == null) return

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Text(
            text = "$progressPercent%",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun EpubReaderMorePanel(
    state: EpubReaderUiState,
    onOpenAsPages: () -> Unit,
    onReload: () -> Unit,
    onOpenExternal: () -> Unit,
    onShowBookInfo: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        MoreActionRow(
            icon = Icons.Outlined.Refresh,
            title = stringResource(MR.strings.epub_reader_reload_current_chapter),
            enabled = state.mangaId > 0 && state.chapterId > 0,
            onClick = onReload,
        )
        MoreActionRow(
            icon = Icons.AutoMirrored.Outlined.OpenInNew,
            title = stringResource(MR.strings.epub_reader_open_in_external_app),
            subtitle = stringResource(MR.strings.epub_reader_external_app_requires_download)
                .takeIf { state.localEpubUri == null },
            enabled = state.localEpubUri != null,
            onClick = onOpenExternal,
        )
        MoreActionRow(
            icon = Icons.Outlined.Info,
            title = stringResource(MR.strings.epub_reader_book_info),
            enabled = state.isReady,
            onClick = onShowBookInfo,
        )
        if (state.canOpenAsPages) {
            MoreActionRow(
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                title = stringResource(MR.strings.epub_reader_open_as_pages),
                enabled = state.mangaId > 0 && state.chapterId > 0,
                onClick = onOpenAsPages,
            )
        }
    }
}

@Composable
private fun MoreActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(22.dp),
            )
        },
        headlineContent = { Text(text = title) },
        supportingContent = subtitle?.let { text ->
            { Text(text = text) }
        },
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick),
    )
    HorizontalDivider()
}

@Composable
internal fun EpubBookInfoDialog(
    state: EpubReaderUiState,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val progressPercent = state.progressionPercent
        ?: (state.progression * 100).roundToInt().coerceIn(0, 100)

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.epub_reader_book_info)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BookInfoItem(
                    label = stringResource(MR.strings.epub_reader_book_info_series),
                    value = state.mangaTitle.orEmpty(),
                )
                BookInfoItem(
                    label = stringResource(MR.strings.epub_reader_book_info_book),
                    value = state.chapterTitle.orEmpty(),
                )
                state.currentSectionTitle?.takeIf { it.isNotBlank() }?.let { sectionTitle ->
                    BookInfoItem(
                        label = stringResource(MR.strings.epub_reader_book_info_chapter),
                        value = sectionTitle,
                    )
                }
                BookInfoItem(
                    label = stringResource(MR.strings.epub_reader_book_info_format),
                    value = "EPUB",
                )
                state.bookFileName?.takeIf { it.isNotBlank() }?.let { fileName ->
                    BookInfoItem(
                        label = stringResource(MR.strings.epub_reader_book_info_file_name),
                        value = fileName,
                    )
                }
                state.bookSizeBytes?.takeIf { it > 0L }?.let { sizeBytes ->
                    BookInfoItem(
                        label = stringResource(MR.strings.epub_reader_book_info_file_size),
                        value = Formatter.formatFileSize(context, sizeBytes),
                    )
                }
                BookInfoItem(
                    label = stringResource(MR.strings.epub_reader_book_info_open_source),
                    value = stringResource(
                        if (state.isUsingLocalFile) {
                            MR.strings.epub_reader_book_info_source_local
                        } else {
                            MR.strings.epub_reader_book_info_source_komga
                        },
                    ),
                )
                BookInfoItem(
                    label = stringResource(MR.strings.epub_reader_book_info_reading_progress),
                    value = "$progressPercent%",
                )
                if (state.currentVisualPage != null && state.totalVisualPages != null) {
                    BookInfoItem(
                        label = stringResource(MR.strings.epub_reader_book_info_pages),
                        value = "${state.currentVisualPage} / ${state.totalVisualPages}",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
    )
}

@Composable
private fun BookInfoItem(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun EpubNavigationDrawer(
    bookTitle: String?,
    entries: List<EpubTocEntry>,
    bookmarks: List<EpubBookmark>,
    currentHref: String?,
    onSelectTocEntry: (EpubTocEntry) -> Unit,
    onSelectBookmark: (EpubBookmark) -> Unit,
    onDeleteBookmark: (EpubBookmark) -> Unit,
    onUpdateBookmarkNote: (EpubBookmark, String?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Text(
                text = bookTitle.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        HorizontalDivider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            EpubNavigationContent(
                entries = entries,
                bookmarks = bookmarks,
                currentHref = currentHref,
                onSelectTocEntry = onSelectTocEntry,
                onSelectBookmark = onSelectBookmark,
                onDeleteBookmark = onDeleteBookmark,
                onUpdateBookmarkNote = onUpdateBookmarkNote,
            )
        }
    }
}
