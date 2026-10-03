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
import eu.kanade.presentation.library.components.DownloadedBadge
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
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
    ) {
        contentHeader?.let { header -> item { header() } }
        item {
            if (showPagingLoadingIndicator && mangaList.loadState.prepend is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }

        items(count = mangaList.itemCount) { index ->
            val manga by mangaList[index]?.collectAsState() ?: return@items
            BrowseSourceListItem(
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
    MangaListItem(
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
        badge = {
            InLibraryBadge(enabled = isLibraryManga)
            DownloadedBadge(enabled = downloaded)
            entryBadge()
        },
        readProgressText = readProgressText,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
