package koharia.connection.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.outlined.Refresh
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
import koharia.connection.ConnectionLibraryRefreshAdapter
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
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkCircularProgressIndicator
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.motion.LocalEInkDisplayPolicy
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs
import kotlin.math.roundToInt
import tachiyomi.core.common.i18n.stringResource as contextStringResource

class LibraryConnectionProfilesScreen(
    private val openAddDialog: Boolean = false,
    private val initialProviderId: String? = null,
    private val completeOnboardingAfterAdd: Boolean = false,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val connectionPreferences = remember { Injekt.get<ConnectionPreferences>() }
        val connectionProfileManager = remember { Injekt.get<ConnectionProfileManager>() }
        val connectionRegistry = remember { Injekt.get<ConnectionRegistry>() }
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val availableProviders = remember(connectionRegistry) { connectionRegistry.availableProviders() }
        val managementAdapters = remember(availableProviders) {
            availableProviders.mapNotNull { provider ->
                (provider as? ConnectionManagementAdapter)?.let { provider.id to it }
            }
        }
        val profiles by connectionPreferences.profilesChanges()
            .collectAsState(initial = connectionPreferences.getProfiles())
        val activeConnectionId by connectionPreferences.activeConnectionId.collectAsState()
        val scope = rememberCoroutineScope()

        var selectedProviderId by rememberSaveable {
            mutableStateOf(initialProviderId ?: availableProviders.singleOrNull()?.id)
        }
        var showProviderDialog by rememberSaveable { mutableStateOf(false) }
        var showAddDialog by rememberSaveable { mutableStateOf(false) }
        var initialAddHandled by rememberSaveable { mutableStateOf(false) }
        var showModeHelpDialog by rememberSaveable { mutableStateOf(false) }
        var profileToDelete by remember { mutableStateOf<LibraryConnectionProfile?>(null) }
        var profileActions by remember { mutableStateOf<LibraryConnectionProfile?>(null) }
        var refreshingConnectionIds by remember { mutableStateOf(emptySet<Long>()) }
        val localRefreshIds by remember { Injekt.get<koharia.source.local.LocalLibraryRefreshTasks>() }
            .activeIds.collectAsState()
        val addConnectionTitle = stringResource(MR.strings.connection_settings_add_title)
        val editConnectionTitle = stringResource(MR.strings.connection_settings_edit_title)

        fun editProfile(profile: LibraryConnectionProfile) {
            connectionRegistry.provider(profile.providerId)
                ?.createSettingsScreen(profile = profile, titleOverride = editConnectionTitle)
                ?.let(navigator::push)
        }

        fun refreshProfile(profile: LibraryConnectionProfile) {
            val refreshAdapter = sourceManager.get(profile.id) as? ConnectionLibraryRefreshAdapter ?: return
            if (profile.id in refreshingConnectionIds || profile.id in localRefreshIds) return
            refreshingConnectionIds += profile.id
            scope.launch {
                val result = try {
                    refreshAdapter.refreshLibrary()
                } finally {
                    refreshingConnectionIds -= profile.id
                }
                result.fold(
                    onSuccess = { refreshResult ->
                        context.toast(
                            context.contextStringResource(
                                MR.strings.local_library_refresh_complete,
                                refreshResult.itemCount,
                            ),
                        )
                    },
                    onFailure = { context.toast(MR.strings.local_library_refresh_failed) },
                )
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
                    completeOnboardingOnSave = completeOnboardingAfterAdd,
                )
                ?.let(navigator::push)
        }

        fun beginProviderSetup(providerId: String) {
            val provider = connectionRegistry.provider(providerId) ?: return
            selectedProviderId = providerId
            if (provider.configuresConnectionNameInSettings) {
                createConnection(provider.displayName)
            } else {
                showAddDialog = true
            }
        }

        fun startAddConnection() {
            when (availableProviders.size) {
                0 -> Unit
                1 -> beginProviderSetup(availableProviders.single().id)
                else -> showProviderDialog = true
            }
        }

        LaunchedEffect(openAddDialog, initialProviderId) {
            if (!openAddDialog || initialAddHandled) return@LaunchedEffect
            initialAddHandled = true
            when {
                initialProviderId != null -> beginProviderSetup(initialProviderId)
                availableProviders.size == 1 -> beginProviderSetup(availableProviders.single().id)
                availableProviders.size > 1 -> showProviderDialog = true
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
                        val refreshAdapter = sourceManager.get(profile.id) as? ConnectionLibraryRefreshAdapter
                        ConnectionRow(
                            profile = profile,
                            providerName = provider?.displayName ?: profile.providerId,
                            isAvailable = provider != null,
                            isActive = activeConnectionId == profile.id,
                            onSelect = { connectionPreferences.activeConnectionId.set(profile.id) },
                            onLongClick = { profileActions = profile },
                            onEdit = { editProfile(profile) },
                            canRefresh = refreshAdapter != null,
                            isRefreshing = profile.id in refreshingConnectionIds || profile.id in localRefreshIds,
                            onRefresh = { refreshProfile(profile) },
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

        profileActions?.let { profile ->
            val provider = connectionRegistry.provider(profile.providerId)
            val canRefresh = sourceManager.get(profile.id) is ConnectionLibraryRefreshAdapter
            AlertDialog(
                onDismissRequest = { profileActions = null },
                title = { Text(profile.name) },
                text = {
                    Column {
                        TextButton(
                            enabled = activeConnectionId != profile.id,
                            onClick = {
                                connectionPreferences.activeConnectionId.set(profile.id)
                                profileActions = null
                            },
                        ) { Text(stringResource(MR.strings.connection_set_active)) }
                        TextButton(
                            enabled =
                            canRefresh && profile.id !in refreshingConnectionIds && profile.id !in localRefreshIds,
                            onClick = {
                                refreshProfile(profile)
                                profileActions = null
                            },
                        ) { Text(stringResource(MR.strings.action_webview_refresh)) }
                        TextButton(
                            enabled = provider != null,
                            onClick = {
                                editProfile(profile)
                                profileActions = null
                            },
                        ) { Text(stringResource(MR.strings.action_edit)) }
                        TextButton(
                            onClick = {
                                profileToDelete = profile
                                profileActions = null
                            },
                        ) { Text(stringResource(MR.strings.action_delete)) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { profileActions = null }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        if (showProviderDialog) {
            ProviderSelectionDialog(
                providers = availableProviders,
                onDismissRequest = { showProviderDialog = false },
                onSelect = { providerId ->
                    showProviderDialog = false
                    beginProviderSetup(providerId)
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
                },
                onAddConnection = { name ->
                    showAddDialog = false
                    createConnection(name)
                },
            )
        }

        profileToDelete?.let { profile ->
            DeleteConnectionDialog(
                connectionName = profile.name,
                message = connectionRegistry.provider(profile.providerId)?.deletionMessage,
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ConnectionProviderIcon(
                                providerId = provider.id,
                                modifier = Modifier.size(28.dp),
                            )
                            Text(text = provider.displayName)
                        }
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
private fun ConnectionRow(
    profile: LibraryConnectionProfile,
    providerName: String,
    isAvailable: Boolean,
    isActive: Boolean,
    onSelect: () -> Unit,
    onLongClick: () -> Unit,
    canRefresh: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val eInkEnabled = LocalEInkDisplayPolicy.current.enabled
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val revealDistancePx = with(density) { CONNECTION_ROW_REVEAL_DISTANCE.toPx() }
    val revealedOffsetPx = if (layoutDirection == LayoutDirection.Ltr) -revealDistancePx else revealDistancePx
    var rowOffsetPx by remember(profile.id) { mutableFloatStateOf(0f) }
    var settleJob by remember(profile.id) { mutableStateOf<Job?>(null) }

    fun settleRow(revealed: Boolean) {
        val targetOffset = if (revealed) revealedOffsetPx else 0f
        settleJob?.cancel()
        if (eInkEnabled) {
            rowOffsetPx = targetOffset
            settleJob = null
            return
        }
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
                .combinedClickable(
                    onClick = {
                        if (isDeleteRevealed) settleRow(revealed = false) else onSelect()
                    },
                    onLongClick = {
                        if (isDeleteRevealed) settleRow(revealed = false) else onLongClick()
                    },
                )
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
            ConnectionProviderIcon(
                providerId = profile.providerId,
                modifier = Modifier.size(32.dp),
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
            if (canRefresh) {
                IconButton(
                    enabled = isAvailable && !isRefreshing,
                    onClick = {
                        if (isDeleteRevealed) {
                            settleRow(revealed = false)
                        } else {
                            onRefresh()
                        }
                    },
                ) {
                    if (isRefreshing) {
                        EInkCircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(MR.strings.action_webview_refresh),
                        )
                    }
                }
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
    message: dev.icerock.moko.resources.StringResource? = null,
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
            Text(
                text = if (message !=
                    null
                ) {
                    stringResource(message, connectionName)
                } else {
                    stringResource(MR.strings.delete_connection_confirmation, connectionName)
                },
            )
        },
    )
}
