package koharia.komga.ui.library.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import koharia.connection.LibraryConnectionProfile
import koharia.connection.ui.ConnectionProviderIcon
import koharia.source.komga.TYPE_ALL_INDEX
import koharia.source.komga.TYPE_BOOKS_INDEX
import koharia.source.komga.TYPE_READ_LISTS_INDEX
import koharia.source.komga.TYPE_SERIES_INDEX
import kotlinx.collections.immutable.persistentListOf
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
    var selectingDisplayMode by remember { mutableStateOf(false) }
    var selectingServer by remember { mutableStateOf(false) }
    var selectingSearchType by remember { mutableStateOf(false) }
    val canSwitchConnection = connectionProfiles.size > 1

    SearchToolbar(
        navigateUp = navigateUp,
        titleContent = { AppBarTitle(stringResource(MR.strings.app_name)) },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onSearch,
        onClickCloseSearch = onClickCloseSearch,
        actions = actions@{
            if (searchQuery != null) {
                Box {
                    TextButton(
                        onClick = { selectingSearchType = true },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            text = searchTypeLabel(searchType),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            imageVector = Icons.Outlined.ArrowDropDown,
                            contentDescription = null,
                        )
                    }

                    DropdownMenu(
                        expanded = selectingSearchType,
                        onDismissRequest = { selectingSearchType = false },
                    ) {
                        SEARCH_TYPES.forEach { type ->
                            RadioMenuItem(
                                text = { Text(text = searchTypeLabel(type)) },
                                isChecked = searchType == type,
                            ) {
                                selectingSearchType = false
                                onSearchTypeSelect(type)
                            }
                        }
                    }
                }
                return@actions
            }

            AppBarActions(
                actions = persistentListOf<AppBar.AppBarAction>().builder()
                    .apply {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_display_mode),
                                icon = if (displayMode == LibraryDisplayMode.List) {
                                    Icons.AutoMirrored.Filled.ViewList
                                } else {
                                    Icons.Filled.ViewModule
                                },
                                onClick = { selectingDisplayMode = true },
                            ),
                        )
                        if (canSwitchConnection) {
                            add(
                                AppBar.Action(
                                    title = stringResource(MR.strings.pref_connection_management),
                                    icon = Icons.Outlined.Storage,
                                    onClick = { selectingServer = true },
                                ),
                            )
                        }
                        if (showFilterAction) {
                            add(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_filter),
                                    icon = Icons.Outlined.FilterList,
                                    onClick = onFilterClick,
                                ),
                            )
                        }
                    }
                    .build(),
            )

            if (canSwitchConnection) {
                DropdownMenu(
                    expanded = selectingServer,
                    onDismissRequest = { selectingServer = false },
                ) {
                    connectionProfiles.forEach { profile ->
                        RadioMenuItem(
                            text = { Text(text = profile.name) },
                            isChecked = activeConnectionId == profile.id,
                            leadingIcon = {
                                ConnectionProviderIcon(
                                    providerId = profile.providerId,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                        ) {
                            selectingServer = false
                            onConnectionSelect(profile.id)
                        }
                    }
                }
            }

            DropdownMenu(
                expanded = selectingDisplayMode,
                onDismissRequest = { selectingDisplayMode = false },
            ) {
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_comfortable_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.ComfortableGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.CompactGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_cover_only_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.CoverOnlyGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.CoverOnlyGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_list)) },
                    isChecked = displayMode == LibraryDisplayMode.List,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.List)
                }
            }
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
