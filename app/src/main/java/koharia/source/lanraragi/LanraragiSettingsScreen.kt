package koharia.source.lanraragi

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.InfoWidget
import eu.kanade.presentation.more.settings.widget.ListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.network.NetworkHelper
import koharia.connection.ConnectionProfileManager
import koharia.domain.lanraragi.LanraragiEntry
import koharia.domain.lanraragi.LanraragiRepository
import koharia.lanraragi.LanraragiApi
import koharia.lanraragi.LanraragiArchiveOpenMode
import koharia.lanraragi.ui.lanraragiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkCircularProgressIndicator
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class LanraragiSettingsScreen(
    private val sourceId: Long,
    private val isNew: Boolean = false,
    private val completeOnboarding: Boolean = false,
    private val titleOverride: String? = null,
) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val manager = remember { Injekt.get<ConnectionProfileManager>() }
        val prefs = remember(sourceId) { LanraragiPreferences(sourceId) }
        val initialName = remember(sourceId) { manager.profiles().firstOrNull { it.id == sourceId }?.name.orEmpty() }
        val initialAddress = remember(sourceId) { prefs.address }
        val initialKey = remember(sourceId) { prefs.apiKey }
        val initialMode = remember(sourceId) { prefs.archiveOpenMode }
        val initialCategory = remember(sourceId) { prefs.defaultCategory }
        val initialGrouped = remember(sourceId) { prefs.groupCollections }
        var name by rememberSaveable(sourceId) { mutableStateOf(initialName) }
        var address by rememberSaveable(sourceId) { mutableStateOf(initialAddress) }
        var archiveOpenMode by rememberSaveable(sourceId) { mutableStateOf(initialMode) }
        var defaultCategory by rememberSaveable(sourceId) { mutableStateOf(initialCategory) }
        var grouped by rememberSaveable(sourceId) { mutableStateOf(initialGrouped) }
        // Credentials stay in memory and never enter saved screen state.
        var apiKey by remember(sourceId) { mutableStateOf(initialKey) }
        var saving by remember { mutableStateOf(false) }
        var showConnectionFailure by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var showHelp by rememberSaveable { mutableStateOf(false) }
        var showUnsaved by rememberSaveable { mutableStateOf(false) }
        val repository = remember { Injekt.get<LanraragiRepository>() }
        val cachedEntries by remember(sourceId) { repository.observeEntries(sourceId) }.collectAsState(emptyList())
        val scope = rememberCoroutineScope()
        val busy = saving
        val dirty = name != initialName || address != initialAddress || apiKey != initialKey ||
            archiveOpenMode != initialMode || defaultCategory != initialCategory || grouped != initialGrouped
        val validAddress = remember(address) { runCatching { LanraragiApi.normalizeBase(address) }.isSuccess }
        val categories = if (address == initialAddress) cachedEntries else emptyList()
        val categoryOptions = linkedMapOf("" to stringResource(MR.strings.all)).apply {
            categories.filter { it.kind == LanraragiEntry.Kind.CATEGORY }.sortedBy { it.title }
                .forEach { put(it.id, it.title) }
            if (defaultCategory.isNotEmpty() && defaultCategory !in this) {
                put(defaultCategory, stringResource(MR.strings.lanraragi_category_unavailable))
            }
        }
        val modeOptions = mapOf(
            LanraragiArchiveOpenMode.READER to stringResource(MR.strings.lanraragi_open_reader),
            LanraragiArchiveOpenMode.PAGE_PREVIEW to stringResource(MR.strings.lanraragi_open_preview),
        )
        fun discard() {
            scope.launch {
                if (isNew) {
                    manager.remove(sourceId).exceptionOrNull()?.let {
                        message = context.lanraragiError(it)
                        return@launch
                    }
                }
                navigator.pop()
            }
        }
        fun cancel() {
            if (saving) return
            if (dirty) showUnsaved = true else discard()
        }
        fun save(checkConnection: Boolean = true) {
            if (saving) return
            saving = true
            scope.launch {
                try {
                    val normalized = LanraragiApi.normalizeBase(address).toString()
                    if (checkConnection) {
                        val connected = try {
                            withContext(Dispatchers.IO) {
                                val api = LanraragiApi(
                                    normalized,
                                    apiKey.trim(),
                                    Injekt.get<NetworkHelper>().client.newBuilder()
                                        .callTimeout(10, TimeUnit.SECONDS).build(),
                                    Injekt.get<Json>(),
                                )
                                try {
                                    withTimeoutOrNull(10_000) { api.serverInfo(true) } != null
                                } finally {
                                    api.close()
                                }
                            }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            false
                        }
                        if (!connected) {
                            showConnectionFailure = true
                            return@launch
                        }
                    }
                    val profile = manager.profiles().first { it.id == sourceId }
                    withContext(Dispatchers.IO) {
                        prefs.save(normalized, apiKey.trim(), archiveOpenMode, defaultCategory, grouped)
                    }
                    manager.update(profile.copy(name = name.trim()))
                    (Injekt.get<SourceManager>().get(sourceId) as? LanraragiSource)?.reload()
                    if (completeOnboarding) {
                        Injekt.get<BasePreferences>().shownOnboardingFlow.set(true)
                        navigator.popUntilRoot()
                    } else {
                        navigator.pop()
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    message = context.lanraragiError(error)
                } finally {
                    saving = false
                }
            }
        }
        BackHandler { cancel() }
        Scaffold(
            topBar = {
                AppBar(
                    title = titleOverride ?: stringResource(
                        if (isNew) MR.strings.lanraragi_add_title else MR.strings.lanraragi_edit_title,
                    ),
                    navigateUp = ::cancel,
                    actions = {
                        IconButton(onClick = { showHelp = true }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.HelpOutline,
                                stringResource(MR.strings.lanraragi_help_title),
                            )
                        }
                    },
                )
            },
            bottomBar = {
                Button(
                    enabled = !busy && name.isNotBlank() && validAddress,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(
                        horizontal = 16.dp,
                        vertical = 12.dp,
                    ),
                    onClick = { save() },
                ) {
                    if (saving) EInkCircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(MR.strings.action_save), Modifier.padding(start = if (saving) 8.dp else 0.dp))
                }
            },
        ) { padding ->
            LazyColumn(Modifier.padding(padding)) {
                item {
                    PreferenceGroupHeader(stringResource(MR.strings.pref_connection_settings))
                    LanraragiTextSetting(
                        title = stringResource(MR.strings.lanraragi_connection_name),
                        value = name,
                        enabled = !busy,
                        hint = stringResource(MR.strings.lanraragi_name_help),
                        valid = { it.isNotBlank() },
                    ) { name = it.trim() }
                    LanraragiTextSetting(
                        title = stringResource(MR.strings.lanraragi_address),
                        value = address,
                        enabled = !busy,
                        hint = stringResource(MR.strings.lanraragi_error_address),
                        keyboardType = KeyboardType.Uri,
                        valid = { runCatching { LanraragiApi.normalizeBase(it) }.isSuccess },
                    ) {
                        if (address != it.trim()) {
                            defaultCategory = ""
                        }
                        address = it.trim()
                        message = null
                    }
                    LanraragiTextSetting(
                        title = stringResource(MR.strings.lanraragi_api_key),
                        value = apiKey,
                        enabled = !busy,
                        hint = stringResource(MR.strings.lanraragi_api_key_help),
                        keyboardType = KeyboardType.Password,
                        secret = true,
                    ) {
                        apiKey = it.trim()
                        message = null
                    }
                    HorizontalDivider()
                    PreferenceGroupHeader(stringResource(MR.strings.pref_category_library))
                    ListPreferenceWidget(
                        value = defaultCategory,
                        title = stringResource(MR.strings.lanraragi_default_category),
                        subtitle = categoryOptions[defaultCategory],
                        icon = null,
                        entries = categoryOptions,
                        enabled = !busy,
                        onValueChange = { defaultCategory = it },
                    )
                    if (categoryOptions.size == 1) InfoWidget(stringResource(MR.strings.lanraragi_category_test_hint))
                    SwitchPreferenceWidget(
                        title = stringResource(MR.strings.lanraragi_group_tanks),
                        subtitle = stringResource(MR.strings.lanraragi_group_default_help),
                        checked = grouped,
                        enabled = !busy,
                        onCheckedChanged = { grouped = it },
                    )
                    ListPreferenceWidget(
                        value = archiveOpenMode,
                        title = stringResource(MR.strings.lanraragi_archive_open_mode),
                        subtitle = modeOptions[archiveOpenMode],
                        icon = null,
                        entries = modeOptions,
                        enabled = !busy,
                        onValueChange = { archiveOpenMode = it },
                    )
                }
            }
        }
        if (showConnectionFailure) {
            AlertDialog(
                onDismissRequest = { showConnectionFailure = false },
                title = { Text(stringResource(MR.strings.lanraragi_connection_failed_title)) },
                text = { Text(stringResource(MR.strings.lanraragi_save_connection_failed)) },
                confirmButton = {
                    TextButton(onClick = {
                        showConnectionFailure = false
                        save(checkConnection = false)
                    }) {
                        Text(stringResource(MR.strings.lanraragi_save_anyway))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConnectionFailure = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
        message?.let { error ->
            AlertDialog(
                onDismissRequest = { message = null },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = { message = null }) { Text(stringResource(MR.strings.action_ok)) }
                },
            )
        }
        if (showUnsaved) {
            AlertDialog(
                onDismissRequest = { showUnsaved = false },
                title = { Text(stringResource(MR.strings.komga_unsaved_changes_title)) },
                text = { Text(stringResource(MR.strings.komga_unsaved_changes_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showUnsaved = false
                        discard()
                    }) {
                        Text(stringResource(MR.strings.komga_action_discard))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUnsaved = false }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
        if (showHelp) {
            AlertDialog(
                onDismissRequest = { showHelp = false },
                title = { Text(stringResource(MR.strings.lanraragi_help_title)) },
                text = {
                    Column {
                        Text(stringResource(MR.strings.lanraragi_setup_help))
                        Text(stringResource(MR.strings.lanraragi_unread_local), Modifier.padding(top = 16.dp))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showHelp = false }) { Text(stringResource(MR.strings.action_ok)) }
                },
            )
        }
    }
}

@Composable
private fun LanraragiTextSetting(
    title: String,
    value: String,
    hint: String,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    valid: (String) -> Boolean = { true },
    onConfirm: (String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    TextPreferenceWidget(
        title = title,
        subtitle = if (value.isEmpty()) {
            hint
        } else if (secret) {
            "••••••••"
        } else {
            value
        },
        enabled = enabled,
        onPreferenceClick = { showDialog = true },
    )
    if (showDialog) {
        var draft by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = !valid(draft),
                    supportingText = { Text(hint) },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                )
            },
            confirmButton = {
                TextButton(enabled = valid(draft), onClick = {
                    onConfirm(draft)
                    showDialog = false
                }) {
                    Text(stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}
