package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.presentation.library.components.displayText
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceList(
    modifier: Modifier = Modifier,
    mangaList: LazyPagingItems<StateFlow<Manga>>,
    contentPadding: PaddingValues,
    showLibraryBadges: Boolean,
    readProgress: ((Manga) -> MangaReadProgress?)? = null,
    showPagingLoadingIndicator: Boolean = true,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
    ) {
        item {
            if (showPagingLoadingIndicator && mangaList.loadState.prepend is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }

        items(count = mangaList.itemCount) { index ->
            val manga by mangaList[index]?.collectAsState() ?: return@items
            BrowseSourceListItem(
                manga = manga,
                showLibraryBadges = showLibraryBadges,
                readProgress = readProgress?.invoke(manga),
                onClick = { onMangaClick(manga) },
                onLongClick = { onMangaLongClick(manga) },
            )
        }

        item {
            if (
                showPagingLoadingIndicator &&
                (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading)
            ) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun BrowseSourceListItem(
    manga: Manga,
    showLibraryBadges: Boolean,
    readProgress: MangaReadProgress?,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    val isLibraryManga = showLibraryBadges && manga.favorite
    val readProgressText = readProgress?.displayText()
    MangaListItem(
        title = manga.title,
        coverData = MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = isLibraryManga,
            url = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        ),
        coverAlpha = if (isLibraryManga) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        badge = {
            InLibraryBadge(enabled = isLibraryManga)
        },
        readProgressText = readProgressText,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
