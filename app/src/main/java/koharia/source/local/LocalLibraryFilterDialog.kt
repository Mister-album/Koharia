package koharia.source.local

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.manga.ChapterDisplayPage
import eu.kanade.presentation.manga.ChapterFilterPage
import eu.kanade.presentation.manga.ChapterSortPage
import eu.kanade.presentation.manga.components.ScanlatorFilterDialog
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.CollapsibleBox
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TextItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun LocalLibraryFilterDialog(
    filters: LocalLibraryFilters,
    rememberFilters: Boolean,
    isFolder: Boolean,
    networkStorage: Boolean,
    availableScanlators: Set<String>,
    libraryPreferences: LibraryPreferences,
    displayMode: LibraryDisplayMode,
    onDisplayModeChanged: (LibraryDisplayMode) -> Unit,
    titleDisplayMode: Long,
    onTitleDisplayModeChanged: (Long) -> Unit,
    onDismissRequest: () -> Unit,
    onFiltersChanged: (LocalLibraryFilters, Boolean) -> Unit,
) {
    var showScanlators by remember { mutableStateOf(false) }
    if (showScanlators) {
        ScanlatorFilterDialog(
            availableScanlators = availableScanlators,
            excludedScanlators = filters.excludedScanlators,
            onDismissRequest = { showScanlators = false },
            onConfirm = {
                onFiltersChanged(filters.copy(excludedScanlators = it), rememberFilters)
                showScanlators = false
            },
        )
    }
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = persistentListOf(
            stringResource(MR.strings.action_filter),
            stringResource(MR.strings.action_sort),
            stringResource(MR.strings.action_display),
        ),
        tabOverflowMenuContent = { closeMenu ->
            CheckboxItem(
                label = stringResource(MR.strings.shelf_persistent_filters),
                checked = rememberFilters,
                onClick = { onFiltersChanged(filters, !rememberFilters) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.action_reset)) },
                onClick = {
                    onFiltersChanged(LocalLibraryFilters(), rememberFilters)
                    closeMenu()
                },
            )
        },
    ) { page ->
        Column(
            Modifier.padding(vertical = TabbedDialogPaddings.Vertical).verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> {
                    val availabilityLabel = stringResource(
                        if (networkStorage) MR.strings.label_downloaded else MR.strings.local_library_available,
                    )
                    if (isFolder) {
                        ChapterFilterPage(
                            downloadFilter = filters.downloaded,
                            downloadFilterLabel = availabilityLabel,
                            onDownloadFilterChanged = {
                                onFiltersChanged(filters.copy(downloaded = it), rememberFilters)
                            },
                            unreadFilter = filters.unread,
                            onUnreadFilterChanged = { onFiltersChanged(filters.copy(unread = it), rememberFilters) },
                            bookmarkedFilter = filters.bookmarked,
                            onBookmarkedFilterChanged = {
                                onFiltersChanged(filters.copy(bookmarked = it), rememberFilters)
                            },
                            scanlatorFilterActive = filters.excludedScanlators.isNotEmpty(),
                            onScanlatorFilterClicked = { showScanlators = true },
                        )
                    } else {
                        TriStateItem(availabilityLabel, filters.downloaded) {
                            onFiltersChanged(filters.copy(downloaded = it), rememberFilters)
                        }
                        TriStateItem(stringResource(MR.strings.action_filter_unread), filters.unread) {
                            onFiltersChanged(filters.copy(unread = it), rememberFilters)
                        }
                        TriStateItem(stringResource(MR.strings.label_started), filters.started) {
                            onFiltersChanged(filters.copy(started = it), rememberFilters)
                        }
                        TriStateItem(stringResource(MR.strings.action_filter_bookmarked), filters.bookmarked) {
                            onFiltersChanged(filters.copy(bookmarked = it), rememberFilters)
                        }
                        TriStateItem(stringResource(MR.strings.completed), filters.completed) {
                            onFiltersChanged(filters.copy(completed = it), rememberFilters)
                        }
                    }
                    CollapsibleBox(heading = stringResource(MR.strings.shelf_filter_conditions)) {
                        Column { MetadataFilters(filters) { onFiltersChanged(it, rememberFilters) } }
                    }
                }
                1 -> {
                    if (isFolder) {
                        ChapterSortPage(
                            sortingMode = filters.chapterSortingMode,
                            sortDescending = filters.descending,
                            onItemSelected = {
                                onFiltersChanged(
                                    filters.selectSort(localSortIndex(it), preserveDirection = false),
                                    rememberFilters,
                                )
                            },
                        )
                    } else {
                        listOf(
                            MR.strings.action_sort_alpha,
                            MR.strings.local_library_sort_added,
                            MR.strings.local_library_sort_modified,
                        )
                            .forEachIndexed { index, label ->
                                SortItem(stringResource(label), filters.descending.takeIf { filters.sort == index }) {
                                    onFiltersChanged(filters.selectSort(index), rememberFilters)
                                }
                            }
                    }
                    CheckboxItem(stringResource(MR.strings.local_library_folders_first), filters.foldersFirst) {
                        onFiltersChanged(filters.copy(foldersFirst = !filters.foldersFirst), rememberFilters)
                    }
                }
                2 -> {
                    val chapterMode by libraryPreferences.chapterCoverDisplayMode.collectAsState()
                    val currentDisplay = if (isFolder) chapterMode.toLocalLibraryDisplayMode() else displayMode
                    SettingsChipRow(MR.strings.action_display_mode) {
                        listOf(
                            MR.strings.action_display_grid to LibraryDisplayMode.CompactGrid,
                            MR.strings.action_display_comfortable_grid to LibraryDisplayMode.ComfortableGrid,
                            MR.strings.action_display_cover_only_grid to LibraryDisplayMode.CoverOnlyGrid,
                            MR.strings.action_display_list to LibraryDisplayMode.List,
                        ).forEach { (label, mode) ->
                            FilterChip(
                                selected = currentDisplay == mode,
                                onClick = {
                                    if (isFolder) {
                                        libraryPreferences.chapterCoverDisplayMode.set(mode.toLocalChapterCoverMode())
                                    } else {
                                        onDisplayModeChanged(mode)
                                    }
                                },
                                label = { Text(stringResource(label)) },
                            )
                        }
                    }
                    if (currentDisplay != LibraryDisplayMode.List) {
                        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                        val columnPreference = when {
                            isFolder && landscape -> libraryPreferences.chapterCoverGridLandscapeColumns
                            isFolder -> libraryPreferences.chapterCoverGridColumns
                            landscape -> libraryPreferences.landscapeColumns
                            else -> libraryPreferences.portraitColumns
                        }
                        val rawColumns by columnPreference.collectAsState()
                        val columns = rawColumns.takeUnless { isFolder && it < 0 }
                            ?: libraryPreferences.chapterCoverGridColumns.get()
                        SliderItem(
                            value = columns,
                            valueRange = 0..10,
                            label = stringResource(MR.strings.pref_library_columns),
                            valueString = columns.takeIf {
                                it > 0
                            }?.toString() ?: stringResource(MR.strings.label_auto),
                            onChange = columnPreference::set,
                            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    }
                    if (isFolder) {
                        val progress by libraryPreferences.showChapterReadProgress.collectAsState()
                        val fileSize by libraryPreferences.showChapterFileSize.collectAsState()
                        val hideMissing by libraryPreferences.hideMissingChapters.collectAsState()
                        ChapterDisplayPage(
                            displayMode = titleDisplayMode,
                            onDisplayModeSelected = onTitleDisplayModeChanged,
                            showChapterReadProgress = progress,
                            onShowChapterReadProgressChanged = libraryPreferences.showChapterReadProgress::set,
                            showChapterFileSize = fileSize,
                            onShowChapterFileSizeChanged = libraryPreferences.showChapterFileSize::set,
                            hideMissingChapters = hideMissing,
                            onHideMissingChaptersChanged = libraryPreferences.hideMissingChapters::set,
                            showMissingChaptersOption = false,
                        )
                    } else {
                        CheckboxItem(
                            stringResource(MR.strings.action_display_read_progress),
                            libraryPreferences.showLibraryReadProgress,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.MetadataFilters(filters: LocalLibraryFilters, onChange: (LocalLibraryFilters) -> Unit) {
    TextItem(stringResource(MR.strings.local_library_filter_series), filters.series) {
        onChange(filters.copy(series = it))
    }
    TextItem(stringResource(MR.strings.local_library_filter_format), filters.format) {
        onChange(filters.copy(format = it))
    }
    TextItem(stringResource(MR.strings.local_library_filter_chapter), filters.chapter) {
        onChange(filters.copy(chapter = it))
    }
    TextItem(stringResource(MR.strings.author), filters.author) { onChange(filters.copy(author = it)) }
    TextItem(stringResource(MR.strings.artist), filters.artist) { onChange(filters.copy(artist = it)) }
    TextItem(stringResource(MR.strings.local_library_filter_genre), filters.genre) {
        onChange(filters.copy(genre = it))
    }
}

internal fun LocalLibraryFilters.selectSort(index: Int, preserveDirection: Boolean = true): LocalLibraryFilters = copy(
    sort = index,
    descending = if (sort == index) !descending else descending && preserveDirection,
)

internal val LocalLibraryFilters.chapterSortingMode: Long
    get() = when (sort) {
        1 -> Manga.CHAPTER_SORTING_SOURCE
        2 -> Manga.CHAPTER_SORTING_UPLOAD_DATE
        3 -> Manga.CHAPTER_SORTING_NUMBER
        else -> Manga.CHAPTER_SORTING_ALPHABET
    }

internal fun localSortIndex(mode: Long): Int = when (mode) {
    Manga.CHAPTER_SORTING_SOURCE -> 1
    Manga.CHAPTER_SORTING_UPLOAD_DATE -> 2
    Manga.CHAPTER_SORTING_NUMBER -> 3
    else -> 0
}

internal fun Long.toLocalLibraryDisplayMode(): LibraryDisplayMode = when (this) {
    Manga.CHAPTER_COVER_DISPLAY_TEXT -> LibraryDisplayMode.List
    Manga.CHAPTER_COVER_DISPLAY_COVER -> LibraryDisplayMode.CoverOnlyGrid
    Manga.CHAPTER_COVER_DISPLAY_COMFORTABLE -> LibraryDisplayMode.ComfortableGrid
    else -> LibraryDisplayMode.CompactGrid
}

internal fun LibraryDisplayMode.toLocalChapterCoverMode(): Long = when (this) {
    LibraryDisplayMode.List -> Manga.CHAPTER_COVER_DISPLAY_TEXT
    LibraryDisplayMode.CoverOnlyGrid -> Manga.CHAPTER_COVER_DISPLAY_COVER
    LibraryDisplayMode.ComfortableGrid -> Manga.CHAPTER_COVER_DISPLAY_COMFORTABLE
    else -> Manga.CHAPTER_COVER_DISPLAY_COVER_AND_TITLE
}
