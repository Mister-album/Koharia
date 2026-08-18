package koharia.source.local

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import kotlinx.serialization.json.Json
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

class LocalBookshelfManagementScreen(
    private val sourceId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val json = remember { Injekt.get<Json>() }
        val preferences = remember(sourceId) { LocalLibraryPreferences(sourceId, json) }
        var config by remember(sourceId) { mutableStateOf(preferences.getConfig()) }
        var editing by remember { mutableStateOf<EditingBookshelf?>(null) }
        var deleting by remember { mutableStateOf<LocalBookshelf?>(null) }

        fun persist(next: LocalLibraryConfig) {
            preferences.setConfig(next)
            config = preferences.getConfig()
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.local_library_manage_bookshelves),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.local_library_organization_guide_title),
                        subtitle = stringResource(MR.strings.local_library_organization_guide_summary),
                        onPreferenceClick = { navigator.push(LocalLibraryOrganizationModeGuideScreen()) },
                    )
                }
                listOf(LocalLibraryContentType.COMICS, LocalLibraryContentType.BOOKS).forEach { type ->
                    val shelves = config.bookshelvesFor(type)
                    item {
                        PreferenceGroupHeader(title = bookshelfTypeTitle(type))
                    }
                    shelves.forEachIndexed { index, shelf ->
                        val isDefault = index == 0
                        item(key = shelf.id) {
                            TextPreferenceWidget(
                                title = shelf.name.ifBlank { defaultBookshelfName(type) },
                                subtitle = listOfNotNull(
                                    organizationModeLabel(shelf.organizationMode),
                                    stringResource(MR.strings.local_library_default_badge).takeIf { isDefault },
                                ).joinToString(" · "),
                                widget = {
                                    EditBookshelfButton {
                                        editing = EditingBookshelf(type, shelf, isDefault = isDefault)
                                    }
                                },
                                onPreferenceClick = {
                                    editing = EditingBookshelf(type, shelf, isDefault = isDefault)
                                },
                            )
                        }
                    }
                    item {
                        TextPreferenceWidget(
                            title = stringResource(MR.strings.local_library_add_bookshelf),
                            onPreferenceClick = { editing = EditingBookshelf(type, null) },
                        )
                    }
                }
            }
        }

        editing?.let { edit ->
            BookshelfNameDialog(
                existingName = edit.bookshelf?.let { shelf ->
                    shelf.name.ifBlank { defaultBookshelfName(edit.contentType) }
                } ?: defaultBookshelfName(edit.contentType)
                    .takeIf { config.bookshelvesFor(edit.contentType).isEmpty() }
                    .orEmpty(),
                existingNames = config.bookshelvesFor(edit.contentType)
                    .filter { it.id != edit.bookshelf?.id }
                    .map { it.name.ifBlank { defaultBookshelfName(edit.contentType) } },
                isEditing = edit.bookshelf != null,
                onDismissRequest = { editing = null },
                initialOrganizationMode = edit.bookshelf?.organizationMode,
                isDefault = edit.isDefault,
                canSetDefault = edit.bookshelf != null && !edit.isDefault,
                onConfirm = { name, organizationMode, makeDefault ->
                    val bookshelfId = edit.bookshelf?.id ?: UUID.randomUUID().toString()
                    val updatedShelf = edit.bookshelf?.copy(name = name)
                        ?: LocalBookshelf(
                            id = bookshelfId,
                            name = name,
                            contentType = edit.contentType,
                            organizationMode = organizationMode,
                        )
                    val currentShelves = config.bookshelvesFor(LocalLibraryContentType.COMICS)
                        .plus(config.bookshelvesFor(LocalLibraryContentType.BOOKS))
                    val updated = if (edit.bookshelf == null) {
                        currentShelves + updatedShelf
                    } else {
                        currentShelves.map { shelf -> if (shelf.id == bookshelfId) updatedShelf else shelf }
                    }
                    var nextConfig = config.copy(
                        bookshelves = updated,
                        enabledContentTypes = config.enabledContentTypes + edit.contentType,
                    )
                    if (makeDefault) {
                        nextConfig = nextConfig.withDefaultBookshelf(edit.contentType, bookshelfId)
                    }
                    persist(nextConfig)
                    editing = null
                },
                onDelete = edit.bookshelf?.takeIf { shelf ->
                    config.canRemoveBookshelf(shelf.id, preferences.getBookshelfAssignments())
                }?.let { shelf ->
                    {
                        editing = null
                        deleting = shelf
                    }
                },
            )
        }

        deleting?.let { shelf ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            preferences.removeBookshelf(shelf.id)
                            config = preferences.getConfig()
                            deleting = null
                        },
                    ) {
                        Text(
                            text = stringResource(MR.strings.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleting = null }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
                title = { Text(text = stringResource(MR.strings.local_library_delete_bookshelf)) },
                text = {
                    Text(
                        text = stringResource(
                            MR.strings.local_library_delete_bookshelf_summary,
                            shelf.name.ifBlank { defaultBookshelfName(shelf.contentType) },
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun BookshelfNameDialog(
    existingName: String,
    existingNames: List<String>,
    isEditing: Boolean,
    initialOrganizationMode: LocalLibraryOrganizationMode?,
    isDefault: Boolean,
    canSetDefault: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (String, LocalLibraryOrganizationMode, Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember(existingName) { mutableStateOf(existingName) }
    var organizationMode by remember(initialOrganizationMode) {
        mutableStateOf(initialOrganizationMode)
    }
    var makeDefault by remember(isDefault) { mutableStateOf(isDefault) }
    val normalized = name.trim()
    val duplicate = existingNames.any { it.equals(normalized, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = normalized.isNotEmpty() && !duplicate && organizationMode != null,
                onClick = { onConfirm(normalized, checkNotNull(organizationMode), makeDefault) },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(
                            text = stringResource(MR.strings.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            }
        },
        title = {
            Text(
                text = stringResource(
                    if (isEditing) {
                        MR.strings.local_library_rename_bookshelf
                    } else {
                        MR.strings.local_library_add_bookshelf
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(text = stringResource(MR.strings.name)) },
                    isError = duplicate,
                    supportingText = {
                        Text(
                            text = stringResource(
                                if (duplicate) {
                                    MR.strings.local_library_bookshelf_exists
                                } else {
                                    MR.strings.information_required_plain
                                },
                            ),
                        )
                    },
                    singleLine = true,
                )
                if (isEditing) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = canSetDefault) { makeDefault = true }
                            .padding(vertical = 8.dp),
                    ) {
                        RadioButton(
                            selected = makeDefault,
                            onClick = if (canSetDefault) ({ makeDefault = true }) else null,
                            enabled = canSetDefault || isDefault,
                        )
                        Column {
                            Text(text = stringResource(MR.strings.local_library_set_as_default))
                            Text(
                                text = stringResource(MR.strings.local_library_default_bookshelf_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(MR.strings.local_library_organization_title),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                LocalLibraryOrganizationMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isEditing) { organizationMode = mode }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = organizationMode == mode,
                            onClick = if (isEditing) null else ({ organizationMode = mode }),
                            enabled = !isEditing,
                        )
                        Column {
                            Text(text = organizationModeLabel(mode))
                            if (organizationMode == mode) {
                                Text(
                                    text = stringResource(MR.strings.local_library_organization_locked),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun EditBookshelfButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = stringResource(MR.strings.action_edit),
        )
    }
}

@Composable
internal fun defaultBookshelfName(type: LocalLibraryContentType): String = stringResource(
    when (type) {
        LocalLibraryContentType.COMICS -> MR.strings.local_library_default_comics_bookshelf
        LocalLibraryContentType.BOOKS -> MR.strings.local_library_default_books_bookshelf
        LocalLibraryContentType.MIXED -> MR.strings.local_library_all_bookshelves
    },
)

@Composable
private fun bookshelfTypeTitle(type: LocalLibraryContentType): String = stringResource(
    when (type) {
        LocalLibraryContentType.COMICS -> MR.strings.local_library_comic_bookshelves
        LocalLibraryContentType.BOOKS -> MR.strings.local_library_book_bookshelves
        LocalLibraryContentType.MIXED -> MR.strings.local_library_bookshelves
    },
)

private data class EditingBookshelf(
    val contentType: LocalLibraryContentType,
    val bookshelf: LocalBookshelf?,
    val isDefault: Boolean = false,
)
