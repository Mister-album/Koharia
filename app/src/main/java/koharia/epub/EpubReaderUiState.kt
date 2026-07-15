package koharia.epub

import androidx.compose.runtime.Immutable

@Immutable
data class EpubReaderUiState(
    val mangaId: Long = -1L,
    val chapterId: Long = -1L,
    val mangaTitle: String? = null,
    val chapterTitle: String? = null,
    val currentSectionTitle: String? = null,
    val progressionPercent: Int? = null,
    val isLoading: Boolean = false,
    val isReady: Boolean = false,
    val menuVisible: Boolean = true,
    val errorMessage: String? = null,
)
