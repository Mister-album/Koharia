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
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.presentation.library.components.displayText
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceComfortableGrid(
    modifier: Modifier = Modifier,
    mangaList: LazyPagingItems<StateFlow<Manga>>,
    columns: GridCells,
    contentPadding: PaddingValues,
    selectedMangaIds: Set<Long> = emptySet(),
    showLibraryBadges: Boolean,
    readProgress: ((Manga) -> MangaReadProgress?)? = null,
    downloaded: ((Manga) -> Boolean)? = null,
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
            BrowseSourceComfortableGridItem(
                manga = manga,
                label = entryLabel?.invoke(manga) ?: manga.title,
                entryBadge = { entryBadge?.invoke(manga) },
                isSelected = manga.id in selectedMangaIds,
                showLibraryBadges = showLibraryBadges,
                readProgress = readProgress?.invoke(manga),
                downloaded = downloaded?.invoke(manga) == true,
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
private fun BrowseSourceComfortableGridItem(
    manga: Manga,
    label: String,
    entryBadge: @Composable () -> Unit,
    isSelected: Boolean,
    showLibraryBadges: Boolean,
    readProgress: MangaReadProgress?,
    downloaded: Boolean,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    val isLibraryManga = showLibraryBadges && manga.favorite
    val readProgressText = readProgress?.displayText()
    val hasReadProgress = readProgressText != null
    MangaComfortableGridItem(
        title = label,
        coverData = MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = isLibraryManga,
            url = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        ),
        isSelected = isSelected,
        coverAlpha = if (isLibraryManga) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        coverBadgeStart = {
            InLibraryBadge(enabled = isLibraryManga)
            entryBadge()
        },
        coverBadgeEndModifier = if (hasReadProgress) Modifier.padding(top = 32.dp) else Modifier,
        coverOverlay = if (hasReadProgress || downloaded) {
            {
                if (hasReadProgress) {
                    LibraryReadProgressCorner(
                        readCount = readProgress.readCount,
                        totalChapterCount = readProgress.totalChapterCount,
                        text = readProgressText,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
                if (downloaded) {
                    DownloadedCorner(
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
