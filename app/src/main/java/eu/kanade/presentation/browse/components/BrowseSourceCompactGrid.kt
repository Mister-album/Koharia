package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.DownloadedCorner
import eu.kanade.presentation.library.components.LibraryReadProgressCorner
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.presentation.library.components.ManualDownloadIndicator
import eu.kanade.presentation.library.components.displayText
import koharia.connection.MangaDownloadState
import koharia.connection.MangaDownloadStatus
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceCompactGrid(
    modifier: Modifier = Modifier,
    mangaList: LazyPagingItems<StateFlow<Manga>>,
    columns: GridCells,
    contentPadding: PaddingValues,
    showTitle: Boolean = true,
    selectedMangaIds: Set<Long> = emptySet(),
    showLibraryBadges: Boolean,
    readProgress: ((Manga) -> MangaReadProgress?)? = null,
    downloadState: ((Manga) -> MangaDownloadState?)? = null,
    showPagingLoadingIndicator: Boolean = true,
    entryLabel: ((Manga) -> String)? = null,
    contentHeader: (@Composable () -> Unit)? = null,
    entryBadge: (@Composable (Manga) -> Unit)? = null,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    LazyVerticalGrid(
        modifier = modifier,
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        contentHeader?.let { header -> item(span = { GridItemSpan(maxLineSpan) }) { header() } }
        if (showPagingLoadingIndicator && mangaList.loadState.prepend is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }

        items(count = mangaList.itemCount) { index ->
            val manga by mangaList[index]?.collectAsState() ?: return@items
            BrowseSourceCompactGridItem(
                manga = manga,
                label = entryLabel?.invoke(manga) ?: manga.title,
                entryBadge = { entryBadge?.invoke(manga) },
                showTitle = showTitle,
                isSelected = manga.id in selectedMangaIds,
                showLibraryBadges = showLibraryBadges,
                readProgress = readProgress?.invoke(manga),
                downloadState = downloadState?.invoke(manga) ?: MangaDownloadState(0, null),
                onClick = { onMangaClick(manga) },
                onLongClick = { onMangaLongClick(manga) },
            )
        }

        if (
            showPagingLoadingIndicator &&
            (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun BrowseSourceCompactGridItem(
    manga: Manga,
    label: String,
    entryBadge: @Composable () -> Unit,
    isSelected: Boolean,
    showTitle: Boolean,
    showLibraryBadges: Boolean,
    readProgress: MangaReadProgress?,
    downloadState: MangaDownloadState,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    val isLibraryManga = manga.favorite
    val showLibraryBadge = showLibraryBadges && isLibraryManga
    val readProgressText = readProgress?.displayText()
    val hasReadProgress = readProgressText != null
    MangaCompactGridItem(
        title = label.takeIf { showTitle },
        coverData = MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = isLibraryManga,
            url = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        ),
        isSelected = isSelected,
        coverAlpha = if (showLibraryBadge) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        coverBadgeStart = {
            InLibraryBadge(enabled = showLibraryBadge)
            entryBadge()
        },
        coverBadgeEndModifier = if (hasReadProgress) Modifier.padding(top = 32.dp) else Modifier,
        coverOverlay = if (hasReadProgress || downloadState.status != MangaDownloadStatus.NONE) {
            {
                if (hasReadProgress) {
                    LibraryReadProgressCorner(
                        readCount = readProgress.readCount,
                        totalChapterCount = readProgress.totalChapterCount,
                        text = readProgressText,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
                if (downloadState.status != MangaDownloadStatus.NONE) {
                    ManualDownloadIndicator(
                        state = downloadState,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                    )
                }
            }
        } else {
            null
        },
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
