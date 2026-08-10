package koharia.connection.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.ListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import koharia.connection.ConnectionConfigManager
import koharia.connection.ConnectionConfigMode
import koharia.connection.ConnectionConfigModeInterceptor
import koharia.connection.ConnectionConfigModeWarning
import koharia.connection.ConnectionManagementAdapter
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.ConnectionProvider
import koharia.connection.ConnectionRegistry
import koharia.connection.LibraryConnectionProfile
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs
import kotlin.math.roundToInt

class LibraryConnectionProfilesScreen(
    private val openAddDialog: Boolean = false,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val connectionPreferences = remember { Injekt.get<ConnectionPreferences>() }
        val connectionProfileManager = remember { Injekt.get<ConnectionProfileManager>() }
        val connectionRegistry = remember { Injekt.get<ConnectionRegistry>() }
        val localConfigManager = remember { Injekt.get<ConnectionConfigManager>() }
        val availableProviders = remember(connectionRegistry) { connectionRegistry.availableProviders() }
        val managementAdapters = remember(availableProviders) {
            availableProviders.mapNotNull { provider ->
                (provider as? ConnectionManagementAdapter)?.let { provider.id to it }
            }
        }
        val configModeInterceptors = remember(availableProviders) {
            availableProviders.filterIsInstance<ConnectionConfigModeInterceptor>()
        }
        val profiles by connectionPreferences.profilesChanges()
            .collectAsState(initial = connectionPreferences.getProfiles())
        val activeConnectionId by connectionPreferences.activeConnectionId.collectAsState()
        val localConfigMode by connectionPreferences.configMode.collectAsState()
        val scope = rememberCoroutineScope()

        var selectedProviderId by rememberSaveable {
            mutableStateOf(availableProviders.singleOrNull()?.id)
        }
        var showProviderDialog by rememberSaveable {
            mutableStateOf(openAddDialog && availableProviders.size > 1)
        }
        var showAddDialog by rememberSaveable {
            mutableStateOf(openAddDialog && availableProviders.size == 1)
        }
        var showModeHelpDialog by rememberSaveable { mutableStateOf(false) }
        var pendingModeChange by remember { mutableStateOf<PendingModeChange?>(null) }
        var pendingConnectionName by rememberSaveable { mutableStateOf<String?>(null) }
        var profileToDelete by remember { mutableStateOf<LibraryConnectionProfile?>(null) }
        val addConnectionTitle = stringResource(MR.strings.connection_settings_add_title)
        val editConnectionTitle = stringResource(MR.strings.connection_settings_edit_title)

        fun startAddConnection() {
            when (availableProviders.size) {
                0 -> Unit
                1 -> {
                    selectedProviderId = availableProviders.single().id
                    showAddDialog = true
                }
                else -> showProviderDialog = true
            }
        }

        fun createConnection(name: String) {
            val providerId = selectedProviderId ?: return
            val newProfile = connectionProfileManager.add(providerId, name)
            connectionPreferences.activeConnectionId.set(newProfile.id)
            connectionRegistry.provider(providerId)
                ?.createSettingsScreen(
                    profile = newProfile,
                    titleOverride = addConnectionTitle,
                    isNew = true,
                )
                ?.let(navigator::push)
        }

        fun applyConfigModeChange(mode: ConnectionConfigMode) {
            configModeInterceptors.forEach { it.prepareConfigModeChange(mode) }
            localConfigManager.setConnectionConfigMode(mode)
        }

        fun requestConfigModeChange(
            mode: ConnectionConfigMode,
            connectionNameAfterChange: String? = null,
        ) {
            val warnings = configModeInterceptors.mapNotNull { it.warningForConfigMode(mode) }
            if (warnings.isEmpty()) {
                applyConfigModeChange(mode)
                connectionNameAfterChange?.let(::createConnection)
            } else {
                pendingModeChange = PendingModeChange(mode, warnings, connectionNameAfterChange)
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.pref_connection_management),
                    navigateUp = navigator::pop,
                    actions = {
                        IconButton(onClick = { showModeHelpDialog = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                                contentDescription = stringResource(MR.strings.connection_management_mode_help_title),
                            )
                        }
                    },
                    scrollBehavior = it,
                )
            },
            floatingActionButton = {
                SmallExtendedFloatingActionButton(
                    text = { Text(text = stringResource(MR.strings.action_add)) },
                    icon = { Icon(imageVector = Icons.Outlined.Add, contentDescription = null) },
                    onClick = ::startAddConnection,
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                item {
                    PreferenceGroupHeader(title = stringResource(MR.strings.connection_management_modes_group))
                }
                item {
                    ListPreferenceWidget(
                        value = localConfigMode,
                        title = stringResource(MR.strings.connection_config_mode),
                        subtitle = stringResource(
                            if (localConfigMode == ConnectionConfigMode.Shared) {
                                MR.strings.connection_config_mode_shared
                            } else {
                                MR.strings.connection_config_mode_separate
                            },
                        ),
                        icon = null,
                        entries = mapOf(
                            ConnectionConfigMode.Shared to stringResource(MR.strings.connection_config_mode_shared),
                            ConnectionConfigMode.Separate to stringResource(
                                MR.strings.connection_config_mode_separate,
                            ),
                        ).toImmutableMap(),
                        onValueChange = ::requestConfigModeChange,
                    )
                }
                managementAdapters.forEach { (providerId, adapter) ->
                    item(key = "management:$providerId") {
                        with(adapter) {
                            ConnectionManagementPreferences()
                        }
                    }
                }

                if (profiles.isEmpty()) {
                    item {
                        PreferenceGroupHeader(
                            title = stringResource(MR.strings.connection_management_connections_group),
                        )
                    }
                    item {
                        EmptyConnectionState(
                            onAddClick = ::startAddConnection,
                        )
                    }
                } else {
                    item {
                        PreferenceGroupHeader(
                            title = stringResource(MR.strings.connection_management_connections_group),
                        )
                    }
                    items(
                        items = profiles,
                        key = LibraryConnectionProfile::id,
                    ) { profile ->
                        val provider = connectionRegistry.provider(profile.providerId)
                        ConnectionRow(
                            profile = profile,
                            providerName = provider?.displayName ?: profile.providerId,
                            isAvailable = provider != null,
                            isActive = activeConnectionId == profile.id,
                            onSelect = { connectionPreferences.activeConnectionId.set(profile.id) },
                            onEdit = {
                                provider?.createSettingsScreen(
                                    profile = profile,
                                    titleOverride = editConnectionTitle,
                                )?.let(navigator::push)
                            },
                            onDelete = { profileToDelete = profile },
                        )
                    }
                }
            }
        }

        if (showModeHelpDialog) {
            ModeHelpDialog(
                managementAdapters = managementAdapters.map { it.second },
                onDismissRequest = { showModeHelpDialog = false },
            )
        }

        pendingModeChange?.let { pending ->
            ConfigModeWarningDialog(
                warnings = pending.warnings,
                onDismissRequest = { pendingModeChange = null },
                onConfirm = {
                    applyConfigModeChange(pending.mode)
                    pending.connectionNameAfterChange?.let(::createConnection)
                    pendingModeChange = null
                },
            )
        }

        if (showProviderDialog) {
            ProviderSelectionDialog(
                providers = availableProviders,
                onDismissRequest = { showProviderDialog = false },
                onSelect = { providerId ->
                    selectedProviderId = providerId
                    showProviderDialog = false
                    showAddDialog = true
                },
            )
        }

        if (showAddDialog) {
            val provider = selectedProviderId?.let(connectionRegistry::provider)
            AddConnectionDialog(
                directoryNameFor = provider?.let { it::directoryNameFor } ?: { it.trim() },
                isNameAvailable = provider?.let { it::isConnectionNameAvailable } ?: { false },
                onDismissRequest = {
                    showAddDialog = false
                    pendingConnectionName = null
                },
                onAddConnection = { name ->
                    showAddDialog = false
                    if (profiles.size == 1) {
                        pendingConnectionName = name
                    } else {
                        createConnection(name)
                    }
                },
            )
        }

        pendingConnectionName?.let { connectionName ->
            ConnectionConfigModeSelectionDialog(
                selected = localConfigMode,
                onDismissRequest = { pendingConnectionName = null },
                onConfirm = { mode ->
                    pendingConnectionName = null
                    requestConfigModeChange(mode, connectionName)
                },
            )
        }

        profileToDelete?.let { profile ->
            DeleteConnectionDialog(
                connectionName = profile.name,
                onDismissRequest = { profileToDelete = null },
                onDelete = {
                    scope.launch {
                        val result = connectionProfileManager.remove(profile.id)
                        if (result.isSuccess) {
                            profileToDelete = null
                        } else {
                            context.toast(MR.strings.connection_delete_failed)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ProviderSelectionDialog(
    providers: List<ConnectionProvider>,
    onDismissRequest: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.connection_provider))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                providers.forEach { provider ->
                    TextButton(
                        onClick = { onSelect(provider.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = provider.displayName)
                    }
                }
            }
        },
    )
}

@Composable
private fun ModeHelpDialog(
    managementAdapters: List<ConnectionManagementAdapter>,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.connection_management_mode_help_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = stringResource(MR.strings.connection_config_mode))
                    Text(text = stringResource(MR.strings.connection_config_mode_shared_explanation))
                    Text(text = stringResource(MR.strings.connection_config_mode_separate_explanation))
                }
                managementAdapters.forEach { adapter ->
                    with(adapter) {
                        ConnectionManagementHelpContent()
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = stringResource(MR.strings.delete_connection))
                    Text(text = stringResource(MR.strings.connection_management_delete_hint))
                }
            }
        },
    )
}

