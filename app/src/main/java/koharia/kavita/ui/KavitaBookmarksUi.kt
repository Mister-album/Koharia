package koharia.kavita.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.dp
import koharia.kavita.KavitaBookmarkKind
import koharia.kavita.KavitaBookmarkState
import koharia.kavita.KavitaEpubPublicationService
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
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun KavitaBookmarkAction(
    source: KavitaSource,
    chapterUrl: String,
    page: Int,
    imageOffset: Int,
    anchor: String,
    readOnly: Boolean,
) {
    val session = remember(source) { source.session() }
    val state = remember(chapterUrl, page, imageOffset, anchor) {
        KavitaBookmarkState(
            session.identity.chapter(chapterUrl),
            KavitaBookmarkKind.IMAGE,
            page,
            imageOffset = imageOffset,
            anchor = anchor,
        )
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember(state) { mutableStateOf(false) }
    var pending by remember(state) { mutableStateOf(false) }
    var confirmation by remember(state) { mutableStateOf(false) }
    var revision by remember(state) { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val writable = !readOnly && source.preferences.capabilities.writable &&
        source.preferences.capabilities.roles.any { it.equals("Admin", true) || it.equals("Bookmark", true) }
    suspend fun load(refresh: Boolean = false) {
        try {
            val entries = session.bookmarks.entries(state.ref, state.kind, refresh)
            val entry = entries.firstOrNull(state::matches)
            saved = entry != null && entry["deleted"]?.jsonPrimitive?.booleanOrNull != true
            pending = entry?.get("pending")?.jsonPrimitive?.booleanOrNull == true
            confirmation = entry?.get("confirmation")?.jsonPrimitive?.booleanOrNull == true
            revision = entry?.longValue("revision") ?: 0
            error = null
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = context.kavitaError(failure)
        }
    }
    LaunchedEffect(state) {
        withContext(Dispatchers.IO) { load() }
        session.bookmarks.changes.collect { withContext(Dispatchers.IO) { load() } }
    }
    Column {
        TextButton(enabled = writable && !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        session.bookmarks.set(state.copy(desired = if (confirmation) saved else !saved))
                        load()
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = context.kavitaError(failure)
                } finally {
                    busy = false
                }
            }
        }) {
            Text(
                stringResource(
                    if (confirmation) {
                        MR.strings.kavita_bookmark_confirm_sync
                    } else if (saved) {
                        MR.strings.kavita_image_unbookmark
                    } else {
                        MR.strings.kavita_image_bookmark
                    },
                ),
            )
        }
        TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        load(true)
                        session.bookmarks.flush()
                        load()
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = context.kavitaError(failure)
                } finally {
                    busy = false
                }
            }
        }) { Text(stringResource(MR.strings.connection_refresh)) }
        if (pending) {
            Text(stringResource(MR.strings.kavita_annotation_pending))
            TextButton(enabled = !busy, onClick = {
                val expected = revision
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            session.bookmarks.discard(state.key, expected)
                            load()
                        }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        error = context.kavitaError(failure)
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(MR.strings.kavita_bookmark_discard_pending)) }
        }
        error?.let { Text(it) }
    }
}

