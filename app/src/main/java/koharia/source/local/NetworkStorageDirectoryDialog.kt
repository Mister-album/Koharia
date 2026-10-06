package koharia.source.local

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import koharia.storage.LibraryStorageBackend
import koharia.storage.StorageEntry
import koharia.storage.StorageFailure
import koharia.storage.StoragePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkLinearProgressIndicator
import tachiyomi.presentation.core.i18n.stringResource

internal fun validStorageFolderName(name: String): Boolean =
    name.isNotBlank() && name == name.trim() && !name.equals(".koharia", ignoreCase = true) &&
        runCatching { StoragePath.child("", name) == name }.getOrDefault(false)

internal suspend fun createStorageDirectory(backend: LibraryStorageBackend, parent: String, name: String) {
    require(validStorageFolderName(name))
    if (!backend.capabilities.writable || backend.directoryCreationAllowed(parent) == false) {
        throw StorageFailure(StorageFailure.Reason.PERMISSION)
    }
    val child = StoragePath.child(parent, name)
    try {
        backend.stat(child)
        throw StorageFailure(StorageFailure.Reason.CONFLICT)
    } catch (error: StorageFailure) {
        if (error.reason != StorageFailure.Reason.NOT_FOUND) throw error
    }
    // Create only: no retry, overwrite, address failover or deletion after an ambiguous response.
    backend.createDirectory(child)
}

