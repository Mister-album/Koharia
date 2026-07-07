package koharia.source.komga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.ListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.delay
import me.saket.swipe.SwipeAction
import me.saket.swipe.SwipeableActionsBox
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.random.Random

class KomgaServerProfilesScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val serverPreferences = remember { Injekt.get<KomgaServerPreferences>() }
        val localConfigManager = remember { Injekt.get<KomgaLocalConfigManager>() }
        val profiles by serverPreferences.profilesChanges().collectAsState(initial = serverPreferences.getProfiles())
        val activeServerId by serverPreferences.activeServerId.collectAsState()
        val localConfigMode by serverPreferences.localConfigMode.collectAsState()
        val downloadDirectoryMode by serverPreferences.downloadDirectoryMode.collectAsState()

        var showAddDialog by rememberSaveable { mutableStateOf(false) }
        var showModeHelpDialog by rememberSaveable { mutableStateOf(false) }
        var pendingServerName by rememberSaveable { mutableStateOf<String?>(null) }
        var profileToDelete by remember { mutableStateOf<KomgaServerProfile?>(null) }

        fun createServer(name: String) {
            val newProfile = KomgaServerProfile(
                id = nextServerId(profiles),
                name = name,
            )
            serverPreferences.setProfiles(profiles + newProfile)
            localConfigManager.initializeScopeForNewServer(newProfile.id)
            serverPreferences.activeServerId.set(newProfile.id)
            navigator.push(
                KomgaServerSettingsScreen(
                    sourceId = newProfile.id,
                    titleOverride = newProfile.name,
                ),
            )
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.pref_server_management),
                    navigateUp = navigator::pop,
                    actions = {
                        IconButton(onClick = { showModeHelpDialog = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                                contentDescription = stringResource(MR.strings.komga_server_management_mode_help_title),
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
                    onClick = { showAddDialog = true },
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                item {
                    PreferenceGroupHeader(title = stringResource(MR.strings.komga_server_management_modes_group))
                }
                item {
                    ListPreferenceWidget(
                        value = localConfigMode,
                        title = stringResource(MR.strings.komga_local_config_mode),
                        subtitle = stringResource(
                            if (localConfigMode == LocalConfigMode.Shared) {
                                MR.strings.komga_local_config_mode_shared
                            } else {
                                MR.strings.komga_local_config_mode_separate
                            },
                        ),
                        icon = null,
                        entries = mapOf(
                            LocalConfigMode.Shared to stringResource(MR.strings.komga_local_config_mode_shared),
                            LocalConfigMode.Separate to stringResource(MR.strings.komga_local_config_mode_separate),
                        ).toImmutableMap(),
                        onValueChange = { localConfigManager.setLocalConfigMode(it) },
                    )
                }
                item {
                    ListPreferenceWidget(
                        value = downloadDirectoryMode,
                        title = stringResource(MR.strings.komga_download_directory_mode),
                        subtitle = stringResource(
                            if (downloadDirectoryMode == DownloadDirectoryMode.PerServer) {
                                MR.strings.komga_download_directory_mode_per_server
                            } else {
                                MR.strings.komga_download_directory_mode_shared
                            },
                        ),
                        icon = null,
                        entries = mapOf(
                            DownloadDirectoryMode.PerServer to
                                stringResource(MR.strings.komga_download_directory_mode_per_server),
                            DownloadDirectoryMode.Shared to
                                stringResource(MR.strings.komga_download_directory_mode_shared),
                        ).toImmutableMap(),
                        onValueChange = { serverPreferences.downloadDirectoryMode.set(it) },
                    )
                }

                if (profiles.isEmpty()) {
                    item {
                        PreferenceGroupHeader(title = stringResource(MR.strings.komga_server_management_servers_group))
                    }
                    item {
                        EmptyServerState(
                            onAddClick = { showAddDialog = true },
                        )
                    }
                } else {
                    item {
                        PreferenceGroupHeader(title = stringResource(MR.strings.komga_server_management_servers_group))
                    }
                    items(
                        items = profiles,
                        key = KomgaServerProfile::id,
                    ) { profile ->
                        ServerRow(
                            profile = profile,
                            isActive = activeServerId == profile.id,
                            onSelect = { serverPreferences.activeServerId.set(profile.id) },
                            onOpen = {
                                navigator.push(
                                    KomgaServerSettingsScreen(
                                        sourceId = profile.id,
                                        titleOverride = profile.name,
                                    ),
                                )
                            },
                            onEdit = {
                                navigator.push(
                                    KomgaServerSettingsScreen(
                                        sourceId = profile.id,
                                        titleOverride = profile.name,
                                    ),
                                )
                            },
                            onDelete = { profileToDelete = profile },
                        )
                    }
                }
            }
        }

        if (showModeHelpDialog) {
            ModeHelpDialog(
                onDismissRequest = { showModeHelpDialog = false },
            )
        }

        if (showAddDialog) {
            AddServerDialog(
                onDismissRequest = {
                    showAddDialog = false
                    pendingServerName = null
                },
                onAddServer = { name ->
                    showAddDialog = false
                    if (profiles.size == 1) {
                        pendingServerName = name
                    } else {
                        createServer(name)
                    }
                },
            )
        }

        pendingServerName?.let { serverName ->
            LocalConfigModeSelectionDialog(
                selected = localConfigMode,
                onDismissRequest = { pendingServerName = null },
                onConfirm = { mode ->
                    localConfigManager.setLocalConfigMode(mode)
                    createServer(serverName)
                    pendingServerName = null
                },
            )
        }

        profileToDelete?.let { profile ->
            DeleteServerDialog(
                serverName = profile.name,
                onDismissRequest = { profileToDelete = null },
                onDelete = {
                    val remainingProfiles = profiles.filterNot { it.id == profile.id }
                    serverPreferences.setProfiles(remainingProfiles)
                    when {
                        remainingProfiles.isEmpty() ->
                            serverPreferences.activeServerId.set(KomgaServerPreferences.NO_ACTIVE_SERVER)
                        activeServerId == profile.id ->
                            serverPreferences.activeServerId.set(remainingProfiles.first().id)
                    }
                    profileToDelete = null
                },
            )
        }
    }
}

