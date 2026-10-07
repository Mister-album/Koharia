package koharia.source.kavita

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.openInBrowser
import koharia.connection.ConnectionProfileManager
import koharia.connection.ConnectionValidation
import koharia.connection.ui.ConnectionAddressSetting
import koharia.kavita.KavitaApiClient
import koharia.kavita.KavitaEndpoint
import koharia.kavita.ui.kavitaError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KavitaSettingsScreen(
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
        val preferences = remember { KavitaPreferences(sourceId) }
        val initialName = remember { manager.profiles().firstOrNull { it.id == sourceId }?.name.orEmpty() }
        var name by remember { mutableStateOf(initialName) }
        // An OPDS URL contains a credential. Never put either field in saved instance state.
        var address by remember { mutableStateOf(preferences.address) }
        var internalAddress by remember { mutableStateOf(preferences.internalAddress) }
        var key by remember { mutableStateOf(preferences.key) }
        var chapterTemplate by remember { mutableStateOf(preferences.chapterTitleTemplate) }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var confirmDiscard by remember { mutableStateOf(false) }
        var sameServer by remember(address) { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val endpoint = remember(address) { runCatching { KavitaEndpoint.parse(address) }.getOrNull() }
        fun discard() {
            scope.launch {
                try {
                    if (isNew) manager.remove(sourceId).getOrThrow()
                    navigator.pop()
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = if (failure is koharia.connection.ConnectionAddressVerification.Failure) {
                        failure.userMessage(context)
                    } else {
                        context.kavitaError(failure)
                    }
                }
            }
        }
        fun cancel() {
            if (saving) return
            val changed = name != initialName || address != preferences.address || key != preferences.key ||
                chapterTemplate != preferences.chapterTitleTemplate || internalAddress != preferences.internalAddress
            if (changed) confirmDiscard = true else discard()
        }
        BackHandler { cancel() }
        Scaffold(topBar = {
            AppBar(title = titleOverride ?: stringResource(MR.strings.pref_connection_settings), navigateUp = ::cancel)
        }) { padding ->
            Column(
                Modifier.padding(padding).verticalScroll(rememberScrollState()),
            ) {
                PreferenceGroupHeader(stringResource(MR.strings.pref_connection_settings))
                KavitaTextSetting(
                    title = stringResource(MR.strings.lanraragi_connection_name),
                    value = name,
                    hint = stringResource(MR.strings.lanraragi_name_help),
                    enabled = !saving,
                    valid = { it.isNotBlank() },
                ) { name = it.trim() }
                ConnectionAddressSetting(address, internalAddress, !saving) { publicValue, internalValue ->
                    address = publicValue
                    internalAddress = internalValue
                }
                KavitaTextSetting(
                    title = stringResource(MR.strings.kavita_key),
                    value = key,
                    hint = stringResource(MR.strings.kavita_setup_help),
                    enabled = !saving,
                    secret = true,
                    keyboardType = KeyboardType.Password,
                ) { key = it.trim() }
                PreferenceGroupHeader(stringResource(MR.strings.pref_category_library))
                KavitaTextSetting(
                    title = stringResource(MR.strings.kavita_chapter_template),
                    value = chapterTemplate,
                    hint = stringResource(MR.strings.kavita_chapter_template_help),
                    enabled = !saving,
                    valid = koharia.kavita.KavitaChapterTitle::valid,
                ) { chapterTemplate = it }
                if (!isNew) {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.kavita_dashboard),
                        subtitle = stringResource(MR.strings.kavita_dashboard_help),
                        enabled = !saving,
                        onPreferenceClick = { navigator.push(koharia.kavita.ui.KavitaDashboardScreen(sourceId)) },
                    )
                }
                if (!isNew && endpoint != null && endpoint.base.toString() != preferences.address) {
                    CheckboxItem(stringResource(MR.strings.kavita_same_server_address), sameServer) {
                        sameServer = !sameServer
                    }
                    Text(stringResource(MR.strings.kavita_identity_help))
                }
                TextButton(
                    enabled = endpoint != null,
                    onClick = { endpoint?.base?.toString()?.let { context.openInBrowser(it) } },
                ) { Text(stringResource(MR.strings.action_open_in_browser)) }
                Button(
                    enabled = !saving && name.isNotBlank() && endpoint != null &&
                        koharia.kavita.KavitaChapterTitle.valid(chapterTemplate) &&
                        (key.isNotBlank() || !endpoint.importedKey.isNullOrBlank()),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                val parsed = requireNotNull(endpoint)
                                val credential = parsed.importedKey ?: key.trim()
                                val normalizedInternal = internalAddress.takeIf { it.isNotBlank() }?.let {
                                    requireNotNull(koharia.connection.ConnectionAddressRouter.normalize(it)).toString()
                                }.orEmpty()
                                if (ConnectionValidation.required(
                                        isNew,
                                        parsed.base.toString() != preferences.address ||
                                            credential != preferences.key ||
                                            normalizedInternal != preferences.internalAddress,
                                        preferences.identity == null,
                                    )
                                ) {
                                    val account = ConnectionValidation.at(ConnectionValidation.Endpoint.PUBLIC) {
                                        withContext(Dispatchers.IO) {
                                            KavitaApiClient(
                                                ConnectionValidation.client(Injekt.get<NetworkHelper>().client),
                                                parsed.base.toString(),
                                                credential,
                                                "validate-$sourceId",
                                            ).use {
                                                it.getAccount()
                                            }
                                        }
                                    }
                                    if (normalizedInternal.isNotEmpty()) {
                                        val internalAccount = ConnectionValidation.at(
                                            ConnectionValidation.Endpoint.INTERNAL,
                                        ) {
                                            withContext(Dispatchers.IO) {
                                                KavitaApiClient(
                                                    ConnectionValidation.client(
                                                        Injekt.get<NetworkHelper>().client,
                                                        normalizedInternal,
                                                    ),
                                                    normalizedInternal,
                                                    credential,
                                                    "validate-internal-$sourceId",
                                                ).use { it.getAccount() }
                                            }
                                        }
                                        if (!account.identity.sameServerVerified(internalAccount.identity)) {
                                            throw koharia.connection.ConnectionAddressVerification.Failure(
                                                koharia.connection.ConnectionAddressVerification.Reason.MISMATCH,
                                            )
                                        }
                                    }
                                    preferences.save(parsed.base.toString(), credential, account, sameServer)
                                    preferences.internalAddress = normalizedInternal
                                }
                                val profile = manager.profiles().first { it.id == sourceId }
                                preferences.chapterTitleTemplate = chapterTemplate
                                (Injekt.get<SourceManager>().get(sourceId) as? KavitaSource)?.reload()
                                manager.update(profile.copy(name = name.trim()))
                                if (completeOnboarding) {
                                    Injekt.get<BasePreferences>().shownOnboardingFlow.set(true)
                                    navigator.popUntilRoot()
                                } else {
                                    navigator.pop()
                                }
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                error = if (failure is koharia.connection.ConnectionAddressVerification.Failure) {
                                    failure.userMessage(context)
                                } else {
                                    context.kavitaError(failure)
                                }
                            } finally {
                                saving = false
                            }
                        }
                    },
                ) { Text(stringResource(MR.strings.action_save)) }
            }
        }
        error?.let { message ->
            AlertDialog(onDismissRequest = { error = null }, text = { Text(message) }, confirmButton = {
                TextButton(onClick = { error = null }) { Text(stringResource(MR.strings.action_ok)) }
            })
        }
        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text(stringResource(MR.strings.komga_unsaved_changes_title)) },
                text = { Text(stringResource(MR.strings.komga_unsaved_changes_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDiscard = false
                        discard()
                    }) { Text(stringResource(MR.strings.komga_action_discard)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
    }
}

@Composable
private fun KavitaTextSetting(
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
