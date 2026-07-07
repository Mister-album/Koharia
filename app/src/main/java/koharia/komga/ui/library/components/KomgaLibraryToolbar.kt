package koharia.komga.ui.library.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import koharia.source.komga.KomgaServerProfile
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
    serverProfiles: List<KomgaServerProfile>,
    activeServerId: Long,
    onServerSelect: (Long) -> Unit,
    showFilterAction: Boolean,
    onFilterClick: () -> Unit,
    navigateUp: (() -> Unit)?,
    onSearch: (String) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    var selectingDisplayMode by remember { mutableStateOf(false) }
    var selectingServer by remember { mutableStateOf(false) }

    SearchToolbar(
        navigateUp = navigateUp,
        titleContent = { AppBarTitle(stringResource(MR.strings.app_name)) },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onSearch,
        onClickCloseSearch = { onSearchQueryChange(null) },
        actions = {
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
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.pref_komga_server),
                                icon = Icons.Outlined.Storage,
                                onClick = { selectingServer = true },
                            ),
                        )
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

            DropdownMenu(
                expanded = selectingServer,
                onDismissRequest = { selectingServer = false },
            ) {
                serverProfiles.forEach { profile ->
                    RadioMenuItem(
                        text = { Text(text = profile.name) },
                        isChecked = activeServerId == profile.id,
                    ) {
                        selectingServer = false
                        onServerSelect(profile.id)
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
