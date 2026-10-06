package koharia.suwayomi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiMigrationSort
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.secondaryItemAlpha

/**
 * Step 1 of the migration flow: pick the library source to migrate off, exactly like the server
 * client's source picker. Obsolete sources are flagged and sort first so a dead source is easy to
 * find; tapping one opens its entry list.
 */
@Composable
internal fun SuwayomiMigrationTab(
    source: SuwayomiSource,
    modifier: Modifier = Modifier,
) {
    val epoch by source.epoch.collectAsState()
    val screen = LocalNavigator.currentOrThrow.lastItem
    val model: SuwayomiMigrationScreenModel = screen.rememberScreenModel(
        tag = "suwayomi-migration-${source.instanceKey}:$epoch",
    ) {
        SuwayomiMigrationScreenModel(source)
    }
    val state by model.state.collectAsState()
    val navigator = LocalNavigator.currentOrThrow

    remember(state.loaded) { if (!state.loaded && !state.loading) model.load() }

    val groups = model.visibleGroups()
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.padding.small),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = model::toggleObsoleteOnly, enabled = state.sourcesLoaded) {
                Icon(
                    imageVector = Icons.Outlined.NewReleases,
                    contentDescription = stringResource(MR.strings.suwayomi_migration_obsolete_only),
                    tint = if (state.obsoleteOnly) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = model::toggleSortMode) {
                Icon(
                    imageVector = if (state.sort == SuwayomiMigrationSort.ALPHABETICAL) {
                        Icons.Outlined.SortByAlpha
                    } else {
                        Icons.Outlined.Tag
                    },
                    contentDescription = stringResource(MR.strings.suwayomi_migration_sort_mode),
                )
            }
            IconButton(onClick = model::toggleSortDirection) {
                Icon(
                    imageVector = if (state.ascending) {
                        Icons.Outlined.ArrowUpward
                    } else {
                        Icons.Outlined.ArrowDownward
                    },
                    contentDescription = stringResource(MR.strings.suwayomi_migration_sort_direction),
                )
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = model::setQuery,
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = MaterialTheme.padding.medium,
                vertical = MaterialTheme.padding.small,
            ),
            singleLine = true,
            placeholder = { Text(stringResource(MR.strings.suwayomi_migration_search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
        )
        val failure = state.error
        when {
            failure != null && state.groups.isEmpty() -> SuwayomiShelfError(
                error = failure,
                onRetry = model::load,
                onSettings = {},
            )
            !state.loaded -> LoadingScreen()
            groups.isEmpty() -> EmptyScreen(message = stringResource(MR.strings.suwayomi_migration_empty))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(groups, key = { it.sourceId }) { group ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(
                            horizontal = MaterialTheme.padding.medium,
                            vertical = MaterialTheme.padding.small,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(group.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = if (group.isObsolete) {
                                    stringResource(MR.strings.suwayomi_migration_obsolete_source)
                                } else {
                                    stringResource(MR.strings.suwayomi_migration_entry_count, group.count)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (group.isObsolete) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = if (group.isObsolete) Modifier else Modifier.secondaryItemAlpha(),
                            )
                        }
                        TextButton(onClick = {
                            navigator.push(
                                SuwayomiMigrationEntryListScreen(source.id, group.sourceId, group.displayName),
                            )
                        }) {
                            Text(stringResource(MR.strings.migrate))
                        }
                    }
                }
            }
        }
    }
}
