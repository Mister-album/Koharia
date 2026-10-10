package koharia.komga.ui.organization

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import koharia.komga.api.KomgaOrganization
import koharia.komga.api.KomgaOrganizationKind
import koharia.komga.api.KomgaOrganizationQuery
import koharia.komga.api.KomgaOrganizationThumbnail
import koharia.komga.domain.repository.KomgaOrganizationRepository
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.i18n.stringResource as contextStringResource

data class KomgaEditMember(val id: String, val title: String)

data class KomgaOrganizationEditState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val saved: Boolean = false,
    val error: Throwable? = null,
    val baseline: KomgaOrganization? = null,
    val name: String = "",
    val summary: String = "",
    val ordered: Boolean = false,
    val members: List<KomgaEditMember> = emptyList(),
    val candidates: List<KomgaEditMember> = emptyList(),
    val candidatePage: Int = 0,
    val candidatePages: Int = 0,
    val thumbnails: List<KomgaOrganizationThumbnail> = emptyList(),
)

class KomgaOrganizationEditModel(
    val repository: KomgaOrganizationRepository,
    val kind: KomgaOrganizationKind,
    private val id: String?,
    private val initialMembers: List<String>,
) :
    StateScreenModel<KomgaOrganizationEditState>(
        KomgaOrganizationEditState(ordered = kind == KomgaOrganizationKind.READ_LIST),
    ) {
    init {
        action {
            repository.authorize()
            val current = id?.let { repository.api.detail(kind, it, strict = true) }
            val members =
                if (current == null) {
                    initialMembers.map { member ->
                        if (kind == KomgaOrganizationKind.COLLECTION) {
                            repository.api.series(member).let {
                                KomgaEditMember(it.id, it.metadata.title)
                            }
                        } else {
                            repository.api.book(member).let {
                                KomgaEditMember(it.id, "${it.seriesTitle} · ${it.metadata.title}")
                            }
                        }
                    }
                } else if (kind == KomgaOrganizationKind.COLLECTION) {
                    val found =
                        repository.api
                            .series(current.id, KomgaOrganizationQuery(unpaged = true), true)
                            .content
                            .associateBy { it.id }
                    current.seriesIds.map { KomgaEditMember(it, found[it]?.metadata?.title ?: it) }
                } else {
                    val found =
                        repository.api
                            .books(current.id, KomgaOrganizationQuery(unpaged = true), true)
                            .content
                            .associateBy { it.id }
                    current.bookIds.map {
                        KomgaEditMember(
                            it,
                            found[it]?.let { book ->
                                "${book.seriesTitle} · ${book.metadata.title}"
                            } ?: it,
                        )
                    }
                }
            mutableState.update {
                it.copy(
                    loaded = true,
                    baseline = current,
                    name = current?.name.orEmpty(),
                    summary = current?.summary.orEmpty(),
                    ordered = current?.ordered ?: it.ordered,
                    members = members,
                )
            }
        }
    }

    fun name(value: String) {
        mutableState.update { it.copy(name = value) }
    }

    fun summary(value: String) {
        mutableState.update { it.copy(summary = value) }
    }

    fun ordered(value: Boolean) {
        mutableState.update { it.copy(ordered = value) }
    }

    fun remove(id: String) {
        mutableState.update {
            it.copy(members = it.members.filterNot { member -> member.id == id })
        }
    }

    fun add(member: KomgaEditMember) {
        mutableState.update {
            it.copy(members = (it.members + member).distinctBy { member -> member.id })
        }
    }

    fun reorder(from: Int, to: Int) {
        mutableState.update {
            val members = it.members.toMutableList()
            members.add(to, members.removeAt(from))
            it.copy(members = members)
        }
    }

    fun search(query: String, page: Int = 0) = action {
        val request = KomgaOrganizationQuery(search = query, page = page)
        if (kind == KomgaOrganizationKind.COLLECTION) {
            val found = repository.api.searchSeries(request)
            mutableState.update {
                it.copy(
                    candidates =
                    found.content.map { series ->
                        KomgaEditMember(series.id, series.metadata.title)
                    },
                    candidatePage = page,
                    candidatePages = found.totalPages.toInt(),
                )
            }
        } else {
            val found = repository.api.searchBooks(request)
            mutableState.update {
                it.copy(
                    candidates =
                    found.content.map { book ->
                        KomgaEditMember(book.id, "${book.seriesTitle} · ${book.metadata.title}")
                    },
                    candidatePage = page,
                    candidatePages = found.totalPages.toInt(),
                )
            }
        }
    }

    fun save() = action {
        val state = state.value
        repository.save(
            kind,
            state.baseline,
            state.name,
            state.summary,
            state.ordered,
            state.members.map { it.id },
        )
        mutableState.update { it.copy(saved = true) }
    }

    fun thumbnails() = action {
        repository.authorize()
        val list = repository.api.thumbnails(kind, requireNotNull(id))
        mutableState.update { it.copy(thumbnails = list) }
    }

    fun cover(block: suspend (String) -> Unit) = action {
        repository.authorize()
        block(requireNotNull(id))
        repository.source.organizationChanged()
        val fresh = repository.api.detail(kind, id, strict = true)
        mutableState.update {
            it.copy(baseline = fresh, thumbnails = repository.api.thumbnails(kind, id))
        }
    }

    fun action(block: suspend () -> Unit) {
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

data class KomgaOrganizationEditScreen(
    val sourceId: Long,
    val kind: KomgaOrganizationKind,
    val organizationId: String? = null,
    val initialMembers: List<String> = emptyList(),
) : Screen() {
    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? KomgaSource ?: return
        val repository =
            remember(source, source.shelfCacheNamespace()) { source.organizationRepository() }
        val model =
            rememberScreenModel(
                tag = "${repository.namespace}:$kind:$organizationId:$initialMembers",
            ) {
                KomgaOrganizationEditModel(repository, kind, organizationId, initialMembers)
            }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        var picker by remember { mutableStateOf(false) }
        var covers by remember { mutableStateOf(false) }
        LaunchedEffect(state.saved) { if (state.saved) navigator.pop() }
        val upload =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    model.cover { id ->
                        val file = readOrganizationFile(context, uri, 25 * 1024 * 1024)
                        repository.api.uploadThumbnail(kind, id, file.name, file.bytes, file.type)
                    }
                }
            }
        Scaffold(
            topBar = {
                AppBar(
                    title =
                    stringResource(
                        if (organizationId == null) {
                            MR.strings.action_create
                        } else {
                            MR.strings.action_edit
                        },
                    ),
                    subtitle = stringResource(kind.titleResource()),
                    navigateUp = { navigator.pop() },
                    actions = {
                        TextButton(
                            enabled =
                            state.loaded &&
                                !state.busy &&
                                state.name.isNotBlank() &&
                                (state.members.isNotEmpty() || state.baseline?.filtered == true),
                            onClick = model::save,
                        ) {
                            Text(stringResource(MR.strings.action_save))
                        }
                    },
                )
            },
        ) { padding ->
            if (!state.loaded && state.error == null) {
                LoadingScreen(Modifier.padding(padding))
            } else {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Column(
                        Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.error?.let {
                            Text(
                                organizationError(context, it),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (state.baseline?.filtered == true) {
                            Text(
                                stringResource(MR.strings.komga_organization_filtered),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        OutlinedTextField(
                            state.name,
                            model::name,
                            label = { Text(stringResource(MR.strings.komga_organization_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !state.busy,
                        )
                        if (kind == KomgaOrganizationKind.READ_LIST) {
                            OutlinedTextField(
                                state.summary,
                                model::summary,
                                label = {
                                    Text(stringResource(MR.strings.komga_organization_summary))
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp),
                                enabled = !state.busy,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                state.ordered,
                                model::ordered,
                                enabled = !state.busy && state.baseline?.filtered != true,
                            )
                            Text(stringResource(MR.strings.komga_manual_order))
                        }
                        Row {
                            TextButton(
                                enabled = !state.busy && state.baseline?.filtered != true,
                                onClick = {
                                    picker = true
                                    model.search("")
                                },
                            ) {
                                Text(stringResource(MR.strings.komga_manage_members))
                            }
                            if (organizationId != null) {
                                TextButton(
                                    enabled = !state.busy,
                                    onClick = {
                                        covers = true
                                        model.thumbnails()
                                    },
                                ) {
                                    Text(stringResource(MR.strings.action_edit_cover))
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    val listState = rememberLazyListState()
                    val reorder =
                        rememberReorderableLazyListState(listState) { from, to ->
                            model.reorder(from.index, to.index)
                        }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.members, key = { it.id }) { member ->
                            ReorderableItem(reorder, member.id) {
                                ElevatedCard(Modifier.padding(horizontal = 16.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (state.ordered) {
                                            Icon(
                                                Icons.Outlined.DragHandle,
                                                stringResource(MR.strings.action_sort),
                                                Modifier.padding(8.dp)
                                                    .draggableHandle(
                                                        enabled =
                                                        !state.busy &&
                                                            state.baseline?.filtered != true,
                                                    ),
                                            )
                                        }
                                        Text(
                                            member.title,
                                            Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        IconButton(
                                            enabled =
                                            !state.busy &&
                                                state.baseline?.filtered != true &&
                                                state.members.size > 1,
                                            onClick = { model.remove(member.id) },
                                        ) {
                                            Icon(
                                                Icons.Outlined.Delete,
                                                stringResource(MR.strings.action_remove),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (picker) KomgaMemberPicker(model, { picker = false })
        if (covers) {
            AdaptiveSheet(onDismissRequest = { covers = false }) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(MR.strings.action_edit_cover),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(
                        enabled = !state.busy,
                        onClick = { upload.launch(arrayOf("image/*")) },
                    ) {
                        Text(stringResource(MR.strings.komga_upload_cover))
                    }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(state.thumbnails, key = { it.id }) { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                eu.kanade.presentation.manga.components.MangaCover.Book(
                                    data =
                                    tachiyomi.domain.manga.model.MangaCover(
                                        KomgaOrganizationRepository.presentationId(item.id),
                                        sourceId,
                                        false,
                                        "${source.baseUrl}/api/v1/${kind.path}/$organizationId/thumbnails/${item.id}",
                                        0L,
                                    ),
                                    modifier = Modifier.padding(8.dp).heightIn(max = 100.dp),
                                )
                                TextButton(
                                    enabled = !state.busy && !item.selected,
                                    onClick = {
                                        model.cover {
                                            repository.api.selectThumbnail(kind, it, item.id)
                                        }
                                    },
                                ) {
                                    Text(
                                        stringResource(
                                            if (item.selected) {
                                                MR.strings.komga_cover_selected
                                            } else {
                                                MR.strings.komga_select_cover
                                            },
                                        ),
                                    )
                                }
                                if (item.type == "USER_UPLOADED") {
                                    IconButton(
                                        enabled = !state.busy,
                                        onClick = {
                                            model.cover {
                                                repository.api.deleteThumbnail(kind, it, item.id)
                                            }
                                        },
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            stringResource(MR.strings.action_delete),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    state.error?.let {
                        Text(
                            organizationError(context, it),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KomgaMemberPicker(model: KomgaOrganizationEditModel, dismiss: () -> Unit) {
    val state by model.state.collectAsState()
    var query by remember { mutableStateOf("") }
    AdaptiveSheet(onDismissRequest = dismiss) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(MR.strings.komga_manage_members),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                query,
                { query = it },
                singleLine = true,
                label = { Text(stringResource(MR.strings.action_search)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                TextButton(enabled = !state.busy, onClick = { model.search(query) }) {
                    Text(stringResource(MR.strings.action_search))
                }
                TextButton(onClick = dismiss) { Text(stringResource(MR.strings.action_close)) }
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(state.candidates, key = { it.id }) { member ->
                    val selected = state.members.any { it.id == member.id }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            selected,
                            { if (it) model.add(member) else model.remove(member.id) },
                            enabled = !state.busy,
                        )
                        Text(member.title, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Row {
                TextButton(
                    enabled = !state.busy && state.candidatePage > 0,
                    onClick = { model.search(query, state.candidatePage - 1) },
                ) {
                    Text(stringResource(MR.strings.komga_previous_page))
                }
                TextButton(
                    enabled = !state.busy && state.candidatePage + 1 < state.candidatePages,
                    onClick = { model.search(query, state.candidatePage + 1) },
                ) {
                    Text(stringResource(MR.strings.komga_next_page))
                }
            }
            state.error?.let {
                Text(
                    organizationError(LocalContext.current, it),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

internal data class KomgaOrganizationFile(val name: String, val bytes: ByteArray, val type: String)

internal suspend fun readOrganizationFile(
    context: android.content.Context,
    uri: Uri,
    limit: Int,
): KomgaOrganizationFile =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name =
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "upload"
        val bytes =
            resolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var count = input.read(buffer)
                while (count >= 0) {
                    require(output.size() + count <= limit) {
                        context.contextStringResource(MR.strings.komga_upload_too_large)
                    }
                    output.write(buffer, 0, count)
                    count = input.read(buffer)
                }
                output.toByteArray()
            } ?: error(context.contextStringResource(MR.strings.komga_organization_file_error))
        KomgaOrganizationFile(name, bytes, resolver.getType(uri) ?: "application/octet-stream")
    }