@Composable
private fun ConfigModeWarningDialog(
    warnings: List<ConnectionConfigModeWarning>,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(
                text = stringResource(
                    warnings.singleOrNull()?.title ?: MR.strings.connection_config_mode_dialog_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                warnings.forEach { warning ->
                    if (warnings.size > 1) {
                        Text(text = stringResource(warning.title))
                    }
                    Text(text = stringResource(warning.message))
                }
            }
        },
    )
}

private data class PendingModeChange(
    val mode: ConnectionConfigMode,
    val warnings: List<ConnectionConfigModeWarning>,
    val connectionNameAfterChange: String?,
)

@Composable
private fun ConnectionRow(
    profile: LibraryConnectionProfile,
    providerName: String,
    isAvailable: Boolean,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val revealDistancePx = with(density) { CONNECTION_ROW_REVEAL_DISTANCE.toPx() }
    val revealedOffsetPx = if (layoutDirection == LayoutDirection.Ltr) -revealDistancePx else revealDistancePx
    var rowOffsetPx by remember(profile.id) { mutableFloatStateOf(0f) }
    var settleJob by remember(profile.id) { mutableStateOf<Job?>(null) }

    fun settleRow(revealed: Boolean) {
        val targetOffset = if (revealed) revealedOffsetPx else 0f
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(
                initialValue = rowOffsetPx,
                targetValue = targetOffset,
                animationSpec = tween(durationMillis = CONNECTION_ROW_SETTLE_DURATION_MILLIS),
            ) { value, _ ->
                rowOffsetPx = value
            }
        }
    }

    val isDeleteRevealed = abs(rowOffsetPx) > 1f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds(),
    ) {
        Box(
            modifier = Modifier.matchParentSize(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            FilledTonalIconButton(
                onClick = {
                    settleRow(revealed = false)
                    onDelete()
                },
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(48.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(MR.strings.action_delete),
                )
            }
        }

        Row(
            modifier = Modifier
                .absoluteOffset { IntOffset(rowOffsetPx.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.surface)
                .fillMaxWidth()
                .pointerInput(profile.id, revealedOffsetPx) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            settleJob?.cancel()
                        },
                        onDragCancel = {
                            settleRow(abs(rowOffsetPx) >= revealDistancePx / 2f)
                        },
                        onDragEnd = {
                            settleRow(abs(rowOffsetPx) >= revealDistancePx / 2f)
                        },
                    ) { change, dragAmount ->
                        val nextOffset = (rowOffsetPx + dragAmount).coerceIn(
                            minimumValue = minOf(0f, revealedOffsetPx),
                            maximumValue = maxOf(0f, revealedOffsetPx),
                        )
                        if (nextOffset != rowOffsetPx) {
                            change.consume()
                            rowOffsetPx = nextOffset
                        }
                    }
                }
                .clickable {
                    if (isDeleteRevealed) {
                        settleRow(revealed = false)
                    } else {
                        onSelect()
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(
                selected = isActive,
                onClick = {
                    if (isDeleteRevealed) {
                        settleRow(revealed = false)
                    } else {
                        onSelect()
                    }
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isAvailable) {
                        providerName
                    } else {
                        "$providerName · ${stringResource(MR.strings.connection_unavailable)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                enabled = isAvailable,
                onClick = {
                    if (isDeleteRevealed) {
                        settleRow(revealed = false)
                    } else {
                        onEdit()
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(MR.strings.action_edit),
                )
            }
        }
    }
}

private val CONNECTION_ROW_REVEAL_DISTANCE = 64.dp
private const val CONNECTION_ROW_SETTLE_DURATION_MILLIS = 180

@Composable
private fun EmptyConnectionState(
    onAddClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(MR.strings.connection_no_profiles_title))
        Text(text = stringResource(MR.strings.connection_no_profiles_summary))
        TextButton(onClick = onAddClick) {
            Text(text = stringResource(MR.strings.action_add_connection))
        }
    }
}

