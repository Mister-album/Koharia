package koharia.source.local

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

internal enum class LocalFolderOperation { CREATE, RENAME, MOVE }

@Composable
internal fun LocalFolderOperationDialog(
    source: LocalFolderSource,
    operation: LocalFolderOperation,
    entryUrl: String?,
    parentUrl: String?,
    onDismiss: () -> Unit,
) {
    val entry = remember(entryUrl) { entryUrl?.let(source::indexedEntry) }
    val parent = remember(parentUrl) { parentUrl?.let(source::indexedEntry) }
    val roots = remember { source.folderRoots().filter { entry == null || it.id == entry.rootId } }
    var rootId by remember { mutableStateOf(entry?.rootId ?: parent?.rootId ?: roots.firstOrNull()?.id) }
    var path by remember {
        mutableStateOf(if (operation == LocalFolderOperation.CREATE) parent?.relativePath.orEmpty() else "")
    }
    val initialName = if (operation == LocalFolderOperation.RENAME) {
        entry?.relativePath?.substringAfterLast('/').orEmpty()
    } else {
        ""
    }
    var name by remember { mutableStateOf(initialName) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val title = when (operation) {
        LocalFolderOperation.CREATE -> MR.strings.local_library_create_folder
        LocalFolderOperation.RENAME -> MR.strings.local_library_rename
        LocalFolderOperation.MOVE -> MR.strings.local_library_move_file
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (operation != LocalFolderOperation.RENAME) {
                    roots.forEach { root ->
                        TextButton(enabled = !busy, onClick = {
                            rootId = root.id
                            path = ""
                        }) {
                            val prefix = if (rootId == root.id && path.isEmpty()) "✓ " else ""
                            Text(prefix + root.displayPath.ifBlank { root.relativePath })
                        }
                    }
                    rootId?.let { id ->
                        if (path.isNotEmpty()) {
                            TextButton(enabled = !busy, onClick = { path = path.substringBeforeLast('/', "") }) {
                                Text("‹ $path")
                            }
                        }
                        val folders = source.folderEntries(id).filter {
                            it.relativePath.substringBeforeLast('/', "") == path &&
                                (entry == null || it.relativePath != entry.relativePath)
                        }.sortedBy { it.relativePath }
                        LazyColumn(Modifier.heightIn(max = 220.dp)) {
                            items(folders, key = { it.itemKey }) { folder ->
                                TextButton(enabled = !busy, onClick = { path = folder.relativePath }) {
                                    Text(folder.relativePath.substringAfterLast('/'))
                                }
                            }
                        }
                    }
                }
                if (operation != LocalFolderOperation.MOVE) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                        },
                        enabled = !busy,
                        singleLine = true,
                        label = {
                            Text(stringResource(MR.strings.name))
                        },
                    )
                }
                if (failed) Text(stringResource(MR.strings.local_library_file_operation_failed))
                if (busy) tachiyomi.presentation.core.components.EInkLinearProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy &&
                    rootId != null &&
                    (
                        operation == LocalFolderOperation.MOVE ||
                            runCatching {
                                validateLocalName(name)
                            }
                                .isSuccess
                        ),
                onClick = {
                    busy = true
                    scope.launch {
                        val result = when (operation) {
                            LocalFolderOperation.CREATE -> source.createFolder(checkNotNull(rootId), path, name)
                            LocalFolderOperation.RENAME -> source.relocateEntry(checkNotNull(entryUrl), newName = name)
                            LocalFolderOperation.MOVE -> source.relocateEntry(
                                checkNotNull(entryUrl),
                                destination = path,
                            )
                        }
                        busy = false
                        failed = result.isFailure
                        if (result.isSuccess) onDismiss()
                    }
                },
            ) { Text(stringResource(MR.strings.action_ok)) }
        },
        dismissButton = {
            TextButton(
                enabled = !busy,
                onClick = onDismiss,
            ) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
