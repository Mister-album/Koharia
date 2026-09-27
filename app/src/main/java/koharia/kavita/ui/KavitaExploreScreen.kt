package koharia.kavita.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import koharia.epub.EpubReaderLauncher
import koharia.kavita.KavitaFilter
import koharia.kavita.KavitaFilterStatement
import koharia.kavita.KavitaListItem
import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Server-owned organization uses canonical series/chapters, never duplicate local library entries. */
class KavitaExploreScreen(
    private val sourceId: Long,
    private val section: String = "",
    private val readingListId: Long = 0,
) : Screen() {
    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? KavitaSource
        if (source == null ||
            !source.hasValidConnection()
        ) {
            EmptyScreen(stringRes = MR.strings.connection_unavailable)
            return
        }
        val session = remember(source) { source.session() }
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var rows by remember { mutableStateOf<List<JsonObject>?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var createName by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var ownsList by remember { mutableStateOf(false) }
        suspend fun load(refresh: Boolean) {
            withContext(Dispatchers.IO) {
                val result = when (section) {
                    "ReadingList" -> session.api.json.parseToJsonElement(
                        session.api.json.encodeToString(session.catalog.lists(refresh)),
                    )
                    "ReadingList/items" -> session.catalog.resource(
                        "ReadingList/items?readingListId=$readingListId",
                        refresh,
                    )
                    "" -> JsonArray(emptyList())
                    else -> session.catalog.resource(section, refresh)
                }
                if (section == "ReadingList/items") {
                    ownsList = session.organization.readingList(readingListId, refresh).text("ownerUserName")
                        .equals(source.preferences.principal, true)
                }
                session.checkActive()
                rows = result.jsonArray.map { it.jsonObject }.let { values ->
                    if (section == "ReadingList/items") {
                        values.sortedBy { it["order"]?.jsonPrimitive?.longOrNull ?: 0 }
                    } else {
                        values.filter {
                            section != "Filter" || (it["entityType"]?.jsonPrimitive?.longOrNull ?: 0) == 0L
                        }
                    }
                }
            }
        }
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
        fun shelf(statements: List<KavitaFilterStatement>) {
            navigator.push(
                KavitaLibraryScreen(sourceId, null, true, session.api.json.encodeToString(KavitaFilter(statements))),
            )
        }
        LaunchedEffect(section, source.instanceKey) {
            try {
                load(false)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = context.kavitaError(failure)
            }
        }
        Scaffold(topBar = {
            AppBar(
                title = stringResource(MR.strings.kavita_explore),
                navigateUp = { navigator.pop() },
                actions = {
                    if (section.isNotEmpty()) {
                        TextButton(onClick = { run { load(true) } }, enabled = !busy) {
                            Text(stringResource(MR.strings.connection_refresh))
                        }
                    }
                    if (section == "Filter") {
                        TextButton(onClick = { navigator.push(KavitaAdvancedFilterScreen(sourceId)) }) {
                            Text(stringResource(MR.strings.action_add))
                        }
                    }
                    if (section == "ReadingList" && source.preferences.capabilities.writable) {
                        if (source.preferences.capabilities.version >= koharia.kavita.KavitaVersion(0, 9, 1)) {
                            TextButton(onClick = { navigator.push(KavitaCblScreen(sourceId)) }) {
                                Text(stringResource(MR.strings.kavita_cbl_import))
                            }
                        }
                        TextButton(onClick = { createName = "" }) { Text(stringResource(MR.strings.action_add)) }
                    }
                },
            )
        }) { padding ->
            if (section.isEmpty()) {
                val entries = listOf(
                    "continue" to MR.strings.kavita_continue,
                    "recent" to MR.strings.kavita_sort_created,
                    "updated" to MR.strings.kavita_sort_updated,
                    "want" to MR.strings.kavita_want_to_read,
                    "Collection" to MR.strings.kavita_collections,
                    "ReadingList" to MR.strings.kavita_reading_lists,
                    "Metadata/genres" to MR.strings.genres,
                    "Metadata/tags" to MR.strings.kavita_tags,
                    "Metadata/people-by-role?role=3" to MR.strings.author,
                    "Filter" to MR.strings.kavita_smart_filters,
                    "stats" to MR.strings.kavita_statistics,
                ) + if (source.preferences.capabilities.version >= koharia.kavita.KavitaVersion(0, 9, 1)) {
                    listOf("annotations" to MR.strings.kavita_shared_annotations)
                } else {
                    emptyList()
                }
                LazyColumn(Modifier.padding(padding)) {
                    items(entries) { (path, label) ->
                        ListItem(
                            headlineContent = { Text(stringResource(label)) },
                            modifier = Modifier.clickable {
                                when (path) {
                                    "stats" -> navigator.push(KavitaPersonalScreen(sourceId, path))
                                    "annotations" -> navigator.push(KavitaAnnotationBrowserScreen(sourceId))
                                    "continue" -> shelf(
                                        listOf(KavitaFilterStatement(20, 1, "0"), KavitaFilterStatement(20, 3, "100")),
                                    )
                                    "want" -> shelf(listOf(KavitaFilterStatement(26, 0, "true")))
                                    "recent", "updated" -> {
                                        source.preferences.order = if (path == "recent") "2 desc" else "4 desc"
                                        navigator.push(KavitaLibraryScreen(sourceId, null, true))
                                    }
                                    else -> navigator.push(KavitaExploreScreen(sourceId, path))
                                }
                            },
                        )
                    }
                }
            } else if (rows == null && error == null) {
                LoadingScreen()
            } else {
                LazyColumn(Modifier.padding(padding)) {
                    items(rows.orEmpty(), key = { it["id"].toString() }) { row ->
                        val remoteId = row["id"]?.jsonPrimitive?.longOrNull ?: 0
                        val title = row.text("title").ifBlank { row.text("name") }.ifBlank { row.text("seriesName") }
                        ListItem(
                            headlineContent = { Text(title) },
                            supportingContent = row.text("summary").takeIf {
                                it.isNotBlank()
                            }?.let { { Text(it, maxLines = 3) } },
                            modifier = Modifier.clickable(enabled = !busy) {
                                when {
                                    section == "ReadingList" -> navigator.push(
                                        KavitaOrganizationScreen(sourceId, "ReadingList", remoteId),
                                    )
                                    section == "ReadingList/items" -> run {
                                        val item = session.api.json.decodeFromString<KavitaListItem>(row.toString())
                                        val chapter = withContext(Dispatchers.IO) {
                                            val manga = source.materialize(
                                                source.toManga(session.catalog.series(item.seriesId)),
                                            )
                                            Injekt.get<SyncChaptersWithSource>().await(
                                                source.getChapterList(manga.toSManga()),
                                                manga,
                                                source,
                                            )
                                            Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).first {
                                                session.identity.chapter(it.url).chapterId == item.chapterId
                                            }
                                        }
                                        val queue = withContext(Dispatchers.IO) {
                                            val members = rows.orEmpty().map {
                                                session.api.decode<KavitaListItem>(it.toString())
                                            }
                                            source.createReadingQueue(
                                                session.catalog.lists().firstOrNull {
                                                    it.id == readingListId
                                                }?.title.orEmpty(),
                                                members,
                                            )
                                        }
                                        val intent = EpubReaderLauncher().resolveIntent(
                                            context,
                                            chapter.mangaId,
                                            chapter.id,
                                        )
                                        context.startActivity(
                                            koharia.connection.ConnectionReadingQueueController.attach(
                                                intent,
                                                source.id,
                                                queue,
                                                chapter.url,
                                            ),
                                        )
                                    }
                                    section == "Collection" -> navigator.push(
                                        KavitaOrganizationScreen(sourceId, "Collection", remoteId),
                                    )
                                    section == "Filter" -> run {
                                        val decoded = session.catalog.postResource(
                                            "Filter/decode",
                                            buildJsonObject {
                                                put("encodedFilter", row.text("filter"))
                                            },
                                        )
                                        val filter = session.api.json.decodeFromString<KavitaFilter>(decoded.toString())
                                        navigator.push(
                                            KavitaAdvancedFilterScreen(
                                                sourceId,
                                                session.api.json.encodeToString(filter),
                                                remoteId,
                                                row.text("name"),
                                            ),
                                        )
                                    }
                                    else -> {
                                        val field = when (section) {
                                            "Collection" -> 7
                                            "Metadata/tags" -> 6
                                            "Metadata/genres" -> 18
                                            else -> 17
                                        }
                                        shelf(listOf(KavitaFilterStatement(field, 0, remoteId.toString())))
                                    }
                                }
                            },
                        )
                        if (section == "ReadingList/items" && ownsList && source.preferences.capabilities.writable) {
                            Row {
                                for ((delta, label) in listOf(
                                    -1 to MR.strings.kavita_move_up,
                                    1 to MR.strings.kavita_move_down,
                                    0 to MR.strings.action_remove,
                                )) {
                                    TextButton(enabled = !busy, onClick = {
                                        run {
                                            session.organization.moveListMember(readingListId, remoteId, delta)
                                            load(true)
                                        }
                                    }) { Text(stringResource(label)) }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                    if (rows?.isEmpty() == true) item { Text(stringResource(MR.strings.no_results_found)) }
                }
            }
        }
        if (createName != null) {
            AlertDialog(
                onDismissRequest = { createName = null },
                title = { Text(stringResource(MR.strings.kavita_reading_lists)) },
                text = { OutlinedTextField(createName.orEmpty(), { createName = it }, singleLine = true) },
                confirmButton = {
                    TextButton(enabled = !createName.isNullOrBlank() && !busy, onClick = {
                        val name = createName.orEmpty()
                        createName = null
                        run {
                            session.organization.createList(name)
                            load(true)
                        }
                    }) { Text(stringResource(MR.strings.action_add)) }
                },
                dismissButton = {
                    TextButton(onClick = { createName = null }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
        error?.let { message ->
            AlertDialog(onDismissRequest = { error = null }, text = { Text(message) }, confirmButton = {
                TextButton(onClick = { error = null }) { Text(stringResource(MR.strings.action_ok)) }
            })
        }
    }
}

internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
