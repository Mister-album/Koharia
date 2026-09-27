package koharia.kavita.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import koharia.kavita.KavitaChapter
import koharia.kavita.KavitaSeries
import koharia.kavita.longValue
import koharia.kavita.textValue
import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KavitaSeriesScreen(private val sourceId: Long, private val seriesId: Long) : Screen() {
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
        var series by remember { mutableStateOf<JsonObject?>(null) }
        var want by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var dialog by remember { mutableStateOf("") }
        var choices by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
        var related by remember { mutableStateOf<List<KavitaSeries>?>(null) }
        var chapters by remember { mutableStateOf<List<KavitaChapter>>(emptyList()) }
        var selectedList by remember { mutableStateOf(0L) }
        var rating by remember { mutableStateOf(0f) }
        var text by remember { mutableStateOf("") }
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
            series = session.catalog.resource("Series/$seriesId", refresh).jsonObject
            want = session.catalog.resource("want-to-read?seriesId=$seriesId", refresh)
                .jsonPrimitive.booleanOrNull == true
        }
        LaunchedEffect(source.instanceKey) { run { load(false) } }
        Scaffold(topBar = {
            AppBar(title = series?.textValue("name").orEmpty(), navigateUp = { navigator.pop() }, actions = {
                TextButton(enabled = !busy, onClick = { run { load(true) } }) {
                    Text(stringResource(MR.strings.connection_refresh))
                }
            })
        }) { padding ->
            if (series == null && error == null) {
                LoadingScreen()
            } else {
                LazyColumn(Modifier.padding(padding)) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(MR.strings.kavita_want_to_read)) },
                            trailingContent = {
                                Switch(
                                    checked = want,
                                    enabled = !busy && source.preferences.capabilities.writable,
                                    onCheckedChange = { enabled ->
                                        run {
                                            session.organization.wantToRead(seriesId, enabled)
                                            want = enabled
                                        }
                                    },
                                )
                            },
                        )
                    }
                    item {
                        KavitaActionRow(MR.strings.kavita_reading_profiles, !busy) {
                            navigator.push(KavitaPersonalScreen(sourceId, "profiles", seriesId))
                        }
                        KavitaActionRow(MR.strings.kavita_plus, !busy) {
                            navigator.push(KavitaPersonalScreen(sourceId, "plus", seriesId))
                        }
                        KavitaActionRow(MR.strings.kavita_collections, !busy) {
                            run {
                                val user = session.api.getAccount()
                                choices = session.catalog.resource("Collection").jsonArray.map { it.jsonObject }
                                    .filter { it.textValue("owner").equals(user.username, true) }
                                dialog = "collections"
                            }
                        }
                        KavitaActionRow(
                            MR.strings.kavita_add_to_reading_list,
                            !busy && source.preferences.capabilities.writable,
                        ) {
                            run {
                                val user = session.api.getAccount()
                                choices =
                                    session.catalog.lists().filter {
                                        it.ownerUserName.equals(user.username, true)
                                    }.map {
                                        session.organization.readingList(it.id)
                                    }
                                dialog = "lists"
                            }
                        }
                        KavitaActionRow(MR.strings.kavita_rating, !busy && source.preferences.capabilities.writable) {
                            rating = series?.get("userRating")?.jsonPrimitive?.floatOrNull ?: 0f
                            dialog = "rating"
                        }
                        KavitaActionRow(MR.strings.kavita_review, !busy && source.preferences.capabilities.writable) {
                            run {
                                // Only a missing legacy endpoint permits an empty editor.
                                text = try {
                                    session.catalog.resource("Review/all?userId=${session.api.getAccount().id}", true)
                                        .jsonArray.map { it.jsonObject }
                                        .firstOrNull {
                                            it.longValue("seriesId") == seriesId &&
                                                it["chapterId"]?.toString() in listOf(null, "null", "0")
                                        }
                                        ?.textValue("body").orEmpty()
                                } catch (failure: koharia.kavita.KavitaException) {
                                    throw failure
                                }
                                dialog = "review"
                            }
                        }
                        KavitaActionRow(MR.strings.kavita_related, !busy) {
                            run {
                                related =
                                    session.catalog.resource("Series/all-related?seriesId=$seriesId").jsonObject.values
                                        .filterIsInstance<JsonArray>().flatMap { array ->
                                            array.map { session.api.decode<KavitaSeries>(it.toString()) }
                                        }.distinctBy { it.id }
                            }
                        }
                        if (source.preferences.capabilities.downloads && source.preferences.capabilities.writable) {
                            KavitaActionRow(MR.strings.kavita_send_device, !busy) {
                                run {
                                    choices = session.catalog.resource("Device", true).jsonArray.map { it.jsonObject }
                                    dialog = "devices"
                                }
                            }
                        }
                    }
                    related?.let { values ->
                        if (values.isEmpty()) item { Text(stringResource(MR.strings.no_results_found)) }
                        items(values, key = { it.id }) { item ->
                            ListItem(
                                headlineContent = { Text(item.name) },
                                modifier = Modifier.clickable(!busy) {
                                    run {
                                        val manga = source.materialize(source.toManga(item))
                                        withContext(Dispatchers.Main) { navigator.push(MangaScreen(manga.id)) }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        if (dialog in listOf("collections", "lists", "devices")) {
            AlertDialog(
                onDismissRequest = { if (!busy) dialog = "" },
                title = {
                    Text(
                        stringResource(
                            when (dialog) {
                                "collections" -> MR.strings.kavita_collections
                                "lists" -> MR.strings.kavita_add_to_reading_list
                                else -> MR.strings.kavita_send_device
                            },
                        ),
                    )
                },
                text = {
                    LazyColumn {
                        if (choices.isEmpty()) item { Text(stringResource(MR.strings.no_results_found)) }
                        items(choices) { value ->
                            ListItem(
                                headlineContent = {
                                    Text(value.textValue("title").ifBlank { value.textValue("name") })
                                },
                                modifier = Modifier.clickable(!busy) {
                                    when (dialog) {
                                        "collections" -> {
                                            dialog = ""
                                            navigator.push(
                                                KavitaOrganizationScreen(
                                                    sourceId,
                                                    "Collection",
                                                    value.longValue("id"),
                                                    seriesId,
                                                ),
                                            )
                                        }
                                        "lists" -> run {
                                            selectedList = value.longValue("id")
                                            chapters =
                                                session.catalog.volumes(seriesId).flatMap {
                                                    it.chapters
                                                }.distinctBy { it.id }
                                            dialog = "chapters"
                                        }
                                        else -> {
                                            selectedList = value.longValue("id")
                                            text = value.textValue("name")
                                            dialog = "send"
                                        }
                                    }
                                },
                            )
                        }
                        if (dialog == "collections" && source.preferences.capabilities.writable) {
                            item {
                                TextButton(onClick = {
                                    text = ""
                                    dialog = "newCollection"
                                }) {
                                    Text(stringResource(MR.strings.action_add))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { dialog = "" }) { Text(stringResource(MR.strings.action_close)) }
                },
            )
        }
        if (dialog == "chapters") {
            AlertDialog(
                onDismissRequest = {
                    dialog = ""
                },
                title = { Text(stringResource(MR.strings.kavita_add_to_reading_list)) },
                text = {
                    LazyColumn {
                        item {
                            TextButton(enabled = !busy, onClick = {
                                run {
                                    session.organization.addListMember(selectedList, seriesId)
                                    dialog = ""
                                }
                            }) { Text(stringResource(MR.strings.kavita_all_chapters)) }
                        }
                        items(chapters, key = { it.id }) { chapter ->
                            ListItem(
                                headlineContent = {
                                    Text(chapter.titleName.ifBlank { chapter.title }.ifBlank { chapter.range })
                                },
                                modifier = Modifier.clickable(!busy) {
                                    run {
                                        session.organization.addListMember(selectedList, seriesId, chapter.id)
                                        dialog = ""
                                    }
                                },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { dialog = "" }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
        if (dialog in listOf("rating", "review", "deleteReview", "newCollection", "send")) {
            AlertDialog(
                onDismissRequest = { if (!busy) dialog = "" },
                text = {
                    when (dialog) {
                        "rating" -> Column {
                            Text("$rating / 5")
                            Slider(rating, { rating = it }, valueRange = 0f..5f, steps = 9)
                        }
                        "send" -> Text(stringResource(MR.strings.kavita_send_device_confirm, text))
                        "deleteReview" -> Text(stringResource(MR.strings.kavita_delete_review_confirm))
                        else -> OutlinedTextField(text, { text = it }, label = {
                            Text(
                                stringResource(
                                    if (dialog == "review") MR.strings.kavita_review else MR.strings.kavita_name,
                                ),
                            )
                        })
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy && (dialog != "newCollection" || text.isNotBlank()), onClick = {
                        val action = dialog
                        run {
                            when (action) {
                                "rating" -> session.organization.rate(seriesId, rating)
                                "review" -> session.organization.review(seriesId, text)
                                "deleteReview" -> session.organization.deleteReview(seriesId)
                                "newCollection" -> session.organization.addCollection(seriesId, title = text)
                                "send" -> session.organization.sendToDevice(seriesId, selectedList)
                            }
                            dialog = ""
                            load(true)
                        }
                    }) { Text(stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    Row {
                        if (dialog == "review") {
                            TextButton(enabled = !busy, onClick = { dialog = "deleteReview" }) {
                                Text(stringResource(MR.strings.action_delete))
                            }
                        }
                        TextButton(onClick = { dialog = "" }) { Text(stringResource(MR.strings.action_cancel)) }
                    }
                },
            )
        }
        KavitaErrorDialog(error) { error = null }
    }
}

@Composable
internal fun KavitaActionRow(
    label: dev.icerock.moko.resources.StringResource,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    ListItem(headlineContent = {
        Text(stringResource(label))
    }, modifier = Modifier.clickable(enabled, onClick = action))
}

@Composable
internal fun KavitaErrorDialog(error: String?, dismiss: () -> Unit) {
    error?.let {
        AlertDialog(onDismissRequest = dismiss, text = { Text(it) }, confirmButton = {
            TextButton(onClick = dismiss) { Text(stringResource(MR.strings.action_ok)) }
        })
    }
}
