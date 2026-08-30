package koharia.source.local

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
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
import tachiyomi.presentation.core.theme.active

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
    onFilterClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onSettingsClick: () -> Unit,
    navigateUp: (() -> Unit)?,
    onSearch: (String) -> Unit,
    onClickCloseSearch: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    var selectingDisplayMode by remember { mutableStateOf(false) }
    var selectingConnection by remember { mutableStateOf(false) }
    val canSwitchConnection = connectionProfiles.size > 1

    SearchToolbar(
        navigateUp = navigateUp,
        titleContent = { AppBarTitle(stringResource(MR.strings.app_name)) },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onSearch,
        onClickCloseSearch = onClickCloseSearch,
        actions = actions@{
            if (searchQuery != null) return@actions

            val filterTint = if (hasActiveFilters) MaterialTheme.colorScheme.active else LocalContentColor.current
            AppBarActions(
                actions = persistentListOf<AppBar.AppBarAction>().builder()
                    .apply {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.local_library_import_files),
                                icon = Icons.Outlined.UploadFile,
                                onClick = onImportClick,
                            ),
                        )
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
                                    onClick = { selectingConnection = true },
                                ),
                            )
                        }
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_filter),
                                icon = Icons.Outlined.FilterList,
                                iconTint = filterTint,
                                onClick = onFilterClick,
                            ),
                        )
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_webview_refresh),
                                onClick = onRefreshClick,
                            ),
                        )
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.local_library_directories),
                                onClick = onSettingsClick,
                            ),
                        )
                    }
                    .build(),
            )

            if (canSwitchConnection) {
                DropdownMenu(
                    expanded = selectingConnection,
                    onDismissRequest = { selectingConnection = false },
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
                            selectingConnection = false
                            onConnectionSelect(profile.id)
                        }
                    }
                }
            }

            DropdownMenu(
                expanded = selectingDisplayMode,
                onDismissRequest = { selectingDisplayMode = false },
            ) {
                displayModes.forEach { mode ->
                    RadioMenuItem(
                        text = { Text(text = displayModeLabel(mode)) },
                        isChecked = displayMode == mode,
                    ) {
                        selectingDisplayMode = false
                        onDisplayModeChange(mode)
                    }
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

private val displayModes = listOf(
    LibraryDisplayMode.ComfortableGrid,
    LibraryDisplayMode.CompactGrid,
    LibraryDisplayMode.CoverOnlyGrid,
    LibraryDisplayMode.List,
)

@Composable
private fun displayModeLabel(mode: LibraryDisplayMode): String = stringResource(
    when (mode) {
        LibraryDisplayMode.ComfortableGrid -> MR.strings.action_display_comfortable_grid
        LibraryDisplayMode.CompactGrid -> MR.strings.action_display_grid
        LibraryDisplayMode.CoverOnlyGrid -> MR.strings.action_display_cover_only_grid
        LibraryDisplayMode.List -> MR.strings.action_display_list
    },
)
