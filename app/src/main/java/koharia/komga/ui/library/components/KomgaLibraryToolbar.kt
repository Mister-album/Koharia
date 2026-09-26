package koharia.komga.ui.library.components

import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import koharia.connection.LibraryConnectionProfile
import koharia.connection.ui.ConnectionLibraryToolbar
import koharia.connection.ui.ConnectionSearchChoice
import koharia.connection.ui.ConnectionSearchScope
import koharia.source.komga.TYPE_ALL_INDEX
import koharia.source.komga.TYPE_BOOKS_INDEX
import koharia.source.komga.TYPE_READ_LISTS_INDEX
import koharia.source.komga.TYPE_SERIES_INDEX
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun KomgaLibraryToolbar(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    connectionProfiles: List<LibraryConnectionProfile>,
    activeConnectionId: Long,
    onConnectionSelect: (Long) -> Unit,
    showFilterAction: Boolean,
    onFilterClick: () -> Unit,
    navigateUp: (() -> Unit)?,
    onSearch: (String) -> Unit,
    onClickCloseSearch: () -> Unit,
    searchType: Int,
    onSearchTypeSelect: (Int) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    ConnectionLibraryToolbar(
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        onSearch = onSearch,
        onCloseSearch = onClickCloseSearch,
        displayMode = displayMode,
        onDisplayModeChange = onDisplayModeChange,
        connectionProfiles = connectionProfiles,
        activeConnectionId = activeConnectionId,
        onConnectionSelect = onConnectionSelect,
        onFilterClick = onFilterClick,
        navigateUp = navigateUp,
        showFilterAction = showFilterAction,
        showConnectionAction = connectionProfiles.size > 1,
        searchActions = {
            ConnectionSearchScope(
                choices = SEARCH_TYPES.map { ConnectionSearchChoice(it, searchTypeLabel(it)) },
                selected = searchType,
                onSelect = onSearchTypeSelect,
            )
        },
        scrollBehavior = scrollBehavior,
    )
}

private val SEARCH_TYPES = listOf(
    TYPE_ALL_INDEX,
    TYPE_SERIES_INDEX,
    TYPE_READ_LISTS_INDEX,
    TYPE_BOOKS_INDEX,
)

@Composable
private fun searchTypeLabel(type: Int): String {
    return when (type) {
        TYPE_SERIES_INDEX -> stringResource(MR.strings.komga_filter_series)
        TYPE_READ_LISTS_INDEX -> stringResource(MR.strings.komga_filter_read_lists)
        TYPE_BOOKS_INDEX -> stringResource(MR.strings.komga_filter_books)
        else -> stringResource(MR.strings.all)
    }
}
