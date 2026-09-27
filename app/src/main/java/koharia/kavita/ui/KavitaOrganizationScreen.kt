package koharia.kavita.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
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
import koharia.kavita.KavitaFilter
import koharia.kavita.KavitaFilterStatement
import koharia.kavita.textValue
import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KavitaOrganizationScreen(
    private val sourceId: Long,
    private val kind: String,
    private val remoteId: Long,
    private val memberSeriesId: Long? = null,
) : Screen() {
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
        val collection = kind == "Collection"
        var data by remember { mutableStateOf<JsonObject?>(null) }
        var owner by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var action by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("") }
        var summary by remember { mutableStateOf("") }
        val export = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/xml"),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val bytes = koharia.kavita.KavitaCbl(session.catalog, session::checkActive).export(remoteId)
                            requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
                        }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        error = context.kavitaError(failure)
                    }
                }
            }
        }
        fun run(block: suspend () -> Unit) {
            if (busy) return
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        block()
                        session.checkActive()
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = context.kavitaError(failure)
                } finally {
                    busy = false
                }
            }
        }
        suspend fun load(refresh: Boolean) {
            val value = if (collection) {
                session.organization.collection(
                    remoteId,
                    refresh,
                )
            } else {
                session.organization.readingList(remoteId, refresh)
            }
            data = value
            title = value.textValue("title")
            summary = value.textValue("summary")
            val ownerName = value.textValue(if (collection) "owner" else "ownerUserName")
            owner = ownerName.equals(source.preferences.principal, true) &&
                source.preferences.capabilities.writable
        }
        LaunchedEffect(source.instanceKey) { run { load(false) } }
        Scaffold(topBar = {
            AppBar(title = data?.textValue("title").orEmpty(), navigateUp = { navigator.pop() }, actions = {
                TextButton(enabled = !busy, onClick = {
                    run { load(true) }
                }) { Text(stringResource(MR.strings.connection_refresh)) }
            })
        }) { padding ->
            if (data == null && error == null) {
                LoadingScreen()
            } else {
                LazyColumn(Modifier.padding(padding)) {
                    item {
                        if (summary.isNotBlank()) Text(summary)
                        if (!collection) {
                            KavitaActionRow(MR.strings.kavita_cbl_export, !busy) {
                                export.launch("Kavita reading list.cbl")
                            }
                        }
                        KavitaActionRow(MR.strings.kavita_view_members, !busy) {
                            navigator.push(
                                if (collection) {
                                    KavitaLibraryScreen(
                                        sourceId,
                                        null,
                                        true,
                                        session.api.json.encodeToString(
                                            KavitaFilter(
                                                listOf(KavitaFilterStatement(7, 0, remoteId.toString())),
                                            ),
                                        ),
                                    )
                                } else {
                                    KavitaExploreScreen(sourceId, "ReadingList/items", remoteId)
                                },
                            )
                        }
                        if (owner) {
                            KavitaActionRow(MR.strings.kavita_add_members, !busy) {
                                navigator.push(
                                    KavitaLibraryScreen(
                                        sourceId,
                                        null,
                                        true,
                                        selection = KavitaMemberSelection(kind, remoteId),
                                    ),
                                )
                            }
                            if (collection && memberSeriesId != null) {
                                KavitaActionRow(MR.strings.kavita_add_this_series, !busy) {
                                    run {
                                        session.organization.addCollection(memberSeriesId, remoteId)
                                        load(true)
                                    }
                                }
                                KavitaActionRow(MR.strings.kavita_remove_this_series, !busy) { action = "removeMember" }
                            }
                            KavitaActionRow(MR.strings.action_edit, !busy) { action = "edit" }
                            KavitaActionRow(MR.strings.action_delete, !busy) { action = "delete" }
                        }
                    }
                }
            }
        }
        if (action.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { if (!busy) action = "" },
                title = { Text(data?.textValue("title").orEmpty()) },
                text = {
                    if (action == "edit") {
                        Column {
                            OutlinedTextField(title, {
                                title = it
                            }, label = { Text(stringResource(MR.strings.kavita_name)) })
                            OutlinedTextField(summary, {
                                summary = it
                            }, label = { Text(stringResource(MR.strings.kavita_summary)) })
                        }
                    } else {
                        Text(
                            stringResource(
                                if (action == "delete") {
                                    MR.strings.kavita_delete_organization_confirm
                                } else {
                                    MR.strings.kavita_remove_this_series
                                },
                            ),
                        )
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy && (action != "edit" || title.isNotBlank()), onClick = {
                        val operation = action
                        run {
                            when (operation) {
                                "edit" -> if (collection) {
                                    session.organization.editCollection(remoteId, title, summary)
                                } else {
                                    session.organization.editList(remoteId, title, summary)
                                }
                                "delete" -> {
                                    if (collection) {
                                        session.organization.deleteCollection(remoteId)
                                    } else {
                                        session.organization.deleteList(remoteId)
                                    }
                                }
                                "removeMember" -> session.organization.removeCollectionMember(
                                    remoteId,
                                    requireNotNull(memberSeriesId),
                                )
                            }
                            action = ""
                            if (operation == "delete") withContext(Dispatchers.Main) { navigator.pop() } else load(true)
                        }
                    }) { Text(stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { action = "" }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
        KavitaErrorDialog(error) { error = null }
    }
}

data class KavitaMemberSelection(val kind: String, val id: Long) : java.io.Serializable
