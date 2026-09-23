package eu.kanade.presentation.more.settings.screen.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.core.net.toUri
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.backup.restore.RestoreDirectoryAccess
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import tachiyomi.core.common.i18n.stringResource as contextStringResource

class RestoreBackupScreen(
    private val uri: String,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { RestoreBackupScreenModel(context, uri) }
        val state by model.state.collectAsState()
        val coroutineScope = rememberCoroutineScope()
        var password by remember { mutableStateOf("") }
        var restoreStarting by remember { mutableStateOf(false) }
        var pendingDirectoryUri by rememberSaveable { mutableStateOf<String?>(null) }
        var directoryPromptUri by rememberSaveable { mutableStateOf<String?>(null) }
        var autoPromptDirectories by rememberSaveable { mutableStateOf(true) }
        var attemptedDirectories by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
        val chooseDirectory =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { selectedUri ->
                pendingDirectoryUri?.let { originalUri ->
                    model.onDirectorySelected(originalUri, selectedUri)
                }
                if (selectedUri == null) autoPromptDirectories = false
                pendingDirectoryUri = null
            }
        LaunchedEffect(
            state.canRestore,
            state.missingDirectories,
            pendingDirectoryUri,
            directoryPromptUri,
            autoPromptDirectories,
        ) {
            if (directoryPromptUri != null && state.missingDirectories.none { it.originalUri == directoryPromptUri }) {
                directoryPromptUri = null
            } else if (autoPromptDirectories && state.canRestore && pendingDirectoryUri == null &&
                directoryPromptUri == null
            ) {
                state.missingDirectories.firstOrNull { it.originalUri !in attemptedDirectories }?.let { next ->
                    attemptedDirectories = attemptedDirectories + next.originalUri
                    directoryPromptUri = next.originalUri
                }
            }
        }
        val promptRequirement = state.missingDirectories.firstOrNull { it.originalUri == directoryPromptUri }
        if (promptRequirement != null) {
            AlertDialog(
                onDismissRequest = {
                    autoPromptDirectories = false
                    directoryPromptUri = null
                },
                title = { Text(stringResource(MR.strings.backup_restore_directory_access)) },
                text = {
                    Column {
                        Text(
                            RestoreDirectoryAccess.displayPath(
                                promptRequirement.originalUri,
                                promptRequirement.displayPath,
                            ),
                        )
                        Text(
                            stringResource(
                                if (promptRequirement.requiresWrite(state.options)) {
                                    MR.strings.backup_restore_directory_write_required
                                } else {
                                    MR.strings.backup_restore_directory_read_required
                                },
                            ),
                        )
                        Text(stringResource(MR.strings.backup_restore_directory_prompt))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            directoryPromptUri = null
                            pendingDirectoryUri = promptRequirement.originalUri
                            try {
                                chooseDirectory.launch(promptRequirement.originalUri.toUri())
                            } catch (_: ActivityNotFoundException) {
                                pendingDirectoryUri = null
                                context.toast(MR.strings.file_picker_error)
                            }
                        },
                    ) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        autoPromptDirectories = false
                        directoryPromptUri = null
                    }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.pref_restore_backup),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            LazyColumnWithAction(
                contentPadding = contentPadding,
                actionLabel = stringResource(MR.strings.action_restore),
                actionEnabled = state.canRestore && state.options.canRestore() &&
                    state.missingDirectories.isEmpty() && !restoreStarting,
                onClickAction = {
                    restoreStarting = true
                    coroutineScope.launch {
                        try {
                            when (model.startRestore(password)) {
                                RestoreStartResult.STARTED -> {
                                    password = ""
                                    navigator.pop()
                                }
                                RestoreStartResult.ALREADY_RUNNING -> context.toast(MR.strings.restore_in_progress)
                                RestoreStartResult.NOT_READY -> Unit
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            context.toast(MR.strings.restoring_backup_error)
                        } finally {
                            restoreStarting = false
                        }
                    }
                },
            ) {
                if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                    item {
                        WarningBanner(MR.strings.restore_miui_warning)
                    }
                }

                if (state.options.libraryEntries && state.hasPausedRemoteHistory) {
                    item { WarningBanner(MR.strings.backup_remote_history_notice) }
                }

                if (state.requiresPassword) {
                    item {
                        SectionCard(MR.strings.backup_password_required) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = {
                                    password = it
                                    model.lock()
                                },
                                label = { Text(stringResource(MR.strings.backup_password_optional)) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                            )
                            Button(
                                onClick = { model.unlock(password) },
                                enabled = password.isNotEmpty() && !state.validating,
                            ) {
                                Text(stringResource(MR.strings.action_ok))
                            }
                        }
                    }
                }

                if (state.canRestore) {
                    item {
                        SectionCard {
                            RestoreOptions.options.forEach { option ->
                                LabeledCheckbox(
                                    label = stringResource(option.label),
                                    checked = option.getter(state.options),
                                    onCheckedChange = {
                                        model.toggle(option.setter, it)
                                    },
                                )
                            }
                        }
                    }
                }

                if (state.canRestore && state.missingDirectories.isNotEmpty()) {
                    item {
                        SectionCard(MR.strings.backup_restore_directory_access) {
                            state.missingDirectories.forEach { requirement ->
                                Text(
                                    text = RestoreDirectoryAccess.displayPath(
                                        requirement.originalUri,
                                        requirement.displayPath,
                                    ),
                                )
                                Text(
                                    text = stringResource(
                                        if (requirement.requiresWrite(state.options)) {
                                            MR.strings.backup_restore_directory_write_required
                                        } else {
                                            MR.strings.backup_restore_directory_read_required
                                        },
                                    ),
                                )
                                Button(
                                    onClick = {
                                        autoPromptDirectories = true
                                        if (requirement.originalUri !in attemptedDirectories) {
                                            attemptedDirectories = attemptedDirectories + requirement.originalUri
                                        }
                                        directoryPromptUri = requirement.originalUri
                                    },
                                ) {
                                    Text(stringResource(MR.strings.backup_restore_choose_directory))
                                }
                            }
                            state.directoryError?.let { Text(stringResource(it)) }
                        }
                    }
                }

                if (state.error != null) {
                    errorMessageItem(state.error)
                }
            }
        }
    }

    private fun LazyListScope.errorMessageItem(
        error: Any?,
    ) {
        item {
            SectionCard {
                Column(
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    val msg = buildAnnotatedString {
                        when (error) {
                            is MissingRestoreTrackers -> {
                                appendLine(stringResource(MR.strings.backup_restore_content_full))
                                if (error.trackers.isNotEmpty()) {
                                    appendLine()
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                        appendLine(stringResource(MR.strings.backup_restore_missing_trackers))
                                    }
                                    error.trackers.joinTo(
                                        this,
                                        separator = "\n- ",
                                        prefix = "- ",
                                    )
                                }
                            }

                            is InvalidRestore -> {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    appendLine(stringResource(MR.strings.invalid_backup_file))
                                }
                                appendLine(error.uri.toString())

                                appendLine()

                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    appendLine(stringResource(MR.strings.invalid_backup_file_error))
                                }
                                appendLine(error.message)
                            }

                            else -> {
                                appendLine(error.toString())
                            }
                        }
                    }

                    SelectionContainer {
                        Text(text = msg)
                    }
                }
            }
        }
    }
}

