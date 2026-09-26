package koharia.connection.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import tachiyomi.presentation.core.theme.active

@Composable
fun ConnectionLibraryToolbar(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    onSearch: (String) -> Unit,
    onCloseSearch: () -> Unit,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    connectionProfiles: List<LibraryConnectionProfile>,
    activeConnectionId: Long,
    onConnectionSelect: (Long) -> Unit,
    onManageConnections: (() -> Unit)? = null,
    onFilterClick: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    navigateUp: (() -> Unit)?,
    showFilterAction: Boolean = true,
    showConnectionAction: Boolean = true,
    hasActiveFilters: Boolean = false,
    additionalActions: List<AppBar.AppBarAction> = emptyList(),
    searchActions: (@Composable () -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var selectingDisplayMode by remember(searchQuery != null) { mutableStateOf(false) }
    var selectingConnection by remember(searchQuery != null) { mutableStateOf(false) }
    SearchToolbar(
        titleContent = { AppBarTitle(stringResource(MR.strings.app_name)) },
        navigateUp = navigateUp,
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onSearch,
        onClickCloseSearch = onCloseSearch,
        scrollBehavior = scrollBehavior,
        actions = {
            if (searchQuery != null) {
                searchActions?.invoke()
                TextButton(
                    enabled = searchQuery.isNotBlank(),
                    onClick = {
                        onSearch(searchQuery)
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    },
                ) {
                    Text(stringResource(MR.strings.action_search), maxLines = 1)
                }
            } else {
                AppBarActions(
                    persistentListOf<AppBar.AppBarAction>().builder().apply {
                        addAll(additionalActions)
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
                        if (showConnectionAction) {
                            add(
                                AppBar.Action(
                                    title = stringResource(MR.strings.pref_connection_management),
                                    icon = Icons.Outlined.Storage,
                                    onClick = { selectingConnection = true },
                                ),
                            )
                        }
                        if (showFilterAction) {
                            add(
                                AppBar.Action(
                                    stringResource(MR.strings.action_filter),
                                    Icons.Outlined.FilterList,
                                    iconTint = if (hasActiveFilters) {
                                        MaterialTheme.colorScheme.active
                                    } else {
                                        LocalContentColor.current
                                    },
                                    onClick = onFilterClick,
                                ),
                            )
                        }
                        onRefresh?.let { add(AppBar.OverflowAction(stringResource(MR.strings.connection_refresh), it)) }
                        onSettings?.let { add(AppBar.OverflowAction(stringResource(MR.strings.action_settings), it)) }
                    }.build(),
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
                if (onManageConnections != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.pref_connection_management)) },
                        onClick = {
                            selectingConnection = false
                            onManageConnections()
                        },
                    )
                }
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
