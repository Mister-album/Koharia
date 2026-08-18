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
import eu.kanade.presentation.library.components.LibraryReadProgressCorner
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaReadProgress
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
    showLibraryBadges: Boolean,
    readProgress: ((Manga) -> MangaReadProgress?)? = null,
    showPagingLoadingIndicator: Boolean = true,
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
        if (showPagingLoadingIndicator && mangaList.loadState.prepend is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }

        items(count = mangaList.itemCount) { index ->
            val manga by mangaList[index]?.collectAsState() ?: return@items
            BrowseSourceCompactGridItem(
                manga = manga,
                showTitle = showTitle,
                showLibraryBadges = showLibraryBadges,
                readProgress = readProgress?.invoke(manga),
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
    showTitle: Boolean,
    showLibraryBadges: Boolean,
    readProgress: MangaReadProgress?,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    val isLibraryManga = showLibraryBadges && manga.favorite
    val hasReadProgress = readProgress != null && readProgress.totalChapterCount > 0
    MangaCompactGridItem(
        title = manga.title.takeIf { showTitle },
        coverData = MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = isLibraryManga,
            url = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        ),
        coverAlpha = if (isLibraryManga) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        coverBadgeStart = {
            InLibraryBadge(enabled = isLibraryManga)
        },
        coverBadgeEndModifier = if (hasReadProgress) Modifier.padding(top = 32.dp) else Modifier,
        coverOverlay = if (hasReadProgress) {
            {
                LibraryReadProgressCorner(
                    readCount = readProgress.readCount,
                    totalChapterCount = readProgress.totalChapterCount,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        } else {
            null
        },
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
