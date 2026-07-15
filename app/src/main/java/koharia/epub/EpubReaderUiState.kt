package koharia.epub

import androidx.compose.runtime.Immutable
import koharia.domain.epub.model.EpubBookmark
import koharia.epub.model.EpubSearchResult

@Immutable
data class EpubReaderUiState(
    val mangaId: Long = -1L,
    val chapterId: Long = -1L,
    val mangaTitle: String? = null,
    val chapterTitle: String? = null,
    val bookFileName: String? = null,
    val bookSizeBytes: Long? = null,
    val localEpubUri: String? = null,
    val isUsingLocalFile: Boolean = false,
    val canOpenAsPages: Boolean = false,
    val previousBookChapterId: Long? = null,
    val nextBookChapterId: Long? = null,
    val currentSectionTitle: String? = null,
    val currentHref: String? = null,
    val progression: Double = 0.0,
    val progressionPercent: Int? = null,
    val currentPosition: Int = 1,
    val totalPositions: Int = 1,
    val currentVisualPage: Int? = null,
    val totalVisualPages: Int? = null,
    val sessionToken: Long = 0,
    val isLoading: Boolean = false,
    val isReady: Boolean = false,
    val menuVisible: Boolean = true,
    val errorMessage: String? = null,
    val isIncognito: Boolean = false,
    val bookmarks: List<EpubBookmark> = emptyList(),
    val currentBookmarkId: Long? = null,
    val isSearchable: Boolean = false,
    val isSearchActive: Boolean = false,
    val isSearchSubmitted: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<EpubSearchResult> = emptyList(),
    val isSearchLoading: Boolean = false,
    val searchErrorMessage: String? = null,
)