@Composable
private fun ModeHelpDialog(
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
            Text(text = stringResource(MR.strings.komga_server_management_mode_help_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = stringResource(MR.strings.komga_local_config_mode))
                    Text(text = stringResource(MR.strings.komga_local_config_mode_shared_explanation))
                    Text(text = stringResource(MR.strings.komga_local_config_mode_separate_explanation))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = stringResource(MR.strings.komga_download_directory_mode))
                    Text(text = stringResource(MR.strings.komga_download_directory_mode_per_server_explanation))
                    Text(text = stringResource(MR.strings.komga_download_directory_mode_shared_explanation))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = stringResource(MR.strings.delete_server))
                    Text(text = stringResource(MR.strings.komga_server_management_delete_hint))
                }
            }
        },
    )
}

@Composable
private fun ServerRow(
    profile: KomgaServerProfile,
    isActive: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val deleteAction = SwipeAction(
        icon = {
            Icon(
                modifier = Modifier.padding(16.dp),
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(MR.strings.action_delete),
            )
        },
        background = MaterialTheme.colorScheme.errorContainer,
        onSwipe = onDelete,
    )

    SwipeableActionsBox(
        modifier = Modifier.clipToBounds(),
        endActions = listOf(deleteAction),
        swipeThreshold = serverRowSwipeThreshold,
        backgroundUntilSwipeThreshold = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(
                selected = isActive,
                onClick = onSelect,
            )
            Text(
                text = profile.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(MR.strings.action_edit),
                )
            }
        }
    }
}

private val serverRowSwipeThreshold = 72.dp

@Composable
private fun EmptyServerState(
    onAddClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(MR.strings.komga_no_servers_title))
        Text(text = stringResource(MR.strings.komga_no_servers_summary))
        TextButton(onClick = onAddClick) {
            Text(text = stringResource(MR.strings.action_add_server))
        }
    }
}

@Composable
private fun AddServerDialog(
    onDismissRequest: () -> Unit,
    onAddServer: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val trimmedName = name.trim()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = trimmedName.isNotEmpty(),
                onClick = { onAddServer(trimmedName) },
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
            Text(text = stringResource(MR.strings.action_add_server))
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.focusRequester(focusRequester),
                label = { Text(text = stringResource(MR.strings.name)) },
                supportingText = {
                    Text(text = stringResource(MR.strings.information_required_plain))
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
private fun DeleteServerDialog(
    serverName: String,
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.delete_server))
        },
        text = {
            Text(text = stringResource(MR.strings.delete_server_confirmation, serverName))
        },
    )
}

@Composable
private fun LocalConfigModeSelectionDialog(
    selected: LocalConfigMode,
    onDismissRequest: () -> Unit,
    onConfirm: (LocalConfigMode) -> Unit,
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
            Text(text = stringResource(MR.strings.komga_local_config_mode_dialog_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(MR.strings.komga_local_config_mode_dialog_message))
                LocalConfigModeOption(
                    title = stringResource(MR.strings.komga_local_config_mode_shared),
                    summary = stringResource(MR.strings.komga_local_config_mode_shared_summary),
                    selected = selectedMode == LocalConfigMode.Shared,
                    onClick = { selectedMode = LocalConfigMode.Shared },
                )
                LocalConfigModeOption(
                    title = stringResource(MR.strings.komga_local_config_mode_separate),
                    summary = stringResource(MR.strings.komga_local_config_mode_separate_summary),
                    selected = selectedMode == LocalConfigMode.Separate,
                    onClick = { selectedMode = LocalConfigMode.Separate },
                )
            }
        },
    )
}

@Composable
private fun LocalConfigModeOption(
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

private fun nextServerId(profiles: List<KomgaServerProfile>): Long {
    val existingIds = profiles.mapTo(mutableSetOf(), KomgaServerProfile::id)
    val maxId = existingIds.maxOrNull()

    if (maxId != null && maxId < Long.MAX_VALUE) {
        val candidate = maxId + 1
        if (candidate !in existingIds) {
            return candidate
        }
    }

    while (true) {
        val candidate = Random.nextLong(1, Long.MAX_VALUE)
        if (candidate !in existingIds) {
            return candidate
        }
    }
}
