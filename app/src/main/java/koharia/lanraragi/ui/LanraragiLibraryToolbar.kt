package koharia.lanraragi.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import koharia.connection.LibraryConnectionProfile
import koharia.connection.ui.ConnectionProviderIcon
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun LanraragiLibraryToolbar(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    onSearch: (String) -> Unit,
    onCloseSearch: () -> Unit,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    connectionProfiles: List<LibraryConnectionProfile>,
    activeConnectionId: Long,
    onConnectionSelect: (Long) -> Unit,
    onManageConnections: () -> Unit,
    onFilterClick: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    navigateUp: (() -> Unit)?,
) {
    var selectingDisplayMode by remember { mutableStateOf(false) }
    var selectingConnection by remember { mutableStateOf(false) }
    SearchToolbar(
        titleContent = { AppBarTitle(stringResource(MR.strings.app_name)) },
        navigateUp = navigateUp,
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onSearch,
        onClickCloseSearch = onCloseSearch,
        actions = {
            if (searchQuery != null) {
                IconButton(onClick = { selectingConnection = true }) {
                    Icon(Icons.Outlined.Storage, stringResource(MR.strings.pref_connection_management))
                }
            } else {
                AppBarActions(
                    persistentListOf(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_display_mode),
                            icon = if (displayMode == LibraryDisplayMode.List) {
                                Icons.AutoMirrored.Filled.ViewList
                            } else {
                                Icons.Filled.ViewModule
                            },
                            onClick = { selectingDisplayMode = true },
                        ),
                        AppBar.Action(
                            title = stringResource(MR.strings.pref_connection_management),
                            icon = Icons.Outlined.Storage,
                            onClick = { selectingConnection = true },
                        ),
                        AppBar.Action(
                            stringResource(MR.strings.action_filter),
                            Icons.Outlined.FilterList,
                            onClick = onFilterClick,
                        ),
                        AppBar.OverflowAction(stringResource(MR.strings.lanraragi_refresh), onRefresh),
                        AppBar.OverflowAction(stringResource(MR.strings.action_settings), onSettings),
                    ),
                )
            }
            DropdownMenu(selectingConnection, { selectingConnection = false }) {
                connectionProfiles.forEach { profile ->
                    RadioMenuItem(
                        text = { Text(profile.name) },
                        isChecked = profile.id == activeConnectionId,
                        leadingIcon = { ConnectionProviderIcon(profile.providerId, Modifier.size(24.dp)) },
                    ) {
                        selectingConnection = false
                        onConnectionSelect(profile.id)
                    }
                }
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(MR.strings.pref_connection_management)) },
                    onClick = {
                        selectingConnection = false
                        onManageConnections()
                    },
                )
            }
            DropdownMenu(selectingDisplayMode, { selectingDisplayMode = false }) {
                listOf(
                    LibraryDisplayMode.ComfortableGrid to MR.strings.action_display_comfortable_grid,
                    LibraryDisplayMode.CompactGrid to MR.strings.action_display_grid,
                    LibraryDisplayMode.CoverOnlyGrid to MR.strings.action_display_cover_only_grid,
                    LibraryDisplayMode.List to MR.strings.action_display_list,
                ).forEach { (mode, label) ->
                    RadioMenuItem(text = { Text(stringResource(label)) }, isChecked = displayMode == mode) {
                        selectingDisplayMode = false
                        onDisplayModeChange(mode)
                    }
                }
            }
        },
    )
}
