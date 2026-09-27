package koharia.kavita.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import koharia.connection.ConnectionTextSelection
import koharia.kavita.KavitaAnnotation
import koharia.kavita.KavitaAnnotationRecord
import koharia.kavita.KavitaAnnotationResolution
import koharia.kavita.KavitaEpubPublicationService
import koharia.kavita.withPlainComment
import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun KavitaAnnotationsDialog(
    source: KavitaSource,
    chapterUrl: String,
    selection: ConnectionTextSelection?,
    readOnly: Boolean,
    onNavigate: (Locator) -> Unit,
    onDismiss: () -> Unit,
) {
    val session = remember(source) { source.session() }
    LaunchedEffect(source) { source.epoch.drop(1).collect { onDismiss() } }
    val ref = remember(chapterUrl) { session.identity.chapter(chapterUrl) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val writable = !readOnly && source.preferences.capabilities.writable
    var records by remember { mutableStateOf<List<KavitaAnnotationRecord>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<KavitaAnnotationRecord?>(null) }
    var creating by remember { mutableStateOf(false) }
    var comment by remember { mutableStateOf("") }
    var slot by remember { mutableStateOf(0) }
    var spoiler by remember { mutableStateOf(false) }
    var deletion by remember { mutableStateOf<KavitaAnnotationRecord?>(null) }
    var conflict by remember { mutableStateOf<KavitaAnnotationRecord?>(null) }
    var revealSpoilers by remember { mutableStateOf(false) }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                session.checkActive()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = context.kavitaError(failure)
            } finally {
                busy = false
            }
        }
    }
    suspend fun cached() {
        records = session.annotations.cached(ref.chapterId)
    }
    LaunchedEffect(session) {
        withContext(Dispatchers.IO) { cached() }
        run {
            session.annotations.load(ref.chapterId)
            cached()
        }
        session.annotations.changes.collect { withContext(Dispatchers.IO) { cached() } }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            run {
                val bytes = session.api.json.encodeToString(records.map { it.state }).toByteArray()
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.connection_annotations)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(MR.strings.kavita_annotation_help))
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text(stringResource(MR.strings.action_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    TextButton(enabled = !busy, onClick = {
                        run {
                            session.annotations.load(ref.chapterId, true)
                            cached()
                        }
                    }) { Text(stringResource(MR.strings.connection_refresh)) }
                    TextButton(enabled = !busy, onClick = { export.launch("kavita-annotations.json") }) {
                        Text(stringResource(MR.strings.kavita_annotation_export))
                    }
                }
                Row {
                    Checkbox(revealSpoilers, { revealSpoilers = it })
                    Text(stringResource(MR.strings.kavita_annotation_reveal))
                }
                error?.let { Text(it) }
                LazyColumn(Modifier.heightIn(max = 370.dp)) {
                    items(
                        records.filter {
                            (it.state.annotation.selectedText + it.state.annotation.commentPlainText)
                                .contains(query, ignoreCase = true)
                        },
                        key = { it.entry.key },
                    ) { record ->
                        val value = record.state.annotation
                        val hidden = value.containsSpoiler && !revealSpoilers
                        Column {
                            Text(
                                if (hidden) {
                                    stringResource(
                                        MR.strings.kavita_annotation_spoiler,
                                    )
                                } else {
                                    value.selectedText
                                },
                                Modifier.clickable(enabled = !hidden && !record.state.anchorStale) {
                                    val locator = annotationLocator(session.api, value)
                                    onNavigate(locator)
                                    onDismiss()
                                },
                            )
                            if (!hidden && value.commentPlainText.isNotBlank()) Text(value.commentPlainText)
                            if (record.state.anchorStale) {
                                Text(stringResource(MR.strings.kavita_annotation_anchor_changed))
                                TextButton(enabled = writable && selection != null && !busy, onClick = {
                                    run {
                                        val selected = requireNotNull(selection)
                                        val selectedPage = requireNotNull(
                                            KavitaEpubPublicationService.pageIndex(selected.locator.href.toString()),
                                        )
                                        session.annotations.reanchor(
                                            record.entry.key,
                                            record.entry.revision,
                                            selectedPage,
                                            selected.startAnchor,
                                            selected.endAnchor,
                                            selected.text,
                                            selected.locator.toJSON().toString(),
                                        )
                                        cached()
                                    }
                                }) { Text(stringResource(MR.strings.kavita_annotation_reanchor)) }
                            }
                            if (record.entry.pending) Text(stringResource(MR.strings.kavita_annotation_pending))
                            if (record.state.conflict && !record.state.anchorStale) {
                                TextButton(enabled = writable && !busy, onClick = { conflict = record }) {
                                    Text(stringResource(MR.strings.kavita_annotation_conflict))
                                }
                            } else if (!record.state.anchorStale && writable &&
                                value.ownerUserId == source.preferences.identity?.userId
                            ) {
                                Row {
                                    TextButton(enabled = !busy, onClick = {
                                        edit = record
                                        comment = value.commentPlainText
                                        slot = value.selectedSlotIndex
                                        spoiler = value.containsSpoiler
                                    }) { Text(stringResource(MR.strings.action_edit)) }
                                    TextButton(enabled = !busy, onClick = { deletion = record }) {
                                        Text(stringResource(MR.strings.action_delete))
                                    }
                                }
                            }
                            if (writable && value.id > 0 && !record.state.deleted &&
                                value.ownerUserId != source.preferences.identity?.userId
                            ) {
                                val liked = source.preferences.identity?.userId in value.likes
                                TextButton(enabled = !busy, onClick = {
                                    run {
                                        session.annotations.like(value.id, !liked)
                                        session.annotations.load(ref.chapterId, true)
                                        cached()
                                    }
                                }) {
                                    Text(
                                        stringResource(
                                            if (liked) {
                                                MR.strings.kavita_annotation_unlike
                                            } else {
                                                MR.strings.kavita_annotation_like
                                            },
                                        ),
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = writable && selection != null && !busy, onClick = {
                creating = true
                comment = ""
                slot = 0
                spoiler = false
            }) { Text(stringResource(MR.strings.kavita_annotation_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_close)) } },
    )
    if (edit != null || creating) {
        AlertDialog(
            onDismissRequest = {
                edit = null
                creating = false
            },
            title = { Text(stringResource(MR.strings.connection_annotations)) },
            text = {
                Column {
                    Text(if (creating) selection?.text.orEmpty() else edit?.state?.annotation?.selectedText.orEmpty())
                    OutlinedTextField(comment, { comment = it }, label = {
                        Text(stringResource(MR.strings.kavita_annotation_note))
                    })
                    Text(stringResource(MR.strings.kavita_annotation_color))
                    Row {
                        repeat(4) { index ->
                            TextButton(onClick = { slot = index }, enabled = slot != index) {
                                Text((index + 1).toString())
                            }
                        }
                    }
                    Row {
                        Checkbox(spoiler, { spoiler = it })
                        Text(stringResource(MR.strings.kavita_annotation_spoiler))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    val editing = edit
                    run {
                        if (editing != null) {
                            session.annotations.edit(editing.entry.key, editing.entry.revision, comment, slot, spoiler)
                        } else {
                            val selected = requireNotNull(selection)
                            val page =
                                requireNotNull(KavitaEpubPublicationService.pageIndex(selected.locator.href.toString()))
                            val draft = KavitaAnnotation(
                                chapterId = ref.chapterId, seriesId = ref.seriesId, volumeId = ref.volumeId,
                                libraryId = ref.libraryId, ownerUserId = source.preferences.identity?.userId ?: 0,
                                xPath = selected.startAnchor, endingXPath = selected.endAnchor,
                                selectedText = selected.text, pageNumber = page,
                                selectedSlotIndex = slot, containsSpoiler = spoiler,
                            ).withPlainComment(comment)
                            session.annotations.create(draft, selected.locator.toJSON().toString())
                        }
                        cached()
                        edit = null
                        creating = false
                    }
                }) { Text(stringResource(MR.strings.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    edit = null
                    creating = false
                }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
    deletion?.let { record ->
        AlertDialog(
            onDismissRequest = { deletion = null },
            title = { Text(stringResource(MR.strings.action_delete)) },
            text = { Text(record.state.annotation.selectedText) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    run {
                        session.annotations.delete(record.entry.key, record.entry.revision)
                        cached()
                        deletion = null
                    }
                }) { Text(stringResource(MR.strings.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletion = null }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
    conflict?.let { record ->
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text(stringResource(MR.strings.kavita_annotation_conflict)) },
            text = {
                LazyColumn {
                    item { Text(stringResource(MR.strings.kavita_annotation_local)) }
                    item {
                        Text(record.state.annotation.selectedText + "\n" + record.state.annotation.commentPlainText)
                    }
                    item { Text(stringResource(MR.strings.kavita_annotation_remote)) }
                    item {
                        Text(
                            record.state.remoteConflict?.let { it.selectedText + "\n" + it.commentPlainText }
                                ?: stringResource(MR.strings.kavita_annotation_remote_missing),
                        )
                    }
                    items(KavitaAnnotationResolution.entries.toList()) { choice ->
                        TextButton(enabled = !busy, onClick = {
                            run {
                                session.annotations.resolve(record.entry.key, record.entry.revision, choice)
                                cached()
                                conflict = null
                            }
                        }) {
                            Text(
                                stringResource(
                                    when (choice) {
                                        KavitaAnnotationResolution.LOCAL -> MR.strings.kavita_annotation_keep_local
                                        KavitaAnnotationResolution.REMOTE -> MR.strings.kavita_annotation_keep_remote
                                        KavitaAnnotationResolution.BOTH -> MR.strings.kavita_annotation_keep_both
                                    },
                                ),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { conflict = null }) { Text(stringResource(MR.strings.action_close)) }
            },
        )
    }
}

internal fun annotationLocator(api: koharia.kavita.KavitaApiClient, annotation: KavitaAnnotation): Locator =
    requireNotNull(
        Locator.fromJSON(
            JSONObject().put(
                "href",
                KavitaEpubPublicationService.pageUrl(api, annotation.chapterId, annotation.pageNumber),
            )
                .put("type", "text/html").put("locations", JSONObject().put("kavitaRestore", annotation.xPath))
                .put("text", JSONObject().put("highlight", annotation.selectedText)),
        ),
    )