private class RestoreBackupScreenModel(
    private val context: Context,
    private val uri: String,
) : StateScreenModel<RestoreBackupScreenModel.State>(State()) {
    private val validationVersion = AtomicInteger(0)

    init {
        screenModelScope.launch(Dispatchers.IO) {
            try {
                val encrypted = BackupDecoder(context).inspect(uri.toUri()).encryptedPayload.isNotEmpty()
                if (encrypted) {
                    mutableState.update { it.copy(requiresPassword = true) }
                } else {
                    validate(uri.toUri(), null)
                }
            } catch (e: Exception) {
                setError(InvalidRestore(uri.toUri(), e.message.toString()), false)
            }
        }
    }

    fun lock() {
        validationVersion.incrementAndGet()
        if (state.value.requiresPassword) {
            mutableState.update { it.copy(canRestore = false, error = null, validating = false) }
        }
    }

    fun unlock(password: String) {
        if (password.isEmpty()) return
        val version = validationVersion.incrementAndGet()
        mutableState.update { it.copy(validating = true, canRestore = false) }
        screenModelScope.launch(Dispatchers.IO) {
            val passwordChars = password.toCharArray()
            try {
                validate(uri.toUri(), passwordChars, version)
            } finally {
                passwordChars.fill('\u0000')
                if (validationVersion.get() == version) {
                    mutableState.update { it.copy(validating = false) }
                }
            }
        }
    }

    fun toggle(setter: (RestoreOptions, Boolean) -> RestoreOptions, enabled: Boolean) {
        mutableState.update {
            val options = setter(it.options, enabled)
            val validBindings = it.directoryBindings.filter { (original, selected) ->
                it.directoryRequirements.firstOrNull { requirement -> requirement.originalUri == original }
                    ?.let { requirement ->
                        requirement.isRequired(options) && DirectoryRestorePlan.hasPersistedPermission(
                            context,
                            selected,
                            requirement.requiresWrite(options),
                        )
                    } == true
            }
            it.copy(
                options = options,
                directoryBindings = validBindings +
                    DirectoryRestorePlan.existingBindings(context, it.directoryRequirements, options),
            )
        }
    }

    fun onDirectorySelected(originalUri: String, selectedUri: Uri?) {
        if (selectedUri == null) return
        val requirement = state.value.directoryRequirements.firstOrNull { it.originalUri == originalUri } ?: return
        val requireWrite = requirement.requiresWrite(state.value.options)
        val persisted = DirectoryRestorePlan.persistSelection(context, selectedUri, requireWrite)
        mutableState.update { current ->
            val selected = selectedUri.toString()
            val matchingBindings = if (persisted) {
                val grantedBindings = DirectoryRestorePlan.existingBindings(
                    context,
                    current.directoryRequirements,
                    current.options,
                )
                current.directoryRequirements.asSequence()
                    .filter { RestoreDirectoryAccess.sameDirectory(it.originalUri, originalUri) }
                    .filter {
                        DirectoryRestorePlan.hasPersistedPermission(
                            context,
                            selected,
                            it.requiresWrite(current.options),
                        )
                    }
                    .associate { it.originalUri to (grantedBindings[it.originalUri] ?: selected) } + grantedBindings
            } else {
                emptyMap()
            }
            current.copy(
                directoryBindings = if (persisted) {
                    current.directoryBindings + matchingBindings
                } else {
                    current.directoryBindings - requirement.originalUri
                },
                directoryError = if (persisted) {
                    null
                } else if (requireWrite) {
                    MR.strings.local_library_write_permission_failed
                } else {
                    MR.strings.local_library_permission_failed
                },
            )
        }
    }

    suspend fun startRestore(password: String): RestoreStartResult {
        val current = state.value
        if (!current.canRestore || !current.options.canRestore()) return RestoreStartResult.NOT_READY
        if (current.requiresPassword && password.isEmpty()) return RestoreStartResult.NOT_READY
        val activeRequirements = current.directoryRequirements.filter { it.isRequired(current.options) }
        val bindings = activeRequirements.mapNotNull { requirement ->
            val original = requirement.originalUri
            val selected = current.directoryBindings[original]
            val granted = RestoreDirectoryAccess.resolveGrantedUri(
                context,
                original,
                requirement.requiresWrite(current.options),
            ) ?: selected?.let {
                RestoreDirectoryAccess.resolveGrantedUri(context, it, requirement.requiresWrite(current.options))
            }
            granted?.let { original to it }
        }.toMap()
        mutableState.update { it.copy(directoryBindings = bindings) }
        if (bindings.size != activeRequirements.size) return RestoreStartResult.NOT_READY

        val started = BackupRestoreJob.start(
            context = context,
            uri = uri.toUri(),
            options = current.options,
            password = password.takeIf { current.requiresPassword },
            directoryBindings = bindings,
        )
        return if (started) RestoreStartResult.STARTED else RestoreStartResult.ALREADY_RUNNING
    }

    private fun validate(uri: Uri, password: CharArray?, version: Int = validationVersion.get()) {
        val (results, requirements, hasPausedRemoteHistory) = try {
            val results = BackupFileValidator(context).validate(uri, password)
            val backup = BackupDecoder(context).decode(uri, password)
            Triple(
                results,
                DirectoryRestorePlan.requirements(backup),
                backup.backupManga.any { manga ->
                    manga.smangaState.any { state -> state.pendingHistoryEvents.isNotEmpty() }
                },
            )
        } catch (e: Exception) {
            val message = if (generateSequence<Throwable>(e) { it.cause }.any { it is AEADBadTagException }) {
                context.contextStringResource(MR.strings.backup_password_invalid)
            } else {
                e.message.toString()
            }
            setError(
                error = InvalidRestore(uri, message),
                canRestore = false,
                version = version,
            )
            return
        }

        if (validationVersion.get() != version) return

        mutableState.update {
            it.copy(
                directoryRequirements = requirements,
                directoryBindings = DirectoryRestorePlan.existingBindings(context, requirements, it.options),
                hasPausedRemoteHistory = hasPausedRemoteHistory,
            )
        }

        if (results.missingTrackers.isNotEmpty()) {
            setError(
                error = MissingRestoreTrackers(results.missingTrackers),
                canRestore = true,
            )
            return
        }

        setError(error = null, canRestore = true)
    }

    private fun setError(error: Any?, canRestore: Boolean, version: Int = validationVersion.get()) {
        if (validationVersion.get() != version) return
        mutableState.update {
            it.copy(
                error = error,
                canRestore = canRestore,
            )
        }
    }

    @Immutable
    data class State(
        val error: Any? = null,
        val canRestore: Boolean = false,
        val options: RestoreOptions = RestoreOptions(),
        val directoryRequirements: List<RestoreDirectoryRequirement> = emptyList(),
        val directoryBindings: Map<String, String> = emptyMap(),
        val directoryError: StringResource? = null,
        val requiresPassword: Boolean = false,
        val validating: Boolean = false,
        val hasPausedRemoteHistory: Boolean = false,
    ) {
        val missingDirectories: List<RestoreDirectoryRequirement>
            get() = directoryRequirements.filter { it.isRequired(options) && it.originalUri !in directoryBindings }
    }
}

private enum class RestoreStartResult {
    STARTED,
    ALREADY_RUNNING,
    NOT_READY,
}

private data class MissingRestoreTrackers(
    val trackers: List<String>,
)

private data class InvalidRestore(
    val uri: Uri? = null,
    val message: String,
)
