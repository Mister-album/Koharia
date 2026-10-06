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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.components.BaseBrowseItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.LocaleHelper
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiMigrationDrafts
import koharia.suwayomi.SuwayomiSourceInfo
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/**
 * Step 3: choose and order the target sources. The selected list is searched in order, so the first
 * source that yields a confident match wins; the rest are the fallbacks.
 */
class SuwayomiMigrationConfigScreen(
    private val sourceId: Long,
    private val draftId: Long,
) : Screen() {
    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? SuwayomiSource
        if (source == null || !source.hasValidConnection()) {
            tachiyomi.presentation.core.screens.EmptyScreen(stringRes = MR.strings.connection_unavailable)
            return
        }
        val epoch by source.epoch.collectAsState()
        val model = rememberScreenModel(tag = "${source.instanceKey}:$epoch:suwayomi-migration-config-$draftId") {
            SuwayomiMigrationConfigScreenModel(source, draftId)
        }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var query by remember { mutableStateOf("") }

        remember(state.loaded) { if (!state.loaded && !state.loading) model.load() }

        val filtered = remember(state.sources, query) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) state.sources else state.sources.filter { it.matches(trimmed) }
        }
        val selected = state.targetSourceIds.mapNotNull { id -> state.sources.firstOrNull { it.id == id } }
        val available = filtered.filter { it.id !in state.targetSourceIds }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.suwayomi_migration_select_targets),
                    navigateUp = navigator::pop,
                    actions = {
                        IconButton(onClick = model::selectPinned) {
                            Icon(
                                imageVector = Icons.Outlined.PushPin,
                                contentDescription = stringResource(MR.strings.suwayomi_migration_select_pinned),
                            )
                        }
                        IconButton(onClick = model::selectNone) {
                            Icon(
                                imageVector = Icons.Outlined.Deselect,
                                contentDescription = stringResource(MR.strings.suwayomi_migration_select_none),
                            )
                        }
                    },
                )
            },
            floatingActionButton = {
                if (selected.isNotEmpty()) {
                    ExtendedFloatingActionButton(
                        text = { Text(stringResource(MR.strings.action_continue)) },
                        icon = { Icon(Icons.Filled.ArrowForward, null) },
                        onClick = { navigator.push(SuwayomiMigrationRunScreen(sourceId, draftId)) },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (!state.loaded) {
                    LoadingScreen()
                    return@Column
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = MaterialTheme.padding.medium,
                        vertical = MaterialTheme.padding.small,
                    ),
                    singleLine = true,
                    placeholder = { Text(stringResource(MR.strings.suwayomi_migration_search_hint)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                )
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    if (selected.isNotEmpty()) {
                        item(key = "selected-header") {
                            SuwayomiSectionHeader(stringResource(MR.strings.suwayomi_migration_selected_header))
                        }
                        items(selected, key = { "selected-${it.id}" }) { info ->
                            SourceRow(
                                info = info,
                                dragIndex = selected.indexOf(info).takeIf { selected.size > 1 },
                                onMove = { up -> model.move(info.id, up) },
                                onToggle = { model.remove(info.id) },
                            )
                        }
                    }
                    if (available.isNotEmpty()) {
                        item(key = "available-header") {
                            SuwayomiSectionHeader(stringResource(MR.strings.suwayomi_migration_available_header))
                        }
                        items(available, key = { "available-${it.id}" }) { info ->
                            SourceRow(info = info, dragIndex = null, onMove = null, onToggle = { model.add(info.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuwayomiSectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = MaterialTheme.padding.medium,
            end = MaterialTheme.padding.medium,
            top = MaterialTheme.padding.medium,
            bottom = MaterialTheme.padding.small,
        ),
    )
}

@Composable
private fun SourceRow(
    info: SuwayomiSourceInfo,
    dragIndex: Int?,
    onMove: ((Boolean) -> Unit)?,
    onToggle: () -> Unit,
) {
    BaseBrowseItem(
        onClickItem = onToggle,
        content = {
            Column(Modifier.padding(horizontal = MaterialTheme.padding.medium).weight(1f)) {
                Text(info.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = stringResource(
                        MR.strings.suwayomi_migration_source_meta,
                        LocaleHelper.getLocalizedDisplayName(info.lang).ifBlank { info.lang.uppercase(Locale.ROOT) },
                        if (info.isPinned) {
                            stringResource(MR.strings.pinned_sources)
                        } else {
                            stringResource(MR.strings.suwayomi_migration_source_unpinned)
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.secondaryItemAlpha(),
                )
            }
        },
        action = {
            if (dragIndex != null && onMove != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        IconButton(onClick = { onMove(true) }) {
                            Icon(
                                imageVector = Icons.Outlined.DragHandle,
                                contentDescription = stringResource(MR.strings.suwayomi_migration_move_up),
                            )
                        }
                    }
                }
            }
            if (dragIndex != null) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = MaterialTheme.padding.small),
                )
            }
        },
    )
    HorizontalDivider()
}

private fun SuwayomiSourceInfo.matches(query: String): Boolean =
    name.contains(query, ignoreCase = true) ||
        lang.contains(query, ignoreCase = true) ||
        id.toString() == query
