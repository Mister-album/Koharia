package koharia.kavita.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import koharia.epub.EpubReaderActivity
import koharia.epub.EpubReaderLauncher
import koharia.kavita.KavitaAnnotation
import koharia.kavita.KavitaAnnotationPage
import koharia.kavita.KavitaEpubPublicationService
import koharia.kavita.KavitaFilterStatement
import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONObject
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** The server applies sharing and library permissions before pagination. */
class KavitaAnnotationBrowserScreen(private val sourceId: Long) : Screen() {
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
        var search by remember { mutableStateOf("") }
        var mine by remember { mutableStateOf(false) }
        var page by remember { mutableStateOf(1) }
        var result by remember { mutableStateOf<KavitaAnnotationPage?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var filter by remember { mutableStateOf(buildJsonObject {}) }
        var social by remember { mutableStateOf<JsonObject?>(null) }
        var changeSharing by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
        suspend fun load(number: Int = page, refresh: Boolean = false) {
            val loaded = session.catalog.annotationPage(number, filter, refresh)
            session.checkActive()
            page = number
            result = loaded
        }
        fun run(block: suspend () -> Unit) {
            if (busy) return
            busy = true
            scope.launch {
                try {
                    block()
                    error = null
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = context.kavitaError(failure)
                } finally {
                    busy = false
                }
            }
        }
        suspend fun applySearch() {
            val user = session.api.getAccount()
            val statements = buildList {
                if (mine) add(KavitaFilterStatement(1, 0, user.id.toString()))
                if (search.isNotBlank()) add(KavitaFilterStatement(5, 7, search.trim()))
            }
            filter = buildJsonObject {
                put("statements", session.api.json.parseToJsonElement(session.api.json.encodeToString(statements)))
                put("combination", 1)
                put("entityType", 3)
                put(
                    "sortOptions",
                    buildJsonObject {
                        put("sortField", 3)
                        put("isAscending", false)
                    },
                )
            }
            result = null
            load(1)
        }
        suspend fun open(annotation: KavitaAnnotation) {
            val chapter = withContext(Dispatchers.IO) {
                val manga = source.materialize(source.toManga(session.catalog.series(annotation.seriesId)))
                Injekt.get<SyncChaptersWithSource>().await(source.getChapterList(manga.toSManga()), manga, source)
                Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).first {
                    session.identity.chapter(it.url).chapterId == annotation.chapterId
                }
            }
            val intent = EpubReaderLauncher().resolveIntent(context, chapter.mangaId, chapter.id)
            val locator = JSONObject()
                .put(
                    "href",
                    KavitaEpubPublicationService.pageUrl(session.api, annotation.chapterId, annotation.pageNumber),
                )
                .put("type", "text/html")
                .put("locations", JSONObject().put("kavitaRestore", annotation.xPath))
            intent.putExtra(EpubReaderActivity.EXTRA_REMOTE_BOOKMARK_LOCATOR, locator.toString())
            context.startActivity(intent)
        }
        LaunchedEffect(session) {
            run {
                withContext(Dispatchers.IO) {
                    applySearch()
                    social = (session.catalog.resource("Users/get-preferences") as? JsonObject)
                        ?.get("socialPreferences") as? JsonObject
                }
            }
        }
        LaunchedEffect(source) { source.epoch.drop(1).collect { navigator.pop() } }
        Scaffold(topBar = {
            AppBar(
                title = stringResource(MR.strings.kavita_shared_annotations),
                navigateUp = { navigator.pop() },
                actions = {
                    TextButton(enabled = !busy, onClick = {
                        run { withContext(Dispatchers.IO) { load(refresh = true) } }
                    }) {
                        Text(stringResource(MR.strings.connection_refresh))
                    }
                },
            )
        }) { padding ->
            LazyColumn(Modifier.padding(padding)) {
                item {
                    OutlinedTextField(
                        search,
                        { search = it },
                        label = { Text(stringResource(MR.strings.kavita_annotation_selection_search)) },
                    )
                    CheckboxItem(stringResource(MR.strings.kavita_annotations_mine), mine) { mine = !mine }
                    TextButton(enabled = !busy, onClick = { run { withContext(Dispatchers.IO) { applySearch() } } }) {
                        Text(stringResource(MR.strings.action_search))
                    }
                    Text(stringResource(MR.strings.kavita_shared_annotations_help))
                    if (source.preferences.capabilities.writable) {
                        social?.let { preferences ->
                            for ((field, label) in listOf(
                                "shareAnnotations" to MR.strings.kavita_share_annotations,
                                "viewOtherAnnotations" to MR.strings.kavita_view_other_annotations,
                            )) {
                                val enabled = preferences[field]?.jsonPrimitive?.booleanOrNull == true
                                CheckboxItem(stringResource(label), enabled) {
                                    if (!busy) changeSharing = field to !enabled
                                }
                            }
                        }
                    }
                }
                items(result?.items.orEmpty(), key = { it.id }) { annotation ->
                    var revealed by remember(annotation.id) { mutableStateOf(false) }
                    Column {
                        Text(
                            listOf(annotation.seriesName, annotation.chapterTitle, annotation.ownerUsername)
                                .filter(String::isNotBlank).joinToString(" · "),
                        )
                        if (annotation.containsSpoiler && !revealed) {
                            TextButton(onClick = { revealed = true }) {
                                Text(stringResource(MR.strings.kavita_reveal_spoiler))
                            }
                        } else {
                            Text(annotation.selectedText)
                            if (annotation.commentPlainText.isNotBlank()) Text(annotation.commentPlainText)
                        }
                        Row {
                            TextButton(enabled = !busy, onClick = { run { open(annotation) } }) {
                                Text(stringResource(MR.strings.kavita_annotation_locate))
                            }
                            if (source.preferences.capabilities.writable &&
                                annotation.ownerUsername != source.preferences.principal
                            ) {
                                TextButton(enabled = !busy, onClick = {
                                    run {
                                        withContext(Dispatchers.IO) {
                                            val user = session.api.getAccount()
                                            session.annotations.like(annotation.id, user.id !in annotation.likes)
                                            session.catalog.invalidateGroups("annotation-browser/")
                                            load(refresh = true)
                                        }
                                    }
                                }) {
                                    Text(
                                        stringResource(
                                            MR.strings.kavita_annotation_like,
                                        ) + " (${annotation.likes.size})",
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    if (result?.items?.isEmpty() == true) Text(stringResource(MR.strings.no_results_found))
                    Row {
                        TextButton(enabled = !busy && page > 1, onClick = {
                            run { withContext(Dispatchers.IO) { load(page - 1) } }
                        }) { Text(stringResource(MR.strings.kavita_previous_page)) }
                        Text(page.toString())
                        TextButton(enabled = !busy && result?.hasNext == true, onClick = {
                            run { withContext(Dispatchers.IO) { load(page + 1) } }
                        }) { Text(stringResource(MR.strings.kavita_next_page)) }
                    }
                }
            }
        }
        changeSharing?.let { (field, enabled) ->
            AlertDialog(
                onDismissRequest = { changeSharing = null },
                text = { Text(stringResource(MR.strings.kavita_sharing_confirm)) },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        changeSharing = null
                        run {
                            withContext(Dispatchers.IO) {
                                session.organization.annotationSharing(field, enabled)
                                social = (session.catalog.resource("Users/get-preferences") as? JsonObject)
                                    ?.get("socialPreferences") as? JsonObject
                                load(1, true)
                            }
                        }
                    }) { Text(stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { changeSharing = null }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
        KavitaErrorDialog(error) { error = null }
    }
}
