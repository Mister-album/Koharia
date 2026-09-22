package koharia.source.smanga

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.network.NetworkHelper
import koharia.connection.ConnectionAddressVerification
import koharia.connection.ConnectionProfileManager
import koharia.connection.ui.ConnectionAddressSetting
import koharia.smanga.SmangaApi
import koharia.smanga.SmangaException
import koharia.smanga.ui.smangaError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkCircularProgressIndicator
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class SmangaSettingsScreen(
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
        val preferences = remember(sourceId) { SmangaPreferences(sourceId) }
        val initialName = remember(sourceId) { manager.profiles().firstOrNull { it.id == sourceId }?.name.orEmpty() }
        val initialAddress = remember(sourceId) { preferences.address }
        val initialInternalAddress = remember(sourceId) { preferences.internalAddress }
        val initialUsername = remember(sourceId) { preferences.username }
        val initialPassword = remember(sourceId) { preferences.password }
        var name by rememberSaveable(sourceId) { mutableStateOf(initialName) }
        var address by rememberSaveable(sourceId) { mutableStateOf(initialAddress) }
        var internalAddress by rememberSaveable(sourceId) { mutableStateOf(initialInternalAddress) }
        // Both credential fields and their dialog drafts remain outside saved screen state.
        var username by remember(sourceId) { mutableStateOf(initialUsername) }
        var password by remember(sourceId) { mutableStateOf(initialPassword) }
        var saving by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var showUnsaved by rememberSaveable { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val dirty =
            name != initialName || address != initialAddress || internalAddress != initialInternalAddress ||
                username != initialUsername ||
                password != initialPassword
        val validAddress = remember(address) { runCatching { SmangaApi.normalizeBase(address) }.isSuccess }
        val validInternalAddress = remember(internalAddress) {
            internalAddress.isBlank() || runCatching { SmangaApi.normalizeBase(internalAddress) }.isSuccess
        }

        fun discard() {
            scope.launch {
                if (isNew) {
                    manager.remove(sourceId).exceptionOrNull()?.let {
                        message = context.stringResource(MR.strings.connection_delete_failed)
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

        fun save() {
            if (saving) return
            saving = true
            scope.launch {
                try {
                    val normalized = SmangaApi.normalizeBase(address).toString()
                    val account = withContext(Dispatchers.IO) {
                        val api = SmangaApi(
                            networkClient = Injekt.get<NetworkHelper>().client.newBuilder()
                                .callTimeout(10, TimeUnit.SECONDS).build(),
                            json = Injekt.get<Json>(),
                            address = normalized,
                            username = username.trim(),
                            password = password,
                            namespace = "validate-$sourceId",
                        )
                        try {
                            withTimeoutOrNull(30_000) { api.validate(internalAddress) }
                                ?: throw SmangaException(SmangaException.Reason.SERVER)
                        } finally {
                            api.close()
                        }
                    }
                    val profile = manager.profiles().first { it.id == sourceId }
                    withContext(Dispatchers.IO) {
                        preferences.save(normalized, username, password, account.id, internalAddress)
                    }
                    manager.update(profile.copy(name = name.trim()))
                    (Injekt.get<SourceManager>().get(sourceId) as? SmangaSource)?.reload()
                    if (completeOnboarding) {
                        Injekt.get<BasePreferences>().shownOnboardingFlow.set(true)
                        navigator.popUntilRoot()
                    } else {
                        navigator.pop()
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    message = if (error is ConnectionAddressVerification.Failure) {
                        error.userMessage(context)
                    } else {
                        context.smangaError(error)
                    }
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
                        if (isNew) {
                            MR.strings.connection_settings_add_title
                        } else {
                            MR.strings.connection_settings_edit_title
                        },
                    ),
                    navigateUp = ::cancel,
                )
            },
            bottomBar = {
                Button(
                    enabled =
                    !saving && name.isNotBlank() && validAddress && validInternalAddress &&
                        username.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(
                        horizontal = 16.dp,
                        vertical = 12.dp,
                    ),
                    onClick = ::save,
                ) {
                    if (saving) EInkCircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(MR.strings.action_save), Modifier.padding(start = if (saving) 8.dp else 0.dp))
                }
            },
        ) { padding ->
            LazyColumn(Modifier.padding(padding)) {
                item {
                    PreferenceGroupHeader(stringResource(MR.strings.pref_connection_settings))
                    SmangaTextSetting(
                        title = stringResource(MR.strings.lanraragi_connection_name),
                        value = name,
                        hint = stringResource(MR.strings.lanraragi_name_help),
                        enabled = !saving,
                        valid = { it.isNotBlank() },
                    ) { name = it.trim() }
                    ConnectionAddressSetting(
                        publicAddress = address,
                        internalAddress = internalAddress,
                        enabled = !saving,
                    ) { publicValue, internalValue ->
                        address = publicValue
                        internalAddress = internalValue
                        message = null
                    }
                    SmangaTextSetting(
                        title = stringResource(MR.strings.username),
                        value = username,
                        hint = stringResource(MR.strings.username),
                        enabled = !saving,
                        valid = { it.isNotBlank() },
                    ) { username = it.trim() }
                    SmangaTextSetting(
                        title = stringResource(MR.strings.password),
                        value = password,
                        hint = stringResource(MR.strings.password),
                        enabled = !saving,
                        keyboardType = KeyboardType.Password,
                        secret = true,
                        valid = { it.isNotEmpty() },
                    ) { password = it }
                    InfoWidget(stringResource(MR.strings.smanga_setup_help))
                }
            }
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
    }
}

@Composable
private fun SmangaTextSetting(
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
        subtitle = if (secret && value.isNotEmpty()) "••••••••" else value.ifEmpty { hint },
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