@Composable
private fun AddConnectionDialog(
    directoryNameFor: (String) -> String,
    isNameAvailable: (String) -> Boolean,
    onDismissRequest: () -> Unit,
    onAddConnection: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val trimmedName = name.trim()
    val directoryName = directoryNameFor(trimmedName)
    val available = trimmedName.isNotEmpty() && isNameAvailable(trimmedName)

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = available,
                onClick = { onAddConnection(trimmedName) },
            ) {
                Text(text = stringResource(MR.strings.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.action_add_connection))
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.focusRequester(focusRequester),
                label = { Text(text = stringResource(MR.strings.name)) },
                supportingText = {
                    Text(
                        text = if (trimmedName.isEmpty()) {
                            stringResource(MR.strings.information_required_plain)
                        } else if (!available) {
                            stringResource(MR.strings.connection_name_directory_conflict)
                        } else {
                            stringResource(MR.strings.connection_directory_preview, directoryName)
                        },
                    )
                },
                singleLine = true,
            )
        },
    )

    LaunchedEffect(focusRequester) {
        delay(100)
        focusRequester.requestFocus()
    }
}

@Composable
private fun DeleteConnectionDialog(
    connectionName: String,
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text(
                    text = stringResource(MR.strings.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.delete_connection))
        },
        text = {
            Text(text = stringResource(MR.strings.delete_connection_confirmation, connectionName))
        },
    )
}

@Composable
private fun ConnectionConfigModeSelectionDialog(
    selected: ConnectionConfigMode,
    onDismissRequest: () -> Unit,
    onConfirm: (ConnectionConfigMode) -> Unit,
) {
    var selectedMode by rememberSaveable { mutableStateOf(selected) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedMode) }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.connection_config_mode_dialog_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(MR.strings.connection_config_mode_dialog_message))
                ConnectionConfigModeOption(
                    title = stringResource(MR.strings.connection_config_mode_shared),
                    summary = stringResource(MR.strings.connection_config_mode_shared_summary),
                    selected = selectedMode == ConnectionConfigMode.Shared,
                    onClick = { selectedMode = ConnectionConfigMode.Shared },
                )
                ConnectionConfigModeOption(
                    title = stringResource(MR.strings.connection_config_mode_separate),
                    summary = stringResource(MR.strings.connection_config_mode_separate_summary),
                    selected = selectedMode == ConnectionConfigMode.Separate,
                    onClick = { selectedMode = ConnectionConfigMode.Separate },
                )
            }
        },
    )
}

@Composable
private fun ConnectionConfigModeOption(
    title: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Column {
            Text(text = title)
            Text(text = summary)
        }
    }
}
