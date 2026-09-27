package koharia.kavita.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import koharia.kavita.longValue
import koharia.kavita.textValue
import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkLinearProgressIndicator
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KavitaDashboardScreen(private val sourceId: Long) : Screen() {
    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? KavitaSource
        if (source == null || !source.hasValidConnection()) {
            EmptyScreen(stringRes = MR.strings.connection_unavailable)
            return
        }
        val session = remember(source) { source.session() }
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var rows by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        var loaded by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var removing by remember { mutableStateOf<Long?>(null) }
        val writable = source.preferences.capabilities.writable
        suspend fun load(refresh: Boolean = false) {
            rows = session.catalog.resource("Stream/dashboard?visibleOnly=false", refresh)
                .jsonArray.map { it.jsonObject }.sortedBy { it.longValue("order") }
            session.checkActive()
            loaded = true
        }
        fun run(block: suspend () -> Unit) {
            if (busy) return
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { block() }
                    error = null
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = context.kavitaError(failure)
                } finally {
                    busy = false
                }
            }
        }
        LaunchedEffect(session) { run { load() } }
        LaunchedEffect(source) { source.epoch.drop(1).collect { navigator.pop() } }
        Scaffold(topBar = {
            AppBar(
                title = stringResource(MR.strings.kavita_dashboard),
                navigateUp = { navigator.pop() },
                actions = {
                    TextButton(enabled = !busy, onClick = { run { load(true) } }) {
                        Text(stringResource(MR.strings.connection_refresh))
                    }
                },
            )
        }) { padding ->
            LazyColumn(
                Modifier.padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Text(stringResource(MR.strings.kavita_dashboard_help), Modifier.padding(16.dp)) }
                if (busy) item { EInkLinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (loaded && rows.isEmpty()) {
                    item {
                        Text(stringResource(MR.strings.kavita_dashboard_empty), Modifier.padding(16.dp))
                    }
                }
                if (!writable) {
                    item {
                        Text(stringResource(MR.strings.kavita_dashboard_read_only), Modifier.padding(16.dp))
                    }
                }
                itemsIndexed(rows, key = { _, row -> row.longValue("id") }) { index, row ->
                    val id = row.longValue("id")
                    val visible = row["visible"]?.jsonPrimitive?.booleanOrNull == true
                    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text(dashboardTitle(row)) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        if (row.longValue("smartFilterId") > 0) {
                                            MR.strings.kavita_dashboard_custom
                                        } else {
                                            MR.strings.kavita_dashboard_builtin
                                        },
                                    ),
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = visible,
                                    enabled = writable && !busy,
                                    onCheckedChange = { checked ->
                                        run {
                                            session.organization.updateDashboard(id, visible = checked)
                                            load()
                                        }
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription =
                                            context.getString(MR.strings.kavita_dashboard_visible.resourceId)
                                    },
                                )
                            },
                        )
                        if (row.textValue("smartFilterEncoded").isNotBlank() && row.longValue("entityType") == 0L) {
                            TextButton(enabled = !busy, onClick = {
                                run {
                                    val filter = session.catalog.postResource(
                                        "Filter/decode",
                                        buildJsonObject {
                                            put("encodedFilter", row.textValue("smartFilterEncoded"))
                                        },
                                    )
                                    withContext(Dispatchers.Main) {
                                        navigator.push(KavitaLibraryScreen(sourceId, null, true, filter.toString()))
                                    }
                                }
                            }) { Text(stringResource(MR.strings.kavita_open_shelf)) }
                        }
                        if (writable) {
                            Row(Modifier.padding(horizontal = 8.dp)) {
                                for ((delta, label) in listOf(
                                    -1 to MR.strings.kavita_move_up,
                                    1 to MR.strings.kavita_move_down,
                                )) {
                                    TextButton(enabled = !busy && index + delta in rows.indices, onClick = {
                                        run {
                                            session.organization.updateDashboard(id, delta = delta)
                                            load()
                                        }
                                    }) { Text(stringResource(label)) }
                                }
                                if (row.longValue("smartFilterId") > 0) {
                                    TextButton(enabled = !busy, onClick = { removing = id }) {
                                        Text(
                                            stringResource(MR.strings.action_remove),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        removing?.let { id ->
            AlertDialog(
                onDismissRequest = { removing = null },
                text = { Text(stringResource(MR.strings.kavita_dashboard_remove_confirm)) },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        removing = null
                        run {
                            session.organization.updateDashboard(id, remove = true)
                            load()
                        }
                    }) { Text(stringResource(MR.strings.action_remove)) }
                },
                dismissButton = {
                    TextButton(onClick = { removing = null }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
        KavitaErrorDialog(error) { error = null }
    }
}

@Composable
private fun dashboardTitle(row: JsonObject): String {
    val name = row.textValue("name")
    if (row.longValue("smartFilterId") > 0) return name
    val title = when (name) {
        "on-deck" -> MR.strings.kavita_dashboard_title_continue
        "recently-updated" -> MR.strings.kavita_dashboard_title_updated
        "newly-added" -> MR.strings.kavita_dashboard_title_added
        "more-in-genre" -> MR.strings.kavita_dashboard_title_genre
        "want-to-read" -> MR.strings.kavita_dashboard_title_want
        "collections" -> MR.strings.kavita_dashboard_title_collections
        "reading-lists" -> MR.strings.kavita_dashboard_title_lists
        "bookmarks" -> MR.strings.kavita_dashboard_title_bookmarks
        "all-series" -> MR.strings.kavita_dashboard_title_all
        "browse-authors" -> MR.strings.kavita_dashboard_title_authors
        else -> return name.ifBlank { stringResource(MR.strings.kavita_dashboard_unnamed) }
    }
    return stringResource(title)
}
