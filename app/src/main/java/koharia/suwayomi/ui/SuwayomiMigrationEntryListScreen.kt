package koharia.suwayomi.ui

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
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiMigrationCandidate
import koharia.suwayomi.SuwayomiMigrationDrafts
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Step 2: the entries held by one library source. Select several to migrate them as a batch, or one
 * to move it alone — the same two entry points the server client offers.
 */
class SuwayomiMigrationEntryListScreen(
    private val sourceId: Long,
    private val librarySourceId: Long,
    private val librarySourceName: String,
) : Screen() {
    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? SuwayomiSource
        if (source == null || !source.hasValidConnection()) {
            EmptyScreen(stringRes = MR.strings.connection_unavailable)
            return
        }
        val epoch by source.epoch.collectAsState()
        val session = remember(source, epoch) { source.session() }
        val model =
            rememberScreenModel(tag = "${source.instanceKey}:$epoch:suwayomi-migration-entries-$librarySourceId") {
                SuwayomiMigrationEntriesScreenModel(source, librarySourceId)
            }
        val draft = remember(session, librarySourceId) {
            SuwayomiMigrationDrafts.create(session.identity, librarySourceId, librarySourceName)
        }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var selected by remember(session) { mutableStateOf(emptySet<Int>()) }

        remember(state.loaded) { if (!state.loaded && !state.loading) model.load() }

        val candidates = remember(state.entries) {
            state.entries.map { SuwayomiMigrationCandidate(it, librarySourceName) }
        }
        val selectedCandidates = candidates.filter { it.from.id in selected }

        Scaffold(
            topBar = {
                AppBar(
                    title = if (selected.isEmpty()) {
                        librarySourceName
                    } else {
                        stringResource(MR.strings.suwayomi_migration_selected_count, selected.size)
                    },
                    navigateUp = navigator::pop,
                    actions = {
                        IconButton(onClick = { selected = candidates.mapTo(hashSetOf()) { it.from.id } }) {
                            Icon(
                                imageVector = Icons.Outlined.SelectAll,
                                contentDescription = stringResource(MR.strings.action_select_all),
                            )
                        }
                        if (selected.isNotEmpty()) {
                            IconButton(onClick = { selected = emptySet() }) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = stringResource(MR.strings.suwayomi_migration_select_none),
                                )
                            }
                        }
                    },
                )
            },
            floatingActionButton = {
                if (selectedCandidates.isNotEmpty()) {
                    ExtendedFloatingActionButton(
                        text = { Text(stringResource(MR.strings.action_continue)) },
                        icon = { Icon(Icons.Filled.ArrowForward, null) },
                        onClick = {
                            draft.candidates = selectedCandidates
                            navigator.push(SuwayomiMigrationConfigScreen(sourceId, draft.id))
                        },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when {
                    !state.loaded -> LoadingScreen()
                    state.entries.isEmpty() -> EmptyScreen(message = stringResource(MR.strings.no_results_found))
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                        items(state.entries, key = { it.id }) { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(
                                    horizontal = MaterialTheme.padding.small,
                                    vertical = MaterialTheme.padding.small,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = entry.id in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + entry.id else selected - entry.id
                                    },
                                )
                                SuwayomiPreviewableThumbnail(
                                    ref = SuwayomiImageRef(
                                        url = entry.thumbnailUrl?.let(session.api::resourceUrlOrNull),
                                        client = session.api.client,
                                        api = session.api,
                                    ),
                                    title = entry.title,
                                    subtitle = librarySourceName,
                                    size = 40.dp,
                                )
                                Text(
                                    text = entry.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                        .padding(
                                            start = MaterialTheme.padding.medium,
                                            end = MaterialTheme.padding.small,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
