package koharia.source.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.EditTextPreferenceWidget
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import koharia.connection.ConnectionProfileManager
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.charset.StandardCharsets
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
        val basePreferences = remember { Injekt.get<BasePreferences>() }
        val connectionProfileManager = remember { Injekt.get<ConnectionProfileManager>() }
        val preferences = remember(sourceId) { LocalLibraryPreferences(sourceId, json) }
        val initialConfig = remember(sourceId) { preferences.getConfig() }
        val defaultComicsBookshelfName = stringResource(MR.strings.local_library_default_comics_bookshelf)
        val defaultBooksBookshelfName = stringResource(MR.strings.local_library_default_books_bookshelf)
        var config by remember(sourceId) { mutableStateOf(initialConfig) }
        var savedConfig by remember(sourceId) { mutableStateOf(initialConfig) }
        var setupStep by rememberSaveable(sourceId) { mutableStateOf(LocalLibrarySetupStep.NAME) }
        var selectedComicsMode by rememberSaveable(sourceId) {
            mutableStateOf(
                initialConfig.bookshelvesFor(LocalLibraryContentType.COMICS).firstOrNull()
                    ?.organizationMode
                    ?.takeIf { initialConfig.roots.isNotEmpty() || initialConfig.setupCompleted },
            )
        }
        var selectedBooksMode by rememberSaveable(sourceId) {
            mutableStateOf(
                initialConfig.bookshelvesFor(LocalLibraryContentType.BOOKS).firstOrNull()
                    ?.organizationMode
                    ?.takeIf { initialConfig.roots.isNotEmpty() || initialConfig.setupCompleted },
            )
        }
        var includeComicsLibrary by rememberSaveable(sourceId) {
            mutableStateOf(LocalLibraryContentType.COMICS in initialConfig.enabledContentTypes)
        }
        var includeBooksLibrary by rememberSaveable(sourceId) {
            mutableStateOf(LocalLibraryContentType.BOOKS in initialConfig.enabledContentTypes)
        }
        var selectedComicsBookshelfName by rememberSaveable(sourceId) {
            mutableStateOf(
                initialConfig.bookshelvesFor(LocalLibraryContentType.COMICS).firstOrNull()
                    ?.name
                    ?.takeIf(String::isNotBlank)
                    ?: defaultComicsBookshelfName,
            )
        }
        var selectedBooksBookshelfName by rememberSaveable(sourceId) {
            mutableStateOf(
                initialConfig.bookshelvesFor(LocalLibraryContentType.BOOKS).firstOrNull()
                    ?.name
                    ?.takeIf(String::isNotBlank)
                    ?: defaultBooksBookshelfName,
            )
        }
        var connectionName by rememberSaveable(sourceId) { mutableStateOf(profileName) }
        var savedConnectionName by remember(sourceId) { mutableStateOf(profileName) }
        var selectedMetadataStorage by rememberSaveable(sourceId) { mutableStateOf(config.metadataStorage) }
        var pendingDirectory by remember { mutableStateOf<PendingLocalDirectory?>(null) }
        var rootToEdit by remember { mutableStateOf<LocalLibraryRootConfig?>(null) }
        var rootToDelete by remember { mutableStateOf<LocalLibraryRootConfig?>(null) }
        var showUnsavedDialog by rememberSaveable { mutableStateOf(false) }
        var isSaving by rememberSaveable { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        fun updateDraft(next: LocalLibraryConfig) {
            config = next.copy(libraryId = next.libraryId.ifBlank { UUID.randomUUID().toString() })
        }

        fun commit(nextConfig: LocalLibraryConfig = config): Boolean {
            val trimmedName = connectionName.trim()
            if (trimmedName.isBlank()) return false
            return runCatching {
                val profile = requireNotNull(
                    connectionProfileManager.profiles().firstOrNull { it.id == sourceId },
                )
                connectionProfileManager.update(profile.copy(name = trimmedName))
                savedConfig.roots
                    .filter { savedRoot -> nextConfig.roots.none { it.id == savedRoot.id } }
                    .forEach { preferences.removeRoot(it.id) }
                preferences.setConfig(nextConfig)
                connectionName = trimmedName
                savedConnectionName = trimmedName
                savedConfig = preferences.getConfig()
                config = savedConfig
            }.isSuccess
        }

        LaunchedEffect(preferences) {
            preferences.configChanges().collect { external ->
                if (external == savedConfig) return@collect
                val previousSaved = savedConfig
                val latestRootsById = external.roots.associateBy(LocalLibraryRootConfig::id)
                val previousRootsById = previousSaved.roots.associateBy(LocalLibraryRootConfig::id)
                config = config.copy(
                    bookshelves = external.bookshelves,
                    roots = config.roots.map { draftRoot ->
                        val latestRoot = latestRootsById[draftRoot.id]
                        val previousRoot = previousRootsById[draftRoot.id]
                        if (
                            latestRoot != null &&
                            previousRoot != null &&
                            latestRoot.bookshelfId != previousRoot.bookshelfId
                        ) {
                            draftRoot.copy(bookshelfId = latestRoot.bookshelfId)
                        } else {
                            draftRoot
                        }
                    },
                )
                savedConfig = external
            }
        }

        val hasUnsavedChanges = config != savedConfig ||
            connectionName.trim() != savedConnectionName ||
            selectedMetadataStorage != config.metadataStorage

        fun finishAfterSave() {
            if (completeOnboardingOnSave) {
                basePreferences.shownOnboardingFlow.set(true)
                navigator.popUntilRoot()
            } else {
                navigator.pop()
            }
        }

        fun saveAndClose(nextConfig: LocalLibraryConfig = config) {
            if (isSaving) return
            isSaving = true
            if (commit(nextConfig)) {
                finishAfterSave()
            } else {
                context.toast(MR.strings.local_library_save_failed)
            }
            isSaving = false
        }

        fun discardAndPop() {
            scope.launch {
                if (isNew) {
                    connectionProfileManager.remove(sourceId)
                }
                navigator.pop()
            }
        }

        fun onCancel() {
            if (hasUnsavedChanges) {
                showUnsavedDialog = true
            } else {
                discardAndPop()
            }
        }

        BackHandler(onBack = ::onCancel)

        val chooseExistingDirectory = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            if (!persistDirectoryPermission(context, uri, requireWrite = false)) {
                context.toast(MR.strings.local_library_permission_failed)
                return@rememberLauncherForActivityResult
            }
            if (config.roots.any { it.treeUri == uri.toString() }) {
                context.toast(MR.strings.local_library_directory_already_added)
                return@rememberLauncherForActivityResult
            }
            pendingDirectory = PendingLocalDirectory(
                treeUri = uri.toString(),
                displayPath = directoryDisplayPath(context, uri),
            )
        }

        val chooseManagedBaseDirectory = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            if (!persistDirectoryPermission(context, uri, requireWrite = true)) {
                context.toast(MR.strings.local_library_write_permission_failed)
                return@rememberLauncherForActivityResult
            }
            if (config.roots.any { it.treeUri == uri.toString() }) {
                context.toast(MR.strings.local_library_directory_already_added)
                return@rememberLauncherForActivityResult
            }
            val displayPath = directoryDisplayPath(context, uri)
            val libraryId = config.libraryId.ifBlank { UUID.randomUUID().toString() }
            val managedLayout = createManagedLayout(
                context = context,
                uri = uri,
                displayPath = displayPath,
                libraryId = libraryId,
                contentTypes = config.enabledContentTypes,
                json = json,
            )
            if (managedLayout == null) {
                context.toast(MR.strings.local_library_create_failed)
                return@rememberLauncherForActivityResult
            }
            updateDraft(
                config.copy(
                    roots = config.roots + managedLayout.roots,
                    managedBaseTreeUri = uri.toString(),
                    managedBaseDisplayPath = displayPath,
                    libraryId = managedLayout.libraryId,
                ),
            )
            context.toast(MR.strings.local_library_managed_created)
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = titleOverride ?: connectionName,
                    navigateUp = ::onCancel,
                    scrollBehavior = it,
                )
            },
            bottomBar = {
                if (config.setupCompleted) {
                    Button(
                        enabled = !isSaving,
                        onClick = { saveAndClose() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(
                            text = stringResource(MR.strings.action_save),
                            modifier = Modifier.padding(start = if (isSaving) 8.dp else 0.dp),
                        )
                    }
                }
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                if (!config.setupCompleted) {
                    item {
                        SetupProgress(step = setupStep)
                    }
                    when (setupStep) {
                        LocalLibrarySetupStep.NAME -> {
                            item {
                                PreferenceGroupHeader(
                                    title = stringResource(MR.strings.local_library_setup_name_title),
                                )
                            }
                            item {
                                SetupNotice(
                                    text = stringResource(MR.strings.local_library_setup_name_summary),
                                )
                            }
                            item {
                                OutlinedTextField(
                                    value = connectionName,
                                    onValueChange = { connectionName = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    label = { Text(text = stringResource(MR.strings.name)) },
                                    supportingText = {
                                        if (connectionName.isBlank()) {
                                            Text(text = stringResource(MR.strings.information_required_plain))
                                        }
                                    },
                                    singleLine = true,
                                )
                            }
                            item {
                                SetupNavigation(
                                    onNext = {
                                        connectionName = connectionName.trim()
                                        setupStep = LocalLibrarySetupStep.ORGANIZATION
                                    },
                                    nextEnabled = connectionName.isNotBlank(),
                                )
                            }
                        }
                        LocalLibrarySetupStep.ORGANIZATION -> {
                            item {
                                PreferenceGroupHeader(
                                    title = stringResource(MR.strings.local_library_organization_title),
                                )
                            }
                            item {
                                OrganizationModeExplanation()
                            }
                            item {
                                DefaultBookshelfSectionHeader(
                                    title = stringResource(MR.strings.local_library_default_comics_bookshelf),
                                    checked = includeComicsLibrary,
                                    onCheckedChange = { includeComicsLibrary = it },
                                )
                            }
                            item {
                                DefaultBookshelfNameField(
                                    value = selectedComicsBookshelfName,
                                    onValueChange = { selectedComicsBookshelfName = it },
                                    enabled = includeComicsLibrary,
                                )
                            }
                            items(LocalLibraryOrganizationMode.entries) { mode ->
                                OrganizationModeOption(
                                    mode = mode,
                                    selected = selectedComicsMode == mode,
                                    enabled = includeComicsLibrary && config.roots.isEmpty(),
                                    onSelect = { selectedComicsMode = mode },
                                )
                            }
                            item {
                                DefaultBookshelfSectionHeader(
                                    title = stringResource(MR.strings.local_library_default_books_bookshelf),
                                    checked = includeBooksLibrary,
                                    onCheckedChange = { includeBooksLibrary = it },
                                )
                            }
                            item {
                                DefaultBookshelfNameField(
                                    value = selectedBooksBookshelfName,
                                    onValueChange = { selectedBooksBookshelfName = it },
                                    enabled = includeBooksLibrary,
                                )
                            }
                            items(LocalLibraryOrganizationMode.entries) { mode ->
                                OrganizationModeOption(
                                    mode = mode,
                                    selected = selectedBooksMode == mode,
                                    enabled = includeBooksLibrary && config.roots.isEmpty(),
                                    onSelect = { selectedBooksMode = mode },
                                )
                            }
                            item {
                                SetupNavigation(
                                    onPrevious = { setupStep = LocalLibrarySetupStep.NAME },
                                    onNext = {
                                        val enabledTypes = buildSet {
                                            if (includeComicsLibrary) add(LocalLibraryContentType.COMICS)
                                            if (includeBooksLibrary) add(LocalLibraryContentType.BOOKS)
                                        }
                                        var nextConfig = config.withEnabledContentTypes(enabledTypes)
                                        if (includeComicsLibrary) {
                                            nextConfig = nextConfig.withDefaultBookshelfDetails(
                                                LocalLibraryContentType.COMICS,
                                                selectedComicsBookshelfName.trim(),
                                                checkNotNull(selectedComicsMode),
                                            )
                                        }
                                        if (includeBooksLibrary) {
                                            nextConfig = nextConfig.withDefaultBookshelfDetails(
                                                LocalLibraryContentType.BOOKS,
                                                selectedBooksBookshelfName.trim(),
                                                checkNotNull(selectedBooksMode),
                                            )
                                        }
                                        updateDraft(nextConfig)
                                        setupStep = LocalLibrarySetupStep.DIRECTORIES
                                    },
                                    nextEnabled = (includeComicsLibrary || includeBooksLibrary) &&
                                        (
                                            !includeComicsLibrary ||
                                                (
                                                    selectedComicsMode != null &&
                                                        selectedComicsBookshelfName.isNotBlank()
                                                    )
                                            ) &&
                                        (
                                            !includeBooksLibrary ||
                                                (
                                                    selectedBooksMode != null &&
                                                        selectedBooksBookshelfName.isNotBlank()
                                                    )
                                            ),
                                )
                            }
                        }
                        LocalLibrarySetupStep.DIRECTORIES -> {
                            val missingDirectoryTypes = config.missingEnabledDirectoryTypes()
                            item {
                                PreferenceGroupHeader(
                                    title = stringResource(MR.strings.local_library_setup_directories_title),
                                )
                            }
                            item {
                                SetupNotice(
                                    text = stringResource(MR.strings.local_library_setup_directories_summary),
                                )
                            }
                            item {
                                DirectorySetupActions(
                                    showCreateManaged = config.roots.isEmpty(),
                                    onAddExisting = { chooseExistingDirectory.launch(null) },
                                    onCreateManaged = { chooseManagedBaseDirectory.launch(null) },
                                )
                            }
                            item {
                                PreferenceGroupHeader(
                                    title = stringResource(MR.strings.local_library_setup_directory_preview),
                                )
                            }
                            localLibraryRootItems(
                                config = config,
                                preferences = preferences,
                                context = context,
                                onEdit = { rootToEdit = it },
                            )
                            if (missingDirectoryTypes.isNotEmpty()) {
                                item {
                                    SetupNotice(
                                        text = stringResource(
                                            MR.strings.local_library_setup_directories_missing,
                                            missingDirectoryTypesLabel(missingDirectoryTypes),
                                        ),
                                        warning = true,
                                    )
                                }
                            }
                            item {
                                SetupNavigation(
                                    onPrevious = { setupStep = LocalLibrarySetupStep.ORGANIZATION },
                                    onNext = { setupStep = LocalLibrarySetupStep.METADATA },
                                    nextEnabled = missingDirectoryTypes.isEmpty(),
                                )
                            }
                        }
                        LocalLibrarySetupStep.METADATA -> {
                            item {
                                PreferenceGroupHeader(
                                    title = stringResource(MR.strings.local_library_setup_metadata_title),
                                )
                            }
                            item {
                                SetupNotice(
                                    text = stringResource(MR.strings.local_library_setup_metadata_summary),
                                )
                            }
                            items(LocalMetadataStorage.entries) { storage ->
                                MetadataStorageOption(
                                    storage = storage,
                                    selected = selectedMetadataStorage == storage,
                                    onSelect = { selectedMetadataStorage = storage },
                                )
                            }
                            item {
                                SetupNotice(
                                    text = stringResource(MR.strings.local_library_setup_metadata_locked),
                                    warning = true,
                                )
                            }
                            item {
                                SetupNavigation(
                                    onPrevious = { setupStep = LocalLibrarySetupStep.DIRECTORIES },
                                    onNext = {
                                        val completedConfig = config.copy(
                                            metadataStorage = selectedMetadataStorage,
                                            setupCompleted = true,
                                        )
                                        if (commit(completedConfig)) {
                                            context.toast(MR.strings.local_library_setup_complete_message)
                                            finishAfterSave()
                                        } else {
                                            context.toast(MR.strings.local_library_save_failed)
                                        }
                                    },
                                    nextLabel = stringResource(MR.strings.local_library_setup_finish),
                                )
                            }
                        }
                    }
                } else {
                    item {
                        PreferenceGroupHeader(title = stringResource(MR.strings.pref_category_general))
                    }
                    item {
                        EditTextPreferenceWidget(
                            title = stringResource(MR.strings.name),
                            subtitle = stringResource(MR.strings.local_library_connection_name_summary),
                            icon = null,
                            value = connectionName,
                            onConfirm = { name ->
                                connectionName = name.trim()
                                true
                            },
                        )
                    }
                    item {
                        PreferenceGroupHeader(title = stringResource(MR.strings.local_library_directories))
                    }
                    localLibraryRootItems(
                        config = config,
                        preferences = preferences,
                        context = context,
                        onEdit = { rootToEdit = it },
                    )
                    item {
                        DirectorySetupActions(
                            showCreateManaged = config.roots.isEmpty(),
                            onAddExisting = { chooseExistingDirectory.launch(null) },
                            onCreateManaged = { chooseManagedBaseDirectory.launch(null) },
                        )
                    }
                    item {
                        PreferenceGroupHeader(title = stringResource(MR.strings.local_library_bookshelves))
                    }
                    item {
                        TextPreferenceWidget(
                            title = stringResource(MR.strings.local_library_manage_bookshelves),
                            subtitle = stringResource(MR.strings.local_library_manage_bookshelves_summary),
                            onPreferenceClick = { navigator.push(LocalBookshelfManagementScreen(sourceId)) },
                        )
                    }
                    item {
                        PreferenceGroupHeader(title = stringResource(MR.strings.local_library_metadata_group))
                    }
                    item {
                        TextPreferenceWidget(
                            title = stringResource(MR.strings.local_library_metadata_storage),
                            subtitle = metadataLabel(context, config.metadataStorage),
                            enabled = false,
                        )
                    }
                }
            }
        }

        pendingDirectory?.let { pending ->
            val initialContentType = listOf(
                LocalLibraryContentType.COMICS,
                LocalLibraryContentType.BOOKS,
            ).first { config.bookshelvesFor(it).isNotEmpty() }
            DirectoryConfigurationDialog(
                title = stringResource(MR.strings.local_library_add_existing),
                displayPath = pending.displayPath,
                config = config,
                initialContentType = initialContentType,
                initialBookshelfId = config.defaultBookshelfId(initialContentType),
                onDismissRequest = { pendingDirectory = null },
                onConfirm = { contentType, bookshelfId ->
                    updateDraft(
                        config.copy(
                            roots = config.roots + LocalLibraryRootConfig(
                                id = UUID.randomUUID().toString(),
                                treeUri = pending.treeUri,
                                displayPath = pending.displayPath,
                                contentType = contentType,
                                bookshelfId = bookshelfId,
                            ),
                        ),
                    )
                    pendingDirectory = null
                    context.toast(MR.strings.local_library_directory_added)
                },
            )
        }

        rootToEdit?.let { root ->
            DirectoryConfigurationDialog(
                title = stringResource(MR.strings.local_library_edit_directory),
                displayPath = root.fullDisplayPath(context),
                config = config,
                initialContentType = root.contentType,
                initialBookshelfId = root.bookshelfId,
                lockedOrganizationMode = config.organizationMode(root),
                onDismissRequest = { rootToEdit = null },
                onConfirm = { contentType, bookshelfId ->
                    updateDraft(
                        config.copy(
                            roots = config.roots.map {
                                if (it.id == root.id) {
                                    it.copy(contentType = contentType, bookshelfId = bookshelfId)
                                } else {
                                    it
                                }
                            },
                        ),
                    )
                    rootToEdit = null
                },
                onDelete = {
                    rootToEdit = null
                    rootToDelete = root
                },
            )
        }

        rootToDelete?.let { root ->
            AlertDialog(
                onDismissRequest = { rootToDelete = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            updateDraft(config.withoutRoot(root.id))
                            rootToDelete = null
                        },
                    ) {
                        Text(
                            text = stringResource(MR.strings.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { rootToDelete = null }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
                title = { Text(text = stringResource(MR.strings.local_library_remove_directory)) },
                text = { Text(text = stringResource(MR.strings.local_library_remove_directory_summary)) },
            )
        }

        if (showUnsavedDialog) {
            AlertDialog(
                onDismissRequest = { showUnsavedDialog = false },
                title = { Text(text = stringResource(MR.strings.local_library_unsaved_changes_title)) },
                text = { Text(text = stringResource(MR.strings.local_library_unsaved_changes_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showUnsavedDialog = false
                            discardAndPop()
                        },
                    ) {
                        Text(text = stringResource(MR.strings.local_library_action_discard))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.localLibraryRootItems(
    config: LocalLibraryConfig,
    preferences: LocalLibraryPreferences,
    context: Context,
    onEdit: (LocalLibraryRootConfig) -> Unit,
) {
    if (config.roots.isEmpty()) {
        item {
            TextPreferenceWidget(
                title = stringResource(MR.strings.local_library_no_directories),
                subtitle = stringResource(MR.strings.local_library_no_directories_summary),
                icon = Icons.Outlined.FolderOpen,
            )
        }
    } else {
        items(
            items = config.roots,
            key = LocalLibraryRootConfig::id,
        ) { root ->
            LocalLibraryRootRow(
                root = root,
                isAvailable = preferences.resolveRoot(context, root) != null,
                bookshelfName = config.bookshelfName(root),
                organizationMode = config.organizationMode(root),
                onEdit = { onEdit(root) },
            )
        }
    }
}

@Composable
private fun SetupProgress(step: LocalLibrarySetupStep) {
    val stepNumber = when (step) {
        LocalLibrarySetupStep.NAME -> 1
        LocalLibrarySetupStep.ORGANIZATION -> 2
        LocalLibrarySetupStep.DIRECTORIES -> 3
        LocalLibrarySetupStep.METADATA -> 4
    }
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(MR.strings.local_library_setup_progress, stepNumber, 4),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        LinearProgressIndicator(
            progress = { stepNumber / 4f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SetupNavigation(
    onNext: () -> Unit,
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
            TextButton(onClick = onPrevious) {
                Text(text = stringResource(MR.strings.local_library_setup_previous))
            }
        } else {
            Text(text = "")
        }
        TextButton(
            enabled = nextEnabled,
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
private fun DefaultBookshelfSectionHeader(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        headlineContent = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        leadingContent = {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}

@Composable
private fun DefaultBookshelfNameField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        label = { Text(text = stringResource(MR.strings.local_library_default_bookshelf_name)) },
        supportingText = {
            if (value.isBlank()) {
                Text(text = stringResource(MR.strings.information_required_plain))
            }
        },
        singleLine = true,
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
private fun OrganizationModeOption(
    mode: LocalLibraryOrganizationMode,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, onClick = onSelect),
        headlineContent = { Text(text = organizationModeLabel(mode)) },
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = if (enabled) onSelect else null,
                enabled = enabled,
            )
        },
    )
}

@Composable
private fun OrganizationModeExplanation() {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable { expanded = !expanded },
            headlineContent = {
                Text(text = stringResource(MR.strings.local_library_organization_guide_title))
            },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            trailingContent = {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
            },
        )
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(
                    start = 56.dp,
                    end = 24.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OrganizationModeExplanationText(
                    title = stringResource(MR.strings.local_library_mode_series),
                    text = stringResource(MR.strings.local_library_mode_series_guide),
                )
                OrganizationModeExplanationText(
                    title = stringResource(MR.strings.local_library_mode_individual),
                    text = stringResource(MR.strings.local_library_mode_individual_guide),
                )
                OrganizationModeExplanationText(
                    title = stringResource(MR.strings.local_library_organization_note_title),
                    text = stringResource(MR.strings.local_library_organization_locked),
                )
            }
        }
    }
}

@Composable
private fun OrganizationModeExplanationText(
    title: String,
    text: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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

@Composable
private fun LocalLibraryRootRow(
    root: LocalLibraryRootConfig,
    isAvailable: Boolean,
    bookshelfName: String,
    organizationMode: LocalLibraryOrganizationMode,
    onEdit: () -> Unit,
) {
    val typeAndShelf = listOfNotNull(
        contentTypeLabel(root.contentType),
        bookshelfName.takeIf(String::isNotBlank),
        organizationModeLabel(organizationMode),
    ).joinToString(" · ")
    TextPreferenceWidget(
        title = root.displayPath.substringAfterLast('/').ifBlank { root.displayPath.ifBlank { root.treeUri } },
        subtitle = if (isAvailable) {
            typeAndShelf
        } else {
            "$typeAndShelf · ${stringResource(MR.strings.local_library_reauthorization_required)}"
        },
        icon = Icons.Outlined.FolderOpen,
        widget = {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(MR.strings.action_edit),
                )
            }
        },
        onPreferenceClick = onEdit,
    )
}

@Composable
private fun LocalLibraryConfig.bookshelfName(root: LocalLibraryRootConfig): String {
    return bookshelves.firstOrNull { it.id == root.bookshelfId && it.contentType == root.contentType }?.name
        ?: defaultBookshelfName(root.contentType)
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

@Composable
private fun DirectoryConfigurationDialog(
    title: String,
    displayPath: String,
    config: LocalLibraryConfig,
    initialContentType: LocalLibraryContentType,
    initialBookshelfId: String,
    lockedOrganizationMode: LocalLibraryOrganizationMode? = null,
    onDismissRequest: () -> Unit,
    onConfirm: (LocalLibraryContentType, String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var contentType by remember(initialContentType, displayPath) { mutableStateOf(initialContentType) }
    var bookshelfId by remember(initialBookshelfId, displayPath) { mutableStateOf(initialBookshelfId) }
    val allowedContentTypes = (
        if (initialContentType == LocalLibraryContentType.MIXED) {
            LocalLibraryContentType.entries
        } else {
            listOf(LocalLibraryContentType.COMICS, LocalLibraryContentType.BOOKS)
        }
        ).filter { type ->
        type == LocalLibraryContentType.MIXED ||
            config.bookshelvesFor(type).let { shelves ->
                shelves.isNotEmpty() &&
                    (lockedOrganizationMode == null || shelves.any { it.organizationMode == lockedOrganizationMode })
            }
    }
    val shelves = config.bookshelvesFor(contentType)
        .filter { lockedOrganizationMode == null || it.organizationMode == lockedOrganizationMode }
    val effectiveBookshelfId = bookshelfId.takeIf { id -> shelves.any { it.id == id } }
        ?: config.defaultBookshelfId(contentType)

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = { onConfirm(contentType, effectiveBookshelfId) }) {
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
        title = { Text(text = title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.local_library_directory_location),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = displayPath,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(MR.strings.local_library_choose_directory_type),
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                allowedContentTypes.forEach { type ->
                    DirectoryChoiceRow(
                        label = contentTypeLabel(type),
                        selected = contentType == type,
                        onClick = {
                            contentType = type
                            bookshelfId = config.defaultBookshelfId(type)
                        },
                    )
                }
                if (contentType != LocalLibraryContentType.MIXED) {
                    Text(
                        text = stringResource(MR.strings.local_library_choose_bookshelf),
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    shelves.forEach { shelf ->
                        DirectoryChoiceRow(
                            label = shelf.name.takeIf(String::isNotBlank) ?: defaultBookshelfName(contentType),
                            selected = effectiveBookshelfId == shelf.id,
                            onClick = { bookshelfId = shelf.id },
                        )
                    }
                    val selectedMode = shelves.firstOrNull { it.id == effectiveBookshelfId }?.organizationMode
                    if (selectedMode != null) {
                        Text(
                            text = stringResource(
                                MR.strings.local_library_organization_locked_summary,
                                organizationModeLabel(selectedMode),
                            ),
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun DirectoryChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Text(text = label, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun contentTypeLabel(type: LocalLibraryContentType): String {
    return stringResource(
        when (type) {
            LocalLibraryContentType.COMICS -> MR.strings.local_library_content_comics
            LocalLibraryContentType.BOOKS -> MR.strings.local_library_content_books
            LocalLibraryContentType.MIXED -> MR.strings.local_library_content_mixed
        },
    )
}

@Composable
private fun missingDirectoryTypesLabel(types: List<LocalLibraryContentType>): String {
    val comics = if (LocalLibraryContentType.COMICS in types) {
        contentTypeLabel(LocalLibraryContentType.COMICS)
    } else {
        null
    }
    val books = if (LocalLibraryContentType.BOOKS in types) {
        contentTypeLabel(LocalLibraryContentType.BOOKS)
    } else {
        null
    }
    return listOfNotNull(comics, books).joinToString(" / ")
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

private fun LocalLibraryRootConfig.fullDisplayPath(context: Context): String {
    val basePath = runCatching { directoryDisplayPath(context, Uri.parse(treeUri)) }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: displayPath.takeIf(String::isNotBlank)
        ?: treeUri
    val childPath = LocalLibraryLocator.normalize(relativePath)
    return if (childPath.isBlank() || basePath.endsWith("/$childPath")) {
        basePath
    } else {
        "${basePath.trimEnd('/')}/$childPath"
    }
}

private fun LocalLibraryConfig.missingEnabledDirectoryTypes(): List<LocalLibraryContentType> {
    return enabledContentTypes
        .filter { it == LocalLibraryContentType.COMICS || it == LocalLibraryContentType.BOOKS }
        .filterNot { requiredType ->
            roots.any { root ->
                root.contentType == requiredType || root.contentType == LocalLibraryContentType.MIXED
            }
        }
}

private fun createManagedLayout(
    context: Context,
    uri: Uri,
    displayPath: String,
    libraryId: String,
    contentTypes: Set<LocalLibraryContentType>,
    json: Json,
): ManagedLayoutResult? {
    if (contentTypes.none { it == LocalLibraryContentType.COMICS || it == LocalLibraryContentType.BOOKS }) {
        return null
    }
    val root = UniFile.fromUri(context, uri)?.takeIf { it.isDirectory } ?: return null
    return runCatching {
        val metadataRoot = root.findFile(".koharia") ?: checkNotNull(root.createDirectory(".koharia"))
        metadataRoot.findFile("metadata") ?: checkNotNull(metadataRoot.createDirectory("metadata"))
        if (LocalLibraryContentType.COMICS in contentTypes) {
            root.findFile("Comics") ?: checkNotNull(root.createDirectory("Comics"))
        }
        if (LocalLibraryContentType.BOOKS in contentTypes) {
            root.findFile("Books") ?: checkNotNull(root.createDirectory("Books"))
        }
        val existingManifest = metadataRoot.findFile("library.json")
        val effectiveLibraryId = if (existingManifest != null) {
            existingManifest.openInputStream().use { input ->
                json.decodeFromString<LocalLibraryManifest>(
                    input.readBytes().toString(StandardCharsets.UTF_8),
                ).libraryId
            }
        } else {
            val manifest = LocalLibraryManifest(
                libraryId = libraryId,
                contentType = contentTypes.singleOrNull() ?: LocalLibraryContentType.MIXED,
            )
            val manifestFile = checkNotNull(metadataRoot.createFile("library.json"))
            manifestFile.openOutputStream().use { output ->
                output.write(
                    json.encodeToString(LocalLibraryManifest.serializer(), manifest)
                        .toByteArray(StandardCharsets.UTF_8),
                )
            }
            libraryId
        }
        ManagedLayoutResult(
            libraryId = effectiveLibraryId,
            roots = buildList {
                if (LocalLibraryContentType.COMICS in contentTypes) {
                    add(
                        LocalLibraryRootConfig(
                            id = UUID.randomUUID().toString(),
                            treeUri = uri.toString(),
                            displayPath = "$displayPath/Comics",
                            contentType = LocalLibraryContentType.COMICS,
                            relativePath = "Comics",
                            managed = true,
                        ),
                    )
                }
                if (LocalLibraryContentType.BOOKS in contentTypes) {
                    add(
                        LocalLibraryRootConfig(
                            id = UUID.randomUUID().toString(),
                            treeUri = uri.toString(),
                            displayPath = "$displayPath/Books",
                            contentType = LocalLibraryContentType.BOOKS,
                            relativePath = "Books",
                            managed = true,
                        ),
                    )
                }
            },
        )
    }.getOrNull()
}

private enum class LocalLibrarySetupStep {
    NAME,
    ORGANIZATION,
    DIRECTORIES,
    METADATA,
}

private fun LocalLibraryConfig.withEnabledContentTypes(
    contentTypes: Set<LocalLibraryContentType>,
): LocalLibraryConfig {
    require(contentTypes.isNotEmpty()) { "At least one local library content type is required" }
    return copy(
        enabledContentTypes = contentTypes,
        bookshelves = bookshelves.filter { it.contentType in contentTypes },
    )
}

private fun LocalLibraryConfig.withDefaultBookshelfDetails(
    contentType: LocalLibraryContentType,
    name: String,
    mode: LocalLibraryOrganizationMode,
): LocalLibraryConfig {
    val id = this.defaultBookshelfId(contentType)
    require(id.isNotBlank()) { "Mixed libraries do not have a default bookshelf" }
    val current = bookshelf(id)
        ?: LocalBookshelf(id = id, name = name, contentType = contentType)
    return copy(
        bookshelves = LocalLibraryContentType.entries
            .filterNot { it == LocalLibraryContentType.MIXED }
            .flatMap { type ->
                bookshelvesFor(type).map { shelf ->
                    if (shelf.id == id) {
                        current.copy(name = name, organizationMode = mode)
                    } else {
                        shelf
                    }
                }
            },
    )
}

private data class ManagedLayoutResult(
    val libraryId: String,
    val roots: List<LocalLibraryRootConfig>,
)

private data class PendingLocalDirectory(
    val treeUri: String,
    val displayPath: String,
)

private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"