@Composable
fun KavitaPersonalTocDialog(
    source: KavitaSource,
    chapterUrl: String,
    locator: Locator?,
    readOnly: Boolean,
    onNavigate: (Locator) -> Unit,
    onDismiss: () -> Unit,
) {
    val session = remember(source) { source.session() }
    val ref = remember(chapterUrl) { session.identity.chapter(chapterUrl) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val writable = !readOnly && source.preferences.capabilities.writable
    val page = locator?.href?.toString()?.let(KavitaEpubPublicationService::pageIndex)
    val anchor = locator?.toJSON()?.optJSONObject("locations")?.optString("kavitaXPath").orEmpty()
    suspend fun load(refresh: Boolean = false) {
        entries = session.bookmarks.entries(ref, KavitaBookmarkKind.TOC, refresh)
            .sortedBy { it.longValue("pageNumber") }
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
    LaunchedEffect(session) {
        run { load() }
        session.bookmarks.changes.collect { run { load() } }
    }
    LaunchedEffect(source) { source.epoch.drop(1).collect { onDismiss() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.kavita_personal_toc)) },
        text = {
            Column {
                OutlinedTextField(title, {
                    title = it
                }, label = { Text(stringResource(MR.strings.kavita_bookmark_title)) })
                Text(stringResource(MR.strings.kavita_personal_toc_help))
                TextButton(enabled = !busy, onClick = {
                    run {
                        load(true)
                        session.bookmarks.flush()
                        load()
                    }
                }) {
                    Text(stringResource(MR.strings.connection_refresh))
                }
                error?.let { Text(it) }
                LazyColumn(Modifier.heightIn(max = 370.dp)) {
                    items(entries) { entry ->
                        Column {
                            Text(
                                entry.textValue("title"),
                                Modifier.clickable {
                                    val target = requireNotNull(
                                        Locator.fromJSON(
                                            JSONObject().put(
                                                "href",
                                                KavitaEpubPublicationService.pageUrl(
                                                    session.api,
                                                    ref.chapterId,
                                                    entry.longValue("pageNumber").toInt(),
                                                ),
                                            ).put("type", "text/html")
                                                .put(
                                                    "locations",
                                                    JSONObject().put("kavitaRestore", entry.textValue("bookScrollId")),
                                                ),
                                        ),
                                    )
                                    onNavigate(target)
                                    onDismiss()
                                },
                            )
                            if (entry["pending"]?.jsonPrimitive?.booleanOrNull == true) {
                                Text(
                                    stringResource(
                                        if (entry["deleted"]?.jsonPrimitive?.booleanOrNull == true) {
                                            MR.strings.kavita_bookmark_pending_delete
                                        } else {
                                            MR.strings.kavita_annotation_pending
                                        },
                                    ),
                                )
                                TextButton(enabled = !busy, onClick = {
                                    run {
                                        val state = KavitaBookmarkState(
                                            ref,
                                            KavitaBookmarkKind.TOC,
                                            entry.longValue("pageNumber").toInt(),
                                            title = entry.textValue("title"),
                                        )
                                        session.bookmarks.discard(state.key, entry.longValue("revision"))
                                        load()
                                    }
                                }) { Text(stringResource(MR.strings.kavita_bookmark_discard_pending)) }
                            }
                            if (entry["confirmation"]?.jsonPrimitive?.booleanOrNull == true) {
                                TextButton(enabled = writable && !busy, onClick = {
                                    run {
                                        session.bookmarks.set(
                                            KavitaBookmarkState(
                                                ref,
                                                KavitaBookmarkKind.TOC,
                                                entry.longValue("pageNumber").toInt(),
                                                title = entry.textValue("title"),
                                                anchor = entry.textValue("bookScrollId"),
                                                selectedText = entry.textValue("selectedText"),
                                                desired = entry["deleted"]?.jsonPrimitive?.booleanOrNull != true,
                                            ),
                                        )
                                        load()
                                    }
                                }) { Text(stringResource(MR.strings.kavita_bookmark_confirm_sync)) }
                            }
                            TextButton(enabled = writable && !busy, onClick = {
                                run {
                                    session.bookmarks.set(
                                        KavitaBookmarkState(
                                            ref,
                                            KavitaBookmarkKind.TOC,
                                            entry.longValue("pageNumber").toInt(),
                                            title = entry.textValue("title"),
                                            anchor = entry.textValue("bookScrollId"),
                                            selectedText = entry.textValue("selectedText"),
                                            desired = false,
                                        ),
                                    )
                                    load()
                                }
                            }) { Text(stringResource(MR.strings.action_delete)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = writable && !busy && page != null && anchor.isNotBlank() && title.isNotBlank(),
                onClick = {
                    run {
                        session.bookmarks.set(
                            KavitaBookmarkState(
                                ref,
                                KavitaBookmarkKind.TOC,
                                requireNotNull(page),
                                title = title.trim(),
                                anchor = anchor,
                                selectedText = locator?.text?.highlight.orEmpty(),
                            ),
                        )
                        title = ""
                        load()
                    }
                },
            ) { Text(stringResource(MR.strings.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_close)) } },
    )
}
