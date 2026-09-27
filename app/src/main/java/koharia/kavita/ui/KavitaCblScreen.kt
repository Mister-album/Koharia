package koharia.kavita.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import koharia.kavita.KavitaCbl
import koharia.kavita.KavitaCblDecision
import koharia.kavita.KavitaCblImport
import koharia.kavita.KavitaChapter
import koharia.kavita.KavitaFilter
import koharia.kavita.KavitaFilterStatement
import koharia.kavita.KavitaSeries
import koharia.kavita.KavitaSeriesPage
import koharia.kavita.readBytesBounded
import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KavitaCblScreen(private val sourceId: Long) : Screen() {
    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? KavitaSource
        if (source == null || !source.hasValidConnection()) {
            EmptyScreen(stringRes = MR.strings.connection_unavailable)
            return
        }
        val session = remember(source) { source.session() }
        val cbl = remember(session) { KavitaCbl(session.catalog, session::checkActive) }
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var import by remember { mutableStateOf<KavitaCblImport?>(null) }
        var decisions by remember { mutableStateOf<Map<Int, KavitaCblDecision>>(emptyMap()) }
        var matching by remember { mutableStateOf<Int?>(null) }
        var confirm by remember { mutableStateOf(false) }
        var attempted by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        fun run(block: suspend () -> Unit) {
            if (busy) return
            busy = true
            scope.launch {
                try {
                    block()
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = context.kavitaError(failure)
                } finally {
                    busy = false
                }
            }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                run {
                    import = withContext(Dispatchers.IO) {
                        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use {
                            it.readBytesBounded(KavitaCbl.MAX_BYTES)
                        }
                        cbl.upload(
                            bytes,
                            bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("{"),
                        )
                    }
                    decisions = emptyMap()
                    attempted = false
                }
            }
        }
        Scaffold(topBar = {
            AppBar(title = stringResource(MR.strings.kavita_cbl_import), navigateUp = { navigator.pop() }, actions = {
                TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text(stringResource(MR.strings.kavita_choose_file))
                }
            })
        }) { padding ->
            LazyColumn(Modifier.padding(padding)) {
                item {
                    Text(stringResource(MR.strings.kavita_cbl_help))
                    import?.let { Text(it.summary.cblName) }
                    if (attempted) {
                        Text(stringResource(MR.strings.kavita_cbl_uncertain))
                        TextButton(onClick = { navigator.push(KavitaExploreScreen(sourceId, "ReadingList")) }) {
                            Text(stringResource(MR.strings.kavita_reading_lists))
                        }
                    }
                }
                items(import?.summary?.items.orEmpty(), key = { it.order }) { item ->
                    val decision = decisions[item.order]
                    ListItem(
                        headlineContent = { Text("${item.order + 1}. ${item.series} ${item.volume} · ${item.number}") },
                        supportingContent = {
                            Text(
                                if (decision != null) {
                                    stringResource(MR.strings.kavita_cbl_manual_match)
                                } else if (item.reason ==
                                    8
                                ) {
                                    item.matchedSeriesName.ifBlank { item.series } + " · " + item.chapterTitle
                                } else {
                                    stringResource(MR.strings.kavita_cbl_unmatched)
                                },
                            )
                        },
                        modifier = Modifier.clickable(!busy && !attempted) { matching = item.order },
                    )
                }
                if (import != null) {
                    item {
                        TextButton(
                            enabled = !busy && !attempted && import!!.summary.items.isNotEmpty() &&
                                import!!.summary.results.none { it.reason in listOf(5, 9) },
                            onClick = { confirm = true },
                        ) {
                            Text(stringResource(MR.strings.kavita_cbl_import))
                        }
                    }
                }
            }
        }
        matching?.let { order ->
            KavitaChapterPicker(source, onDismiss = { matching = null }) { series, chapter ->
                decisions = decisions + (order to KavitaCblDecision(series.id, chapter.volumeId, chapter.id))
                matching = null
            }
        }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                text = {
                    Text(
                        stringResource(
                            if (import?.summary?.isUpdate ==
                                true
                            ) {
                                MR.strings.kavita_cbl_update_confirm
                            } else {
                                MR.strings.kavita_cbl_import_confirm
                            },
                        ),
                    )
                },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        confirm = false
                        run {
                            attempted = true
                            val result = withContext(Dispatchers.IO) { cbl.finish(requireNotNull(import), decisions) }
                            if (result.verifiedAfterError) {
                                context.toast(MR.strings.kavita_cbl_verified_after_error)
                            }
                            if (result.readingListId >
                                0
                            ) {
                                navigator.replace(
                                    KavitaOrganizationScreen(sourceId, "ReadingList", result.readingListId),
                                )
                            } else {
                                error =
                                    context.kavitaError(
                                        koharia.kavita.KavitaException(koharia.kavita.KavitaException.Reason.PROTOCOL),
                                    )
                            }
                        }
                    }) { Text(stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirm = false }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
        KavitaErrorDialog(error) { error = null }
    }
}

/** Every search is server-paginated, including manual CBL matching outside the current shelf page. */
@Composable
internal fun KavitaChapterPicker(
    source: KavitaSource,
    onDismiss: () -> Unit,
    onSelect: (KavitaSeries, KavitaChapter) -> Unit,
) {
    val session = remember(source) { source.session() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(1) }
    var results by remember { mutableStateOf<KavitaSeriesPage?>(null) }
    var selected by remember { mutableStateOf<KavitaSeries?>(null) }
    var chapters by remember { mutableStateOf<List<KavitaChapter>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(submitted, page, selected) {
        busy = true
        error = null
        try {
            withContext(Dispatchers.IO) {
                if (selected == null) {
                    results = session.catalog.page(
                        page,
                        KavitaFilter(
                            if (submitted.isBlank()) emptyList() else listOf(KavitaFilterStatement(1, 5, submitted)),
                        ),
                    )
                } else {
                    chapters = session.catalog.volumes(selected!!.id).flatMap { volume ->
                        volume.chapters.map { it.copy(volumeId = volume.id) }
                    }.distinctBy { it.id }
                }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = context.kavitaError(failure)
        } finally {
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.kavita_cbl_match)) },
        text = {
            Column {
                if (selected == null) {
                    OutlinedTextField(query, { query = it }, label = { Text(stringResource(MR.strings.action_search)) })
                    TextButton(enabled = !busy, onClick = {
                        page = 1
                        submitted = query
                    }) { Text(stringResource(MR.strings.action_search)) }
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(results?.items.orEmpty(), key = { it.id }) { item ->
                            ListItem(
                                headlineContent = { Text(item.name) },
                                modifier = Modifier.clickable(!busy) {
                                    chapters = emptyList()
                                    selected = item
                                },
                            )
                        }
                    }
                    Row {
                        TextButton(enabled = !busy && page > 1, onClick = { page-- }) { Text("‹") }
                        Text("$page")
                        TextButton(enabled = !busy && results?.hasNext == true, onClick = { page++ }) { Text("›") }
                    }
                } else {
                    TextButton(onClick = { selected = null }) { Text(selected!!.name) }
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(chapters, key = { it.id }) { chapter ->
                            ListItem(
                                headlineContent = {
                                    Text(chapter.titleName.ifBlank { chapter.title }.ifBlank { chapter.range })
                                },
                                modifier = Modifier.clickable(!busy) { onSelect(selected!!, chapter) },
                            )
                        }
                    }
                }
                error?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}
