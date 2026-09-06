package koharia.source.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import koharia.connection.ConnectionLibraryRefreshAdapter
import koharia.connection.ConnectionProfileManager
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkLinearProgressIndicator
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID
import tachiyomi.core.common.i18n.stringResource as contextStringResource

class LocalFolderSettingsScreen(
    private val sourceId: Long,
    private val profileName: String,
    private val titleOverride: String?,
    private val isNew: Boolean = false,
    private val completeOnboardingOnSave: Boolean = false,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val json = remember { Injekt.get<Json>() }
        val preferences = remember(sourceId) { LocalLibraryPreferences(sourceId, json) }
        val profileManager = remember { Injekt.get<ConnectionProfileManager>() }
        val basePreferences = remember { Injekt.get<BasePreferences>() }
        val comicsName = stringResource(MR.strings.local_library_default_comics_bookshelf)
        val booksName = stringResource(MR.strings.local_library_default_books_bookshelf)
        val initial = remember(sourceId) { preferences.getConfig() }
        val storagePreferences = remember { Injekt.get<StoragePreferences>() }
        val storageDirectory by storagePreferences.baseStorageDirectory.changes()
            .collectAsState(initial = storagePreferences.baseStorageDirectory.get())
        val pickStorageDirectory = SettingsDataScreen.storageLocationPicker(storagePreferences.baseStorageDirectory)
        val createInitialDirectories = remember(sourceId) {
            shouldCreateInitialLocalDirectories(sourceId, initial, profileManager.profiles())
        }
        var config by rememberSaveable(
            sourceId,
            stateSaver = Saver<LocalLibraryConfig, String>(
                save = { json.encodeToString(it) },
                restore = { json.decodeFromString(it) },
            ),
        ) { mutableStateOf(initial.withInitialBookshelves(comicsName, booksName)) }
        var assignments by rememberSaveable(
            sourceId,
            stateSaver = Saver<Map<String, String>, String>(
                save = { json.encodeToString(it) },
                restore = { json.decodeFromString(it) },
            ),
        ) { mutableStateOf(preferences.getBookshelfAssignments()) }
        var initialDirectoriesCreated by rememberSaveable(sourceId) { mutableStateOf(!createInitialDirectories) }
        var initialDirectoryError by remember { mutableStateOf(false) }
        var isPreparingDirectories by remember { mutableStateOf(false) }
        var selectedDefaultShelfIds by rememberSaveable(sourceId) { mutableStateOf(emptyList<String>()) }
        val defaultModesChosen = config.hasSelectedDefaultModes(selectedDefaultShelfIds)
        var connectionName by rememberSaveable(sourceId) { mutableStateOf(profileName) }
        var setupStep by rememberSaveable(sourceId) { mutableStateOf(LocalLibrarySetupStep.NAME) }
        var editingShelfId by rememberSaveable(sourceId) { mutableStateOf<String?>(null) }
        var pickerShelfId by rememberSaveable(sourceId) { mutableStateOf<String?>(null) }
        var replacingRootId by rememberSaveable(sourceId) { mutableStateOf<String?>(null) }
        var rootToRemove by remember { mutableStateOf<LocalLibraryRootConfig?>(null) }
        var shelfToRemove by remember { mutableStateOf<LocalBookshelf?>(null) }
        var showUnsavedDialog by remember { mutableStateOf(false) }
        var isSaving by remember { mutableStateOf(false) }
        var isInitialScanning by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val editingShelf = editingShelfId?.let(config::bookshelf)
        fun shelfName(shelf: LocalBookshelf): String = shelf.name.ifBlank {
            if (shelf.contentType == LocalLibraryContentType.BOOKS) booksName else comicsName
        }
        val validShelves = config.bookshelves.filter {
            it.contentType in config.enabledContentTypes
        }.groupBy { it.contentType }.values.all { shelves ->
            shelves.map { shelfName(it).trim().lowercase() }.let { names -> names.distinct().size == names.size }
        }
        fun updateShelf(shelf: LocalBookshelf) {
            config = config.copy(bookshelves = config.bookshelves.map { if (it.id == shelf.id) shelf else it })
        }
        fun completeLibrariesStep() {
            if (isPreparingDirectories || isSaving || !validShelves || !defaultModesChosen) return
            if (!createInitialDirectories) {
                setupStep = LocalLibrarySetupStep.METADATA
                return
            }
            isPreparingDirectories = true
            initialDirectoryError = false
            scope.launch {
                try {
                    config = tachiyomi.core.common.util.lang.withIOContext {
                        require(storagePreferences.baseStorageDirectory.isSet())
                        prepareInitialLocalDirectories(
                            context,
                            config,
                            storageDirectory,
                            directoryDisplayPath(context, Uri.parse(storageDirectory)),
                            json,
                        )
                    }
                    initialDirectoriesCreated = true
                    setupStep = LocalLibrarySetupStep.METADATA
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    initialDirectoryError = true
                } finally {
                    isPreparingDirectories = false
                }
            }
        }
        fun discard() {
            scope.launch {
                if (isNew) profileManager.remove(sourceId)
                navigator.pop()
            }
        }
        fun back() {
            if (isSaving || isPreparingDirectories) return
            if (editingShelfId != null) {
                editingShelfId = null
            } else if (config != initial || connectionName.trim() != profileName) {
                showUnsavedDialog = true
            } else {
                discard()
            }
        }
        fun save() {
            if (isSaving || !initialDirectoriesCreated || !defaultModesChosen || connectionName.isBlank() ||
                !validShelves
            ) {
                return
            }
            isSaving = true
            scope.launch {
                try {
                    val profile = requireNotNull(profileManager.profiles().firstOrNull { it.id == sourceId })
                    val scanAdapter = Injekt.get<SourceManager>().get(sourceId) as? ConnectionLibraryRefreshAdapter
                        ?: LocalFolderSource(context, sourceId, connectionName.trim(), profile)
                    profileManager.update(profile.copy(name = connectionName.trim()))
                    val enabledConfig = if (config.setupCompleted) config else config.enabledLibraryConfiguration()
                    isInitialScanning = !preferences.getConfig().setupCompleted
                    val scanResult = tachiyomi.core.common.util.lang.withIOContext {
                        saveLocalLibraryConfiguration(
                            preferences,
                            enabledConfig.copy(libraryId = config.libraryId.ifBlank { UUID.randomUUID().toString() }),
                            assignments.filterValues { shelfId -> enabledConfig.bookshelf(shelfId) != null },
                            scan = { scanAdapter.refreshLibrary() },
                        )
                    }
                    if (scanResult?.isFailure == true) context.toast(MR.strings.local_library_initial_scan_failed)
                    if (completeOnboardingOnSave) {
                        basePreferences.shownOnboardingFlow.set(true)
                        navigator.popUntilRoot()
                    } else {
                        navigator.pop()
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    context.toast(MR.strings.local_library_save_failed)
                } finally {
                    isInitialScanning = false
                    isSaving = false
                }
            }
        }
        fun attachDirectory(
            root: LocalLibraryRootConfig,
            shelfId: String? = pickerShelfId,
            replacing: String? = replacingRootId,
        ) {
            val targetId = shelfId ?: return
            val result = runCatching { config.withBookshelfDirectory(targetId, root, replacing) }
            result.onSuccess { config = it }
                .onFailure { context.toast(MR.strings.local_library_directory_already_added) }
            replacingRootId = null
        }
        BackHandler(onBack = ::back)
        val chooseDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null && pickerShelfId != null) {
                if (persistDirectoryPermission(context, uri, requireWrite = false)) {
                    attachDirectory(
                        LocalLibraryRootConfig(
                            id = UUID.randomUUID().toString(),
                            treeUri = uri.toString(),
                            displayPath = directoryDisplayPath(context, uri),
                        ),
                    )
                } else {
                    context.toast(MR.strings.local_library_permission_failed)
                }
            }
        }
        val chooseManagedDirectory =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                val shelf = pickerShelfId?.let(config::bookshelf)
                if (uri != null && shelf != null) {
                    if (!persistDirectoryPermission(context, uri, requireWrite = true)) {
                        context.toast(MR.strings.local_library_write_permission_failed)
                    } else {
                        scope.launch {
                            isSaving = true
                            val layout = tachiyomi.core.common.util.lang.withIOContext {
                                createManagedLayout(
                                    context,
                                    uri,
                                    directoryDisplayPath(context, uri),
                                    config.libraryId.ifBlank { UUID.randomUUID().toString() },
                                    setOf(shelf.contentType),
                                    json,
                                )
                            }
                            if (layout != null) {
                                attachDirectory(layout.roots.single(), shelf.id, null)
                                config = config.copy(
                                    libraryId = layout.libraryId,
                                    managedBaseTreeUri = config.managedBaseTreeUri.ifBlank { uri.toString() },
                                    managedBaseDisplayPath = config.managedBaseDisplayPath.ifBlank {
                                        directoryDisplayPath(context, uri)
                                    },
                                )
                            } else {
                                context.toast(MR.strings.local_library_create_failed)
                            }
                            isSaving = false
                        }
                    }
                }
            }
        Scaffold(
            topBar = {
                AppBar(
                    title = editingShelf?.let(::shelfName) ?: titleOverride ?: connectionName,
                    subtitle = editingShelf?.let { shelf -> bookshelfTypeTitle(shelf.contentType) },
                    navigateUp = ::back,
                    scrollBehavior = it,
                )
            },
            bottomBar = {
                if (editingShelf != null) {
                    Button(
                        onClick = { editingShelfId = null },
                        enabled = !isSaving && validShelves,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    ) { Text(stringResource(MR.strings.action_ok)) }
                } else if (config.setupCompleted) {
                    Button(
                        onClick = ::save,
                        enabled = !isSaving && connectionName.isNotBlank() && validShelves,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    ) { Text(stringResource(MR.strings.action_save)) }
                }
            },
        ) { padding ->
            ScrollbarLazyColumn(contentPadding = padding) {
                if (isSaving ||
                    isPreparingDirectories
                ) {
                    item { EInkLinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }
                if (isInitialScanning) {
                    item {
                        SetupNotice(stringResource(MR.strings.local_library_initial_scanning))
                    }
                }
                if (initialDirectoryError) {
                    item {
                        SetupNotice(stringResource(MR.strings.local_library_auto_directories_failed), warning = true)
                    }
                    item {
                        TextPreferenceWidget(
                            title = stringResource(MR.strings.action_retry),
                            onPreferenceClick = ::completeLibrariesStep,
                        )
                    }
                    item {
                        TextPreferenceWidget(
                            title = stringResource(MR.strings.onboarding_storage_action_select),
                            onPreferenceClick = { pickStorageDirectory.launch(null) },
                        )
                    }
                }
                if (editingShelf != null) {
                    item {
                        OutlinedTextField(
                            value = shelfName(editingShelf),
                            onValueChange = {
                                updateShelf(editingShelf.copy(name = it))
                            },
                            label = { Text(stringResource(MR.strings.name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            isError = !validShelves,
                            supportingText = {
                                if (!validShelves) Text(stringResource(MR.strings.local_library_bookshelf_exists))
                            },
                        )
                    }
                    item { PreferenceGroupHeader(title = stringResource(MR.strings.local_library_shelf_directories)) }
                    val roots = config.bookshelfRoots(editingShelf.id)
                    if (roots.isEmpty()) {
                        item {
                            SetupNotice(stringResource(MR.strings.local_library_shelf_no_directories))
                        }
                    }
                    items(roots, key = LocalLibraryRootConfig::id) { root ->
                        TextPreferenceWidget(
                            title = root.displayPath.ifBlank { root.treeUri },
                            subtitle = stringResource(MR.strings.local_library_shelf_replace_directory),
                            onPreferenceClick = {
                                pickerShelfId = editingShelf.id
                                replacingRootId = root.id
                                chooseDirectory.launch(Uri.parse(root.treeUri))
                            },
                            widget = {
                                IconButton(onClick = { rootToRemove = root }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        stringResource(MR.strings.local_library_remove_directory),
                                    )
                                }
                            },
                        )
                    }
                    item {
                        DirectorySetupActions(
                            showCreateManaged = true,
                            onAddExisting = {
                                pickerShelfId = editingShelf.id
                                replacingRootId = null
                                chooseDirectory.launch(null)
                            },
                            onCreateManaged = {
                                pickerShelfId = editingShelf.id
                                replacingRootId = null
                                chooseManagedDirectory.launch(null)
                            },
                        )
                    }
                    item { PreferenceGroupHeader(title = stringResource(MR.strings.local_library_organization_title)) }
                    val modeLocked = !config.canEditBookshelfMode(editingShelf.id, initial, assignments)
                    if (modeLocked) {
                        item {
                            TextPreferenceWidget(
                                title = if (!config.setupCompleted && config.isDefaultBookshelf(editingShelf.id) &&
                                    editingShelf.id !in selectedDefaultShelfIds
                                ) {
                                    stringResource(MR.strings.local_library_select_default_mode)
                                } else {
                                    organizationModeLabel(editingShelf.organizationMode)
                                },
                            )
                        }
                    } else {
                        item {
                            LocalLibraryOrganizationModePicker(
                                selectedMode = editingShelf.organizationMode,
                                enabled = !isSaving,
                                onSelect = { updateShelf(editingShelf.copy(organizationMode = it)) },
                            )
                        }
                        item {
                            TextPreferenceWidget(
                                title = stringResource(MR.strings.local_library_organization_guide_title),
                                onPreferenceClick = { navigator.push(LocalLibraryOrganizationModeGuideScreen()) },
                            )
                        }
                    }
                    if (config.defaultBookshelfId(editingShelf.contentType) != editingShelf.id) {
                        item {
                            TextPreferenceWidget(
                                title = stringResource(MR.strings.local_library_set_as_default),
                                onPreferenceClick = {
                                    config =
                                        config.withDefaultBookshelf(editingShelf.contentType, editingShelf.id)
                                },
                            )
                        }
                    }
                    if (config.canRemoveBookshelf(editingShelf.id, assignments)) {
                        item {
                            TextPreferenceWidget(
                                title = stringResource(MR.strings.local_library_delete_bookshelf),
                                onPreferenceClick = { shelfToRemove = editingShelf },
                            )
                        }
                    }
                } else {
                    if (!config.setupCompleted) item { SetupProgress(setupStep) }
                    if (config.setupCompleted || setupStep == LocalLibrarySetupStep.NAME) {
                        item {
                            OutlinedTextField(
                                value = connectionName,
                                onValueChange = { connectionName = it },
                                label = { Text(stringResource(MR.strings.name)) },
                                supportingText = if (connectionName.isBlank()) {
                                    { Text(stringResource(MR.strings.information_required_plain)) }
                                } else {
                                    null
                                },
                                isError = connectionName.isBlank(),
                                singleLine = true,
                                enabled = !isSaving && !isPreparingDirectories,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (!config.setupCompleted && setupStep == LocalLibrarySetupStep.NAME) {
                        item {
                            SetupNavigation(
                                enabled = !isSaving && !isPreparingDirectories,
                                onNext = { setupStep = LocalLibrarySetupStep.LIBRARIES },
                                nextEnabled =
                                connectionName.isNotBlank() && !isPreparingDirectories,
                            )
                        }
                    }
                    if (config.setupCompleted || setupStep == LocalLibrarySetupStep.LIBRARIES) {
                        if (!config.setupCompleted) {
                            item {
                                TextPreferenceWidget(
                                    title = stringResource(MR.strings.local_library_organization_guide_title),
                                    subtitle = stringResource(MR.strings.local_library_organization_guide_summary),
                                    icon = Icons.Outlined.Info,
                                    onPreferenceClick = { navigator.push(LocalLibraryOrganizationModeGuideScreen()) },
                                )
                            }
                        } else {
                            item { SetupNotice(stringResource(MR.strings.local_library_shelf_first_summary)) }
                        }
                        listOf(LocalLibraryContentType.COMICS, LocalLibraryContentType.BOOKS).forEach { type ->
                            val enabled = type in config.enabledContentTypes
                            if (!config.setupCompleted) {
                                item {
                                    ListItem(
                                        modifier = Modifier.toggleable(
                                            value = enabled,
                                            enabled = !isSaving && !isPreparingDirectories,
                                            role = Role.Checkbox,
                                            onValueChange = { checked ->
                                                config = config.copy(
                                                    enabledContentTypes = if (checked) {
                                                        config.enabledContentTypes + type
                                                    } else {
                                                        config.enabledContentTypes -
                                                            type
                                                    },
                                                )
                                                if (createInitialDirectories) initialDirectoriesCreated = false
                                                initialDirectoryError = false
                                            },
                                        ),
                                        headlineContent = { Text(bookshelfTypeTitle(type)) },
                                        leadingContent = {
                                            Checkbox(
                                                checked = enabled,
                                                onCheckedChange = null,
                                                enabled =
                                                !isSaving && !isPreparingDirectories,
                                            )
                                        },
                                    )
                                }
                            } else {
                                item { PreferenceGroupHeader(title = bookshelfTypeTitle(type)) }
                            }
                            if (!enabled && !config.setupCompleted) return@forEach
                            val shelves = config.bookshelvesFor(type)
                            items(shelves, key = LocalBookshelf::id) { shelf ->
                                val directorySummary = if (createInitialDirectories && !initialDirectoriesCreated &&
                                    config.bookshelfRoots(shelf.id).isEmpty()
                                ) {
                                    stringResource(MR.strings.local_library_auto_directory_pending)
                                } else {
                                    stringResource(
                                        MR.strings.local_library_shelf_directory_count,
                                        config.bookshelfRoots(shelf.id).size,
                                    )
                                }
                                val showMode = config.setupCompleted || !config.isDefaultBookshelf(shelf.id) ||
                                    shelf.id in selectedDefaultShelfIds
                                TextPreferenceWidget(
                                    title = shelfName(shelf),
                                    subtitle = if (showMode) {
                                        "$directorySummary · ${organizationModeLabel(
                                            shelf.organizationMode,
                                        )}"
                                    } else {
                                        directorySummary
                                    },
                                    icon = Icons.Outlined.FolderOpen,
                                    onPreferenceClick = { editingShelfId = shelf.id },
                                )
                                if (!config.setupCompleted && config.isDefaultBookshelf(shelf.id)) {
                                    LocalLibraryOrganizationModePicker(
                                        selectedMode = shelf.organizationMode.takeIf {
                                            shelf.id in
                                                selectedDefaultShelfIds
                                        },
                                        enabled = !isPreparingDirectories && !isSaving,
                                        onSelect = { mode ->
                                            updateShelf(shelf.copy(organizationMode = mode))
                                            selectedDefaultShelfIds = (selectedDefaultShelfIds + shelf.id).distinct()
                                        },
                                    )
                                }
                            }
                            item {
                                TextPreferenceWidget(
                                    title = stringResource(MR.strings.local_library_add_bookshelf),
                                    icon = Icons.Outlined.Add,
                                    onPreferenceClick = {
                                        val baseName = if (type ==
                                            LocalLibraryContentType.COMICS
                                        ) {
                                            comicsName
                                        } else {
                                            booksName
                                        }
                                        val name = generateSequence(1) { it + 1 }.map { "$baseName $it" }
                                            .first { candidate ->
                                                shelves.none { shelfName(it).equals(candidate, true) }
                                            }
                                        val shelf = LocalBookshelf(
                                            UUID.randomUUID().toString(),
                                            name,
                                            type,
                                            if (type ==
                                                LocalLibraryContentType.COMICS
                                            ) {
                                                LocalLibraryOrganizationMode.SERIES
                                            } else {
                                                LocalLibraryOrganizationMode.INDIVIDUAL_FILES
                                            },
                                        )
                                        config = config.copy(
                                            bookshelves = config.bookshelves + shelf,
                                            enabledContentTypes = config.enabledContentTypes + type,
                                        )
                                        editingShelfId = shelf.id
                                    },
                                )
                            }
                        }
                        if (!config.setupCompleted) {
                            if (config.enabledContentTypes.isEmpty()) {
                                item {
                                    SetupNotice(stringResource(MR.strings.local_library_enable_one), warning = true)
                                }
                            }
                            item {
                                SetupNavigation(
                                    enabled = !isSaving && !isPreparingDirectories,
                                    onPrevious = { setupStep = LocalLibrarySetupStep.NAME },
                                    onNext = ::completeLibrariesStep,
                                    nextEnabled = validShelves && defaultModesChosen && !isPreparingDirectories,
                                )
                            }
                        }
                    }
                    if (config.setupCompleted) {
                        item {
                            TextPreferenceWidget(
                                title = stringResource(MR.strings.local_library_metadata_storage),
                                subtitle = metadataLabel(context, config.metadataStorage),
                                enabled = false,
                            )
                        }
                    } else if (setupStep == LocalLibrarySetupStep.METADATA) {
                        item { SetupNotice(stringResource(MR.strings.local_library_setup_metadata_summary)) }
                        items(LocalMetadataStorage.entries) { storage ->
                            MetadataStorageOption(
                                storage,
                                config.metadataStorage == storage,
                                onSelect = { config = config.copy(metadataStorage = storage) },
                            )
                        }
                        item {
                            SetupNavigation(
                                enabled = !isSaving && !isPreparingDirectories,
                                onPrevious = { setupStep = LocalLibrarySetupStep.LIBRARIES },
                                onNext = ::save,
                                nextEnabled =
                                !isSaving && validShelves && defaultModesChosen && initialDirectoriesCreated,
                                nextLabel = stringResource(MR.strings.local_library_setup_finish),
                            )
                        }
                    }
                }
            }
        }
        rootToRemove?.let { root ->
            LocalConfigurationRemovalDialog(
                title = stringResource(MR.strings.local_library_remove_directory),
                message = stringResource(MR.strings.local_library_remove_directory_summary),
                onDismiss = { rootToRemove = null },
                onConfirm = {
                    config = config.withoutRoot(root.id)
                    rootToRemove = null
                },
            )
        }
        shelfToRemove?.let { shelf ->
            LocalConfigurationRemovalDialog(
                title = stringResource(MR.strings.local_library_delete_bookshelf),
                message = stringResource(MR.strings.local_library_delete_bookshelf_summary, shelfName(shelf)),
                onDismiss = { shelfToRemove = null },
                onConfirm = {
                    config.withoutBookshelf(shelf.id, assignments)?.let { removal ->
                        config = removal.config
                        assignments = removal.assignments
                        editingShelfId = null
                    }
                    shelfToRemove = null
                },
            )
        }
        if (showUnsavedDialog) {
            LocalConfigurationRemovalDialog(
                title = stringResource(MR.strings.local_library_unsaved_changes_title),
                message = stringResource(MR.strings.local_library_unsaved_changes_message),
                onDismiss = { showUnsavedDialog = false },
                onConfirm = {
                    showUnsavedDialog = false
                    discard()
                },
            )
        }
    }
}

@Composable
private fun LocalConfigurationRemovalDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(MR.strings.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun SetupProgress(step: LocalLibrarySetupStep) {
    val stepNumber = when (step) {
        LocalLibrarySetupStep.NAME -> 1
        LocalLibrarySetupStep.LIBRARIES -> 2
        LocalLibrarySetupStep.METADATA -> 3
    }
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(MR.strings.local_library_setup_progress, stepNumber, 3),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        EInkLinearProgressIndicator(
            progress = { stepNumber / 3f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SetupNavigation(
    onNext: () -> Unit,
    enabled: Boolean = true,
    nextEnabled: Boolean = true,
    nextLabel: String = stringResource(MR.strings.local_library_setup_next),
    onPrevious: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onPrevious != null) {
            TextButton(onClick = onPrevious, enabled = enabled) {
                Text(text = stringResource(MR.strings.local_library_setup_previous))
            }
        } else {
            Text(text = "")
        }
        TextButton(
            enabled = enabled && nextEnabled,
            onClick = onNext,
        ) {
            Text(text = nextLabel)
        }
    }
}

@Composable
private fun DirectorySetupActions(
    showCreateManaged: Boolean,
    onAddExisting: () -> Unit,
    onCreateManaged: () -> Unit,
) {
    Column {
        if (showCreateManaged) {
            TextPreferenceWidget(
                title = stringResource(MR.strings.local_library_create_managed),
                subtitle = stringResource(MR.strings.local_library_create_managed_summary),
                icon = Icons.Outlined.Add,
                onPreferenceClick = onCreateManaged,
            )
        }
        TextPreferenceWidget(
            title = stringResource(MR.strings.local_library_add_existing),
            subtitle = stringResource(MR.strings.local_library_add_existing_summary),
            icon = Icons.Outlined.FolderOpen,
            onPreferenceClick = onAddExisting,
        )
    }
}

@Composable
private fun SetupNotice(
    text: String,
    warning: Boolean = false,
) {
    TextPreferenceWidget(
        subtitle = text,
        icon = if (warning) Icons.Outlined.Warning else Icons.Outlined.Info,
        iconTint = if (warning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun MetadataStorageOption(
    storage: LocalMetadataStorage,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onSelect),
        headlineContent = { Text(text = metadataLabel(LocalContext.current, storage)) },
        supportingContent = { Text(text = metadataSummary(storage)) },
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = onSelect,
            )
        },
    )
}

@Composable
internal fun organizationModeLabel(mode: LocalLibraryOrganizationMode): String = stringResource(
    when (mode) {
        LocalLibraryOrganizationMode.SERIES -> MR.strings.local_library_mode_series
        LocalLibraryOrganizationMode.INDIVIDUAL_FILES -> MR.strings.local_library_mode_individual
    },
)

@Composable
private fun metadataSummary(storage: LocalMetadataStorage): String {
    return stringResource(
        when (storage) {
            LocalMetadataStorage.DATABASE -> MR.strings.local_library_metadata_database_summary
            LocalMetadataStorage.ADJACENT_SIDECAR -> MR.strings.local_library_metadata_adjacent_summary
            LocalMetadataStorage.UNIFIED_DIRECTORY -> MR.strings.local_library_metadata_unified_summary
        },
    )
}

private fun LocalLibraryConfig.withoutRoot(rootId: String): LocalLibraryConfig {
    val removed = roots.firstOrNull { it.id == rootId } ?: return this
    val remaining = roots.filterNot { it.id == rootId }
    val keepManagedBase = managedBaseTreeUri != removed.treeUri ||
        remaining.any { it.treeUri == managedBaseTreeUri && it.managed }
    return copy(
        roots = remaining,
        managedBaseTreeUri = managedBaseTreeUri.takeIf { keepManagedBase }.orEmpty(),
        managedBaseDisplayPath = managedBaseDisplayPath.takeIf { keepManagedBase }.orEmpty(),
    )
}

private fun metadataLabel(context: Context, storage: LocalMetadataStorage): String {
    return when (storage) {
        LocalMetadataStorage.DATABASE -> context.contextStringResource(MR.strings.local_library_metadata_database)
        LocalMetadataStorage.ADJACENT_SIDECAR -> context.contextStringResource(
            MR.strings.local_library_metadata_adjacent,
        )
        LocalMetadataStorage.UNIFIED_DIRECTORY -> context.contextStringResource(
            MR.strings.local_library_metadata_unified,
        )
    }
}

private fun persistDirectoryPermission(context: Context, uri: Uri, requireWrite: Boolean): Boolean {
    val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION
    val writeFlag = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    return runCatching {
        context.contentResolver.takePersistableUriPermission(uri, readFlag or writeFlag)
        true
    }.getOrElse {
        if (requireWrite) {
            false
        } else {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, readFlag)
                true
            }.getOrDefault(false)
        }
    }
}

private fun directoryDisplayPath(context: Context, uri: Uri): String {
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    val documentPath = documentId?.let { id ->
        when {
            id.startsWith("raw:", ignoreCase = true) -> id.substringAfter(':')
            uri.authority == EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY -> {
                val volumeId = id.substringBefore(':')
                val relativePath = id.substringAfter(':', missingDelimiterValue = "").trim('/')
                val volumePath = if (volumeId.equals("primary", ignoreCase = true)) {
                    "/storage/emulated/0"
                } else {
                    "/storage/$volumeId"
                }
                if (relativePath.isBlank()) volumePath else "$volumePath/$relativePath"
            }
            id.contains('/') -> id
            else -> null
        }
    }
    return documentPath
        ?: UniFile.fromUri(context, uri)?.uri?.toString()?.takeIf(String::isNotBlank)
        ?: uri.toString()
}

private enum class LocalLibrarySetupStep {
    NAME,
    LIBRARIES,
    METADATA,
}

private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"
