package koharia.suwayomi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiMigrationCandidate
import koharia.suwayomi.SuwayomiMigrationMatch
import koharia.suwayomi.SuwayomiMigrationOptions
import koharia.suwayomi.SuwayomiMigrationOutcome
import koharia.suwayomi.SuwayomiMigrationPhase
import koharia.suwayomi.SuwayomiMigrationRunFilters
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.EInkCircularProgressIndicator
import tachiyomi.presentation.core.components.EInkLinearProgressIndicator
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Step 4: the migration list. Every selected entry is matched against the target sources; the top
 * bar commits the batch as a migration (remove the old entry) or a copy (keep it), and each row can
 * be re-searched, skipped or committed alone.
 */
class SuwayomiMigrationRunScreen(
    private val sourceId: Long,
    private val draftId: Long,
) : Screen() {
    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? SuwayomiSource
        if (source == null || !source.hasValidConnection()) {
            EmptyScreen(stringRes = MR.strings.connection_unavailable)
            return
        }
        val epoch by source.epoch.collectAsState()
        val model = rememberScreenModel(tag = "${source.instanceKey}:$epoch:suwayomi-migration-run-$draftId") {
            SuwayomiMigrationRunScreenModel(source, draftId)
        }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val snackbar = remember { SnackbarHostState() }
        val context = LocalContext.current
        var showOptions by remember { mutableStateOf(false) }
        var pendingCommit by remember { mutableStateOf<Boolean?>(null) }

        remember(state.loaded) { if (!state.loaded && !state.searching) model.start() }
        LaunchedEffect(model) {
            model.errors.collect { error -> snackbar.showSnackbar(context.suwayomiError(error)) }
        }
        val resultMessage = if (state.results.isEmpty()) {
            null
        } else {
            stringResource(
                MR.strings.suwayomi_migration_result,
                state.results.count { it.succeeded },
                state.results.count { !it.succeeded },
            )
        }
        LaunchedEffect(resultMessage) {
            if (resultMessage != null) snackbar.showSnackbar(resultMessage)
        }

        val visible = remember(state.candidates, state.phases, state.filters) {
            state.candidates.filter { candidate ->
                val phase = state.phases[candidate.from.id] ?: SuwayomiMigrationPhase.QUEUED
                !(state.filters.hideUnmatched && phase == SuwayomiMigrationPhase.NO_MATCH)
            }
        }
        val committable = state.phases.count { it.value == SuwayomiMigrationPhase.READY }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(
                        MR.strings.suwayomi_migration_progress,
                        state.phases.count { it.value != SuwayomiMigrationPhase.QUEUED },
                        state.candidates.size,
                    ),
                    navigateUp = navigator::pop,
                    actions = {
                        IconButton(onClick = { showOptions = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = stringResource(MR.strings.suwayomi_migration_settings),
                            )
                        }
                        IconButton(
                            onClick = { pendingCommit = false },
                            enabled = committable > 0 && !state.committing,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(MR.strings.suwayomi_migration_copy),
                            )
                        }
                        IconButton(
                            onClick = { pendingCommit = true },
                            enabled = committable > 0 && !state.committing,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DoneAll,
                                contentDescription = stringResource(MR.strings.migrate),
                            )
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.committing) {
                    EInkLinearProgressIndicator(
                        progress = {
                            if (state.commitTotal == 0) 0f else state.committed.toFloat() / state.commitTotal
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                when {
                    state.missingTargets -> EmptyScreen(
                        message = stringResource(MR.strings.suwayomi_migration_no_sources),
                    )
                    !state.loaded -> LoadingScreen()
                    visible.isEmpty() -> EmptyScreen(message = stringResource(MR.strings.no_results_found))
                    else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp)) {
                        items(visible, key = { it.from.id }) { candidate ->
                            MigrationRow(
                                candidate = candidate,
                                phase = state.phases[candidate.from.id] ?: SuwayomiMigrationPhase.QUEUED,
                                match = state.matches[candidate.from.id],
                                source = source,
                                onSkip = { model.skip(candidate.from.id) },
                                onMigrateNow = { model.commit(deleteSource = true, only = candidate.from.id) },
                                onCopyNow = { model.commit(deleteSource = false, only = candidate.from.id) },
                            )
                        }
                    }
                }
            }
        }

        if (showOptions) {
            SuwayomiMigrationOptionsSheet(
                options = state.options,
                filters = state.filters,
                onDismiss = { showOptions = false },
                onApply = { options, filters, extra ->
                    model.setOptions(options)
                    model.setFilters(filters)
                    model.setExtraSearchQuery(extra)
                    showOptions = false
                },
            )
        }

        pendingCommit?.let { deleteSource ->
            AlertDialog(
                onDismissRequest = { pendingCommit = null },
                title = {
                    Text(
                        stringResource(
                            if (deleteSource) {
                                MR.strings.suwayomi_migration_confirm_migrate
                            } else {
                                MR.strings.suwayomi_migration_confirm_copy
                            },
                            committable,
                        ),
                    )
                },
                text = {
                    Text(
                        stringResource(
                            if (deleteSource) {
                                MR.strings.suwayomi_migration_confirm_migrate_body
                            } else {
                                MR.strings.suwayomi_migration_confirm_copy_body
                            },
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingCommit = null
                        model.commit(deleteSource = deleteSource)
                    }) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingCommit = null }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        if (state.results.isNotEmpty() && !state.committing) {
            MigrationResultDialog(state.results, context, model::clearResults)
        }
    }
}