@Composable
internal fun NetworkStorageDirectoryDialog(
    initialPath: String,
    draft: NetworkStorageDraft,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    loadDirectories: suspend (String) -> List<StorageEntry> = draft::directories,
    allowRootSelection: Boolean = true,
    createDirectory: suspend (String, String) -> Unit = { parent, name -> draft.createDirectory(parent, name) },
    creationAllowed: suspend (String) -> Boolean? = { draft.canCreateDirectory(it) },
) {
    var path by remember(draft, initialPath) { mutableStateOf(StoragePath.normalize(initialPath)) }
    var retry by remember { mutableStateOf(0) }
    var directories by remember(draft, path, retry) { mutableStateOf<List<StorageEntry>?>(null) }
    var failure by remember(draft, path, retry) { mutableStateOf<StorageFailure.Reason?>(null) }
    var canCreate by remember(draft, path, retry) { mutableStateOf<Boolean?>(false) }
    var creationDenied by remember(draft, path) { mutableStateOf(false) }
    var newFolder by remember(draft, path) { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var editingPath by remember(draft, path) { mutableStateOf(false) }
    var pathInput by remember(draft, path) { mutableStateOf(path) }
    var pathInvalid by remember(draft, path) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(draft, path, retry) {
        try {
            directories = loadDirectories(path)
            canCreate = try {
                creationAllowed(path)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure = (error as? StorageFailure)?.reason ?: StorageFailure.Reason.NETWORK
        }
    }
    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text(stringResource(MR.strings.storage_browse_folders)) },
        text = {
            Column {
                Text(path.ifEmpty { "/" }, modifier = Modifier.testTag("storage-browser-path"))
                if (editingPath) {
                    OutlinedTextField(
                        value = pathInput,
                        onValueChange = {
                            pathInput = it
                            pathInvalid = false
                        },
                        label = { Text(stringResource(MR.strings.storage_browse_path)) },
                        singleLine = true,
                        enabled = !creating,
                        isError = pathInvalid,
                        supportingText = if (pathInvalid) {
                            { Text(stringResource(MR.strings.storage_browse_path_invalid)) }
                        } else {
                            null
                        },
                        modifier = Modifier.testTag("storage-browser-path-input").fillMaxWidth(),
                    )
                    Row {
                        TextButton(
                            enabled = !creating,
                            onClick = {
                                val normalized = runCatching { StoragePath.normalize(pathInput) }.getOrNull()
                                if (normalized == null) {
                                    pathInvalid = true
                                } else {
                                    editingPath = false
                                    pathInvalid = false
                                    path = normalized
                                }
                            },
                            modifier = Modifier.testTag("storage-browser-path-apply"),
                        ) { Text(stringResource(MR.strings.action_ok)) }
                        TextButton(
                            enabled = !creating,
                            onClick = {
                                editingPath = false
                                pathInvalid = false
                            },
                            modifier = Modifier.testTag("storage-browser-path-cancel"),
                        ) { Text(stringResource(MR.strings.action_cancel)) }
                    }
                } else {
                    Row {
                        TextButton(
                            enabled = !creating,
                            onClick = {
                                pathInput = path
                                editingPath = true
                            },
                            modifier = Modifier.testTag("storage-browser-manual-path"),
                        ) { Text(stringResource(MR.strings.storage_browse_manual_path)) }
                        if (path.isNotEmpty()) {
                            TextButton(enabled = !creating, onClick = { path = StoragePath.parent(path) }) {
                                Text(stringResource(MR.strings.storage_parent_folder))
                            }
                        }
                    }
                }
                if (allowRootSelection || path.isNotEmpty()) {
                    TextButton(
                        enabled =
                        directories != null && failure == null && canCreate != false && !creationDenied && !creating,
                        onClick = { newFolder = true },
                        modifier = Modifier.testTag("storage-browser-create"),
                    ) { Text(stringResource(MR.strings.local_library_create_folder)) }
                }
                if (failure != null) {
                    Text(
                        stringResource(
                            when (failure) {
                                StorageFailure.Reason.AUTH -> MR.strings.storage_browse_auth_failed
                                StorageFailure.Reason.PERMISSION -> MR.strings.storage_browse_permission_denied
                                else -> MR.strings.storage_browse_failed
                            },
                        ),
                    )
                    TextButton(onClick = { retry++ }) { Text(stringResource(MR.strings.action_retry)) }
                } else if (directories == null) {
                    EInkLinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (directories!!.isEmpty()) {
                    Text(
                        stringResource(
                            if (!allowRootSelection && path.isEmpty()) {
                                MR.strings.storage_smb_no_shares
                            } else {
                                MR.strings.storage_no_subfolders
                            },
                        ),
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().height(280.dp).testTag("storage-browser-folders")) {
                    items(directories.orEmpty(), key = { it.path }) { directory ->
                        ListItem(
                            headlineContent = { Text(directory.name) },
                            leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                            modifier = Modifier.clickable(enabled = !creating) { path = directory.path },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled =
                !creating && directories != null && failure == null && (allowRootSelection || path.isNotEmpty()),
                onClick = { onConfirm(path) },
                modifier = Modifier.testTag("storage-browser-select"),
            ) { Text(stringResource(MR.strings.storage_select_current_folder)) }
        },
        dismissButton = {
            TextButton(enabled = !creating, onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
    if (newFolder) {
        var name by remember { mutableStateOf("") }
        var createError by remember { mutableStateOf<StorageFailure.Reason?>(null) }
        AlertDialog(
            onDismissRequest = { if (!creating) newFolder = false },
            title = { Text(stringResource(MR.strings.local_library_create_folder)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            createError = null
                        },
                        label = { Text(stringResource(MR.strings.name)) },
                        singleLine = true,
                        enabled = !creating,
                        modifier = Modifier.testTag("storage-new-folder-name"),
                    )
                    if (creating) EInkLinearProgressIndicator(Modifier.fillMaxWidth())
                    createError?.let {
                        Text(
                            stringResource(
                                when (it) {
                                    StorageFailure.Reason.PERMISSION -> MR.strings.storage_create_denied
                                    StorageFailure.Reason.CONFLICT -> MR.strings.storage_create_exists
                                    else -> MR.strings.storage_create_unconfirmed
                                },
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !creating && canCreate != false && !creationDenied && validStorageFolderName(name),
                    modifier = Modifier.testTag("storage-new-folder-confirm"),
                    onClick = {
                        creating = true
                        scope.launch {
                            try {
                                createDirectory(path, name)
                                newFolder = false
                                path = StoragePath.child(path, name)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                createError = (error as? StorageFailure)?.reason ?: StorageFailure.Reason.NETWORK
                                if (createError == StorageFailure.Reason.PERMISSION) creationDenied = true
                            } finally {
                                creating = false
                                retry++
                            }
                        }
                    },
                ) { Text(stringResource(MR.strings.action_ok)) }
            },
            dismissButton = {
                TextButton(enabled = !creating, onClick = {
                    newFolder = false
                }, modifier = Modifier.testTag("storage-new-folder-cancel")) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}
