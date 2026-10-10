package koharia.komga.ui.organization

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import koharia.komga.api.KomgaOrganizationKind
import koharia.komga.api.KomgaReadListMatch
import koharia.komga.domain.repository.KomgaOrganizationRepository
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class KomgaImportState(
    val busy: Boolean = false,
    val error: Throwable? = null,
    val match: KomgaReadListMatch? = null,
    val name: String = "",
    val summary: String = "",
    val ordered: Boolean = true,
    val chosen: List<String?> = emptyList(),
    val createdId: String? = null,
)

class KomgaImportModel(val repository: KomgaOrganizationRepository) :
    StateScreenModel<KomgaImportState>(KomgaImportState()) {
    fun name(value: String) {
        mutableState.update { it.copy(name = value) }
    }

    fun summary(value: String) {
        mutableState.update { it.copy(summary = value) }
    }

    fun ordered(value: Boolean) {
        mutableState.update { it.copy(ordered = value) }
    }

    fun choose(index: Int, id: String?) {
        mutableState.update { it.copy(chosen = it.chosen.toMutableList().apply { set(index, id) }) }
    }

    internal fun match(file: suspend () -> KomgaOrganizationFile) = action {
        repository.authorize()
        val upload = file()
        val result = repository.api.matchComicRack(upload.name, upload.bytes)
        repository.checkActive()
        mutableState.update {
            it.copy(
                match = result,
                name = result.readListMatch.name,
                chosen =
                result.requests.map { request ->
                    request.matches.flatMap { it.books }.distinctBy { it.bookId }.singleOrNull()?.bookId
                },
            )
        }
    }

    fun create() = action {
        val state = state.value
        val created =
            repository.save(
                KomgaOrganizationKind.READ_LIST,
                null,
                state.name,
                state.summary,
                state.ordered,
                state.chosen.filterNotNull().distinct(),
            )
        mutableState.update { it.copy(createdId = created.id) }
    }

    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        screenModelScope.launchIO {
            mutableState.update { it.copy(busy = true, error = null) }
            try {
                repository.checkActive()
                block()
                repository.checkActive()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = error) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }
}

data class KomgaReadListImportScreen(val sourceId: Long) : Screen() {
    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? KomgaSource ?: return
        val repository =
            remember(source, source.shelfCacheNamespace()) { source.organizationRepository() }
        val model = rememberScreenModel(tag = repository.namespace) { KomgaImportModel(repository) }
        val state by model.state.collectAsState()
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val picker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    model.match { readOrganizationFile(context, uri, 20 * 1024 * 1024) }
                }
            }
        var showMissing by remember { mutableStateOf(true) }
        var showDuplicates by remember { mutableStateOf(true) }
        LaunchedEffect(state.createdId) {
            state.createdId?.let {
                navigator.replace(
                    KomgaOrganizationScreen(sourceId, KomgaOrganizationKind.READ_LIST, it),
                )
            }
        }
        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.komga_import_readlist),
                    navigateUp = { navigator.pop() },
                )
            },
        ) { padding ->
            LazyColumn(contentPadding = padding) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(MR.strings.komga_import_readlist_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(
                            enabled = !state.busy,
                            onClick = { picker.launch(arrayOf("*/*")) },
                        ) {
                            Text(stringResource(MR.strings.komga_choose_file))
                        }
                        state.error?.let {
                            Text(
                                organizationError(context, it),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (state.match != null) {
                            OutlinedTextField(
                                state.name,
                                model::name,
                                label = {
                                    Text(stringResource(MR.strings.komga_organization_name))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                state.summary,
                                model::summary,
                                label = {
                                    Text(stringResource(MR.strings.komga_organization_summary))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(state.ordered, model::ordered)
                                Text(stringResource(MR.strings.komga_manual_order))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(showMissing, { showMissing = it })
                                Text(stringResource(MR.strings.komga_import_show_missing))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(showDuplicates, { showDuplicates = it })
                                Text(stringResource(MR.strings.komga_import_show_duplicates))
                            }
                            Text(
                                stringResource(
                                    MR.strings.komga_import_selected,
                                    state.chosen.filterNotNull().distinct().size,
                                ),
                            )
                            TextButton(
                                enabled =
                                !state.busy &&
                                    state.name.isNotBlank() &&
                                    state.chosen.any { it != null },
                                onClick = model::create,
                            ) {
                                Text(stringResource(MR.strings.action_create))
                            }
                        }
                    }
                }
                itemsIndexed(state.match?.requests.orEmpty()) { index, request ->
                    val selected = state.chosen.getOrNull(index)
                    val duplicate = selected != null && state.chosen.count { it == selected } > 1
                    if ((selected == null && !showMissing) || (duplicate && !showDuplicates)) {
                        return@itemsIndexed
                    }
                    var selecting by remember { mutableStateOf(false) }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            "${index + 1}. ${request.request.series.joinToString()} · ${request.request.number}",
                        )
                        val matches =
                            request.matches.flatMap { match ->
                                match.books.map { match.series.title to it }
                            }
                        TextButton(enabled = !state.busy, onClick = { selecting = true }) {
                            Text(
                                matches
                                    .firstOrNull { it.second.bookId == selected }
                                    ?.let { "${it.first} · ${it.second.title}" }
                                    ?: stringResource(MR.strings.komga_import_unmatched),
                            )
                        }
                        if (duplicate) {
                            Text(
                                stringResource(MR.strings.komga_import_duplicate),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        DropdownMenu(selecting, { selecting = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.komga_import_skip)) },
                                onClick = {
                                    model.choose(index, null)
                                    selecting = false
                                },
                            )
                            matches.forEach { (series, book) ->
                                DropdownMenuItem(
                                    text = { Text("$series · ${book.number} · ${book.title}") },
                                    onClick = {
                                        model.choose(index, book.bookId)
                                        selecting = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
