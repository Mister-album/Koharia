package koharia.source.local

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import eu.kanade.presentation.components.AppBar
import koharia.connection.LibraryConnectionProfile
import koharia.connection.ui.ConnectionLibraryToolbar
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun LocalLibraryToolbar(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    connectionProfiles: List<LibraryConnectionProfile>,
    activeConnectionId: Long,
    onConnectionSelect: (Long) -> Unit,
    hasActiveFilters: Boolean,
    onImportClick: () -> Unit,
    onMergeImagesClick: (() -> Unit)?,
    onFilterClick: () -> Unit,
    onSettingsClick: () -> Unit,
    navigateUp: (() -> Unit)?,
    onSearch: (String) -> Unit,
    onClickCloseSearch: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    folderTitle: String? = null,
    onReadAsComicClick: (() -> Unit)? = null,
    onRecoverOperation: (() -> Unit)? = null,
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
        showConnectionAction = folderTitle == null && connectionProfiles.size > 1,
        title = folderTitle,
        hasActiveFilters = hasActiveFilters,
        onFilterClick = onFilterClick,
        navigateUp = navigateUp,
        scrollBehavior = scrollBehavior,
        additionalActions = listOf(
            AppBar.Action(
                title = stringResource(MR.strings.local_library_import_files),
                icon = Icons.Outlined.UploadFile,
                onClick = onImportClick,
            ),
            onMergeImagesClick?.let {
                AppBar.OverflowAction(
                    title = stringResource(MR.strings.image_comic_merge),
                    onClick = it,
                )
            },
            onReadAsComicClick?.let {
                AppBar.OverflowAction(
                    title = stringResource(MR.strings.local_library_show_as_comic),
                    onClick = it,
                )
            },
            onRecoverOperation?.let {
                AppBar.OverflowAction(
                    title = stringResource(MR.strings.local_library_pending_operation),
                    onClick = it,
                )
            },
            if (folderTitle == null) {
                AppBar.OverflowAction(
                    title = stringResource(MR.strings.local_library_manage_bookshelves),
                    onClick = onSettingsClick,
                )
            } else {
                null
            },
        ).filterNotNull(),
    )
}