@Composable
private fun MigrationRow(
    candidate: SuwayomiMigrationCandidate,
    phase: SuwayomiMigrationPhase,
    match: SuwayomiMigrationMatch?,
    source: SuwayomiSource,
    onSkip: () -> Unit,
    onMigrateNow: () -> Unit,
    onCopyNow: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val session = remember(source) { source.session() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(5f)) {
            SuwayomiPreviewableThumbnail(
                ref = SuwayomiImageRef(
                    url = candidate.from.thumbnailUrl?.let(session.api::resourceUrlOrNull),
                    client = session.api.client,
                    api = session.api,
                ),
                title = candidate.from.title,
                subtitle = candidate.fromSourceName,
            )
            Text(
                text = candidate.from.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = candidate.fromSourceName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.secondaryItemAlpha(),
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.ArrowForward, null, Modifier.size(20.dp))
        }
        Column(Modifier.weight(5f)) {
            when (phase) {
                SuwayomiMigrationPhase.QUEUED, SuwayomiMigrationPhase.SEARCHING ->
                    EInkCircularProgressIndicator(Modifier.size(24.dp))
                SuwayomiMigrationPhase.NO_MATCH -> Text(
                    text = stringResource(MR.strings.suwayomi_migration_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                SuwayomiMigrationPhase.COPYING -> Text(stringResource(MR.strings.suwayomi_migration_copying))
                SuwayomiMigrationPhase.DONE -> Text(stringResource(MR.strings.suwayomi_migration_done))
                SuwayomiMigrationPhase.FAILED -> Text(
                    text = stringResource(MR.strings.suwayomi_migration_failed),
                    color = MaterialTheme.colorScheme.error,
                )
                SuwayomiMigrationPhase.READY -> {
                    val target = match?.target
                    if (target == null) {
                        Text(stringResource(MR.strings.suwayomi_migration_no_match))
                    } else {
                        SuwayomiPreviewableThumbnail(
                            ref = SuwayomiImageRef(
                                url = target.thumbnailUrl?.let(session.api::resourceUrlOrNull),
                                client = session.api.client,
                                api = session.api,
                            ),
                            title = target.title,
                            subtitle = match.targetSourceName,
                        )
                        Text(
                            text = target.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            text = buildString {
                                append(match.targetSourceName.orEmpty())
                                if (match.confidence > 0) {
                                    append(" · ")
                                    append((match.confidence * 100).toInt())
                                    append('%')
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.secondaryItemAlpha(),
                        )
                    }
                }
            }
        }
        Box(Modifier.size(40.dp)) {
            if (phase == SuwayomiMigrationPhase.QUEUED || phase == SuwayomiMigrationPhase.SEARCHING) {
                IconButton(onClick = onSkip) { Icon(Icons.Outlined.SwapHoriz, null) }
            } else if (phase == SuwayomiMigrationPhase.READY) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, null) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.suwayomi_migration_skip)) },
                        onClick = {
                            menu = false
                            onSkip()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.migrate)) },
                        onClick = {
                            menu = false
                            onMigrateNow()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.suwayomi_migration_copy)) },
                        onClick = {
                            menu = false
                            onCopyNow()
                        },
                    )
                }
            } else if (phase == SuwayomiMigrationPhase.DONE) {
                Icon(
                    imageVector = Icons.Filled.Done,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    HorizontalDivider()
}

/** The data-to-migrate sheet: carry-over chips, an extra query and the list filters. */
@Composable
internal fun SuwayomiMigrationOptionsSheet(
    options: SuwayomiMigrationOptions,
    filters: SuwayomiMigrationRunFilters,
    onDismiss: () -> Unit,
    onApply: (SuwayomiMigrationOptions, SuwayomiMigrationRunFilters, String?) -> Unit,
) {
    var draft by remember { mutableStateOf(options) }
    var draftFilters by remember { mutableStateOf(filters) }
    var extraQuery by remember { mutableStateOf("") }
    BackHandler(enabled = true, onBack = onDismiss)
    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            Text(
                text = stringResource(MR.strings.suwayomi_migration_data_to_migrate),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.weight(1f, fill = false)) {
                item(key = "chips") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        FlagChip(
                            label = stringResource(MR.strings.suwayomi_migration_carry_chapters),
                            selected = draft.migrateChapters,
                        ) { draft = draft.copy(migrateChapters = it) }
                        FlagChip(
                            label = stringResource(MR.strings.suwayomi_migration_carry_categories),
                            selected = draft.migrateCategories,
                        ) { draft = draft.copy(migrateCategories = it) }
                        FlagChip(
                            label = stringResource(MR.strings.suwayomi_migration_carry_tracking),
                            selected = draft.migrateTracking,
                        ) { draft = draft.copy(migrateTracking = it) }
                        FlagChip(
                            label = stringResource(MR.strings.suwayomi_migration_carry_reader),
                            selected = draft.migrateReaderSettings,
                        ) { draft = draft.copy(migrateReaderSettings = it) }
                        FlagChip(
                            label = stringResource(MR.strings.suwayomi_migration_carry_downloads),
                            selected = draft.migrateDownloads,
                        ) { draft = draft.copy(migrateDownloads = it) }
                    }
                }
                item(key = "extra-query") {
                    OutlinedTextField(
                        value = extraQuery,
                        onValueChange = { extraQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        singleLine = true,
                        label = { Text(stringResource(MR.strings.suwayomi_migration_extra_query)) },
                        supportingText = { Text(stringResource(MR.strings.suwayomi_migration_extra_query_hint)) },
                    )
                }
                item(key = "hide-unmatched") {
                    CheckboxItem(
                        label = stringResource(MR.strings.suwayomi_migration_hide_unmatched),
                        checked = draftFilters.hideUnmatched,
                    ) {
                        draftFilters = draftFilters.copy(hideUnmatched = !draftFilters.hideUnmatched)
                    }
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(MR.strings.action_apply)) },
                    icon = { Icon(Icons.Filled.Done, null) },
                    onClick = { onApply(draft, draftFilters, extraQuery.trim().takeIf(String::isNotEmpty)) },
                )
            }
        }
    }
}

@Composable
private fun FlagChip(label: String, selected: Boolean, onSelected: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onSelected(!selected) },
        label = { Text(label) },
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun MigrationResultDialog(
    results: List<SuwayomiMigrationOutcome>,
    context: android.content.Context,
    onDismiss: () -> Unit,
) {
    val failed = results.count { !it.succeeded }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.migrate)) },
        text = {
            Column {
                Text(stringResource(MR.strings.suwayomi_migration_result, results.size - failed, failed))
                results.filter { it.warnings.isNotEmpty() || !it.succeeded }.take(5).forEach { outcome ->
                    Text(
                        text = outcome.failure?.let(context::suwayomiError)
                            ?: outcome.warnings.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_ok)) }
        },
    )
}
