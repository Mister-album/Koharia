package koharia.kavita.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import koharia.connection.SharedAppPreferences
import koharia.kavita.KavitaException
import koharia.kavita.KavitaProfileImport
import koharia.kavita.KavitaScrobbleStatus
import koharia.kavita.KavitaSeries
import koharia.kavita.kavitaProviderName
import koharia.kavita.longValue
import koharia.kavita.textValue
import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KavitaPersonalScreen(
    private val sourceId: Long,
    private val section: String,
    private val seriesId: Long = 0,
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
        val uriHandler = LocalUriHandler.current
        val scope = rememberCoroutineScope()
        val yes = stringResource(MR.strings.kavita_yes)
        val no = stringResource(MR.strings.kavita_no)
        fun flag(value: Boolean?) = when (value) {
            true -> yes
            false -> no
            null -> "—"
        }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var data by remember { mutableStateOf<JsonObject?>(null) }
        var profiles by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
        var selectedProfile by remember { mutableStateOf<JsonObject?>(null) }
        var statuses by remember { mutableStateOf<List<KavitaScrobbleStatus>>(emptyList()) }
        var licensed by remember { mutableStateOf<Boolean?>(null) }
        var hold by remember { mutableStateOf<Boolean?>(null) }
        var allowed by remember { mutableStateOf<Boolean?>(null) }
        var nextSync by remember { mutableStateOf("") }
        fun run(block: suspend () -> Unit) {
            if (busy) return
            busy = true
            error = null
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
            when (section) {
                "stats" -> {
                    val user = session.api.getAccount()
                    data = session.catalog.resource("Stats/user-stats?userId=${user.id}", refresh).jsonObject
                }
                "profiles" -> {
                    profiles = try {
                        val result = if (seriesId > 0) {
                            val series = session.catalog.series(seriesId)
                            JsonArray(
                                listOf(
                                    session.catalog.resource(
                                        "reading-profile/${series.libraryId}/$seriesId?skipImplicit=false",
                                        refresh,
                                    ),
                                ),
                            )
                        } else {
                            session.catalog.resource("reading-profile/all", refresh)
                        }
                        (result as JsonArray).map { it.jsonObject }
                    } catch (failure: KavitaException) {
                        if (failure.status !in listOf(404, 405)) throw failure
                        listOf(session.catalog.resource("Users/get-preferences", refresh).jsonObject)
                    }
                }
                "plus" -> {
                    licensed = session.catalog.resource("License/valid-license", refresh).jsonPrimitive.booleanOrNull
                    if (licensed == true) {
                        val series = session.catalog.series(seriesId)
                        val library = session.catalog.libraries().single { it.id == series.libraryId }
                        data = session.catalog.resource(
                            "Metadata/series-detail-plus?seriesId=$seriesId&libraryType=${library.type}",
                            refresh,
                        ).jsonObject
                        statuses = session.catalog.scrobbleStatuses(refresh)
                        hold = session.catalog.resource("Scrobbling/has-hold?seriesId=$seriesId", refresh)
                            .jsonPrimitive.booleanOrNull
                        allowed =
                            session.catalog.resource("Scrobbling/library-allows-scrobbling?seriesId=$seriesId", refresh)
                                .jsonPrimitive.booleanOrNull
                        nextSync =
                            session.catalog.resource("Scrobbling/next-scrobble-time", refresh).jsonPrimitive.content
                    } else {
                        data = null
                        statuses = emptyList()
                    }
                }
            }
        }
        LaunchedEffect(session) { run { load(false) } }
        LaunchedEffect(source) { source.epoch.drop(1).collect { navigator.pop() } }
        fun open(value: JsonObject, field: String) {
            val url = value.textValue(field)
            if (url.startsWith("https://") || url.startsWith("http://")) uriHandler.openUri(url)
        }
        Scaffold(topBar = {
            AppBar(
                title = stringResource(
                    when (section) {
                        "stats" -> MR.strings.kavita_statistics
                        "profiles" -> MR.strings.kavita_reading_profiles
                        else -> MR.strings.kavita_plus
                    },
                ),
                navigateUp = { navigator.pop() },
                actions = {
                    TextButton(enabled = !busy, onClick = { run { load(true) } }) {
                        Text(stringResource(MR.strings.connection_refresh))
                    }
                },
            )
        }) { padding ->
            LazyColumn(Modifier.padding(padding)) {
                error?.let { item { Text(it) } }
                when (section) {
                    "stats" -> {
                        val fields = listOf(
                            "booksRead" to MR.strings.kavita_stats_books,
                            "comicsRead" to MR.strings.kavita_stats_comics,
                            "pagesRead" to MR.strings.kavita_stats_pages,
                            "wordsRead" to MR.strings.kavita_stats_words,
                            "authorsRead" to MR.strings.kavita_stats_authors,
                            "reviews" to MR.strings.kavita_stats_reviews,
                            "ratings" to MR.strings.kavita_stats_ratings,
                        )
                        items(fields) { (key, label) ->
                            ListItem(
                                headlineContent = { Text(stringResource(label)) },
                                trailingContent = { Text(data?.get(key)?.jsonPrimitive?.content ?: "—") },
                            )
                        }
                    }
                    "profiles" -> items(profiles) { profile ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    profile.textValue("name").ifBlank {
                                        stringResource(MR.strings.kavita_reading_profiles)
                                    },
                                )
                            },
                            supportingContent = { Text(stringResource(MR.strings.kavita_profile_preview)) },
                            modifier = Modifier.clickable(!busy) { selectedProfile = profile },
                        )
                    }
                    "plus" -> {
                        if (licensed == false) item { Text(stringResource(MR.strings.kavita_plus_unavailable)) }
                        val extra = data?.get("series") as? JsonObject
                        extra?.let {
                            item {
                                Text(it.textValue("name"))
                                Text(org.jsoup.Jsoup.parse(it.textValue("summary")).text())
                                Text(
                                    (it["genres"] as? JsonArray).orEmpty().joinToString { genre ->
                                        genre.jsonPrimitive.content
                                    },
                                )
                            }
                        }
                        items((data?.get("ratings") as? JsonArray).orEmpty()) { element ->
                            val rating = element.jsonObject
                            ListItem(
                                headlineContent = { Text(kavitaProviderName(rating.longValue("provider"))) },
                                trailingContent = {
                                    Text("${rating["averageScore"]?.jsonPrimitive?.content ?: "—"} / 100")
                                },
                                modifier = Modifier.clickable { open(rating, "providerUrl") },
                            )
                        }
                        items((data?.get("reviews") as? JsonArray).orEmpty()) { element ->
                            val review = element.jsonObject
                            ListItem(
                                headlineContent = { Text(review.textValue("username")) },
                                supportingContent = {
                                    Text(
                                        review.textValue("bodyJustText").ifBlank {
                                            org.jsoup.Jsoup.parse(review.textValue("body")).text()
                                        },
                                    )
                                },
                                modifier = Modifier.clickable { open(review, "siteUrl") },
                            )
                        }
                        val recommendations = data?.get("recommendations") as? JsonObject
                        items((recommendations?.get("ownedSeries") as? JsonArray).orEmpty()) { item ->
                            val series = session.api.decode<KavitaSeries>(item.jsonObject.getValue("series").toString())
                            ListItem(
                                headlineContent = { Text(series.name) },
                                modifier = Modifier.clickable(!busy) {
                                    run {
                                        val manga = source.materialize(source.toManga(series))
                                        withContext(Dispatchers.Main) { navigator.push(MangaScreen(manga.id)) }
                                    }
                                },
                            )
                        }
                        items((recommendations?.get("externalSeries") as? JsonArray).orEmpty()) { item ->
                            val series = item.jsonObject
                            ListItem(
                                headlineContent = { Text(series.textValue("name")) },
                                supportingContent = { Text(org.jsoup.Jsoup.parse(series.textValue("summary")).text()) },
                                modifier = Modifier.clickable { open(series, "url") },
                            )
                        }
                        if (licensed == true) {
                            item { Text(stringResource(MR.strings.kavita_scrobble_help)) }
                            item {
                                Text(
                                    stringResource(
                                        MR.strings.kavita_scrobble_status,
                                        flag(allowed),
                                        flag(hold),
                                        nextSync,
                                    ),
                                )
                            }
                            items(statuses) { status ->
                                ListItem(
                                    headlineContent = { Text(kavitaProviderName(status.provider.toLong())) },
                                    supportingContent = {
                                        Text(
                                            stringResource(
                                                MR.strings.kavita_scrobble_provider,
                                                status.userName,
                                                flag(status.settings.progressScrobbling),
                                                status.lastSyncedUtc,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        selectedProfile?.let { profile ->
            val compatible = KavitaProfileImport.from(profile)
            val direction = compatible.direction?.let {
                stringResource(
                    if (it == koharia.epub.settings.EpubLayoutPreferences.PageDirection.LEFT_TO_RIGHT) {
                        MR.strings.left_to_right_viewer
                    } else {
                        MR.strings.right_to_left_viewer
                    },
                )
            } ?: "—"
            val mode = compatible.readingMode?.let {
                stringResource(
                    if (it == koharia.epub.settings.EpubLayoutPreferences.ReadingMode.SCROLL) {
                        MR.strings.pref_epub_layout_scrolled
                    } else {
                        MR.strings.pref_epub_layout_paginated
                    },
                )
            } ?: "—"
            AlertDialog(
                onDismissRequest = { selectedProfile = null },
                title = { Text(stringResource(MR.strings.kavita_profile_preview)) },
                text = {
                    Column {
                        Text(stringResource(MR.strings.kavita_profile_shared))
                        Text(
                            stringResource(
                                MR.strings.kavita_profile_values,
                                compatible.fontScale?.toString() ?: "—",
                                compatible.lineHeight?.toString() ?: "—",
                                direction,
                                mode,
                            ),
                        )
                    }
                },
                confirmButton = {
                    TextButton(enabled = !compatible.isEmpty, onClick = {
                        compatible.applyTo(Injekt.get<SharedAppPreferences>().epubLayoutPreferences())
                        selectedProfile = null
                    }) { Text(stringResource(MR.strings.kavita_profile_import)) }
                },
                dismissButton = {
                    TextButton(onClick = { selectedProfile = null }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
    }
}
