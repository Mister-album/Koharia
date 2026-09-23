package eu.kanade.presentation.more.settings.screen.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.presentation.more.settings.widget.InfoWidget
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class CreateBackupScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { CreateBackupScreenModel() }
        val state by model.state.collectAsState()
        val coroutineScope = rememberCoroutineScope()
        var password by remember { mutableStateOf("") }
        var confirmation by remember { mutableStateOf("") }
        var backupStarting by remember { mutableStateOf(false) }
        var pendingEncryption by rememberSaveable { mutableStateOf(false) }
        var pendingOptions by rememberSaveable { mutableStateOf<BooleanArray?>(null) }

        val chooseBackupDir = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/*"),
        ) {
            if (it != null) {
                val selectedOptions = pendingOptions?.let(BackupOptions::fromBooleanArray)
                if (selectedOptions == null || (pendingEncryption && password.isEmpty())) {
                    context.toast(MR.strings.backup_selection_lost)
                    pendingEncryption = false
                    pendingOptions = null
                    return@rememberLauncherForActivityResult
                }
                try {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    backupStarting = true
                    val selectedPassword = password
                    coroutineScope.launch {
                        try {
                            if (model.createBackup(context, it, selectedOptions, selectedPassword)) {
                                password = ""
                                confirmation = ""
                                navigator.pop()
                            } else {
                                context.toast(MR.strings.backup_in_progress)
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            context.toast(MR.strings.creating_backup_error)
                        } finally {
                            backupStarting = false
                        }
                    }
                } catch (_: SecurityException) {
                    context.toast(MR.strings.creating_backup_error)
                }
            }
            pendingEncryption = false
            pendingOptions = null
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.pref_create_backup),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            LazyColumnWithAction(
                contentPadding = contentPadding,
                actionLabel = stringResource(MR.strings.action_create),
                actionEnabled = state.options.canCreate() && (password.isEmpty() || password == confirmation) &&
                    !backupStarting,
                onClickAction = {
                    if (!BackupCreateJob.isManualJobRunning(context)) {
                        try {
                            pendingEncryption = password.isNotEmpty()
                            pendingOptions = state.options.asBooleanArray()
                            chooseBackupDir.launch(BackupCreator.getFilename())
                        } catch (e: ActivityNotFoundException) {
                            context.toast(MR.strings.file_picker_error)
                        }
                    } else {
                        context.toast(MR.strings.backup_in_progress)
                    }
                },
            ) {
                if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                    item {
                        WarningBanner(MR.strings.restore_miui_warning)
                    }
                }

                item { PreferenceGroupHeader(stringResource(MR.strings.label_library)) }
                options(BackupOptions.libraryOptions, state, model)
                item { InfoWidget(stringResource(MR.strings.backup_external_files_notice)) }
                item { Spacer(Modifier.height(12.dp)) }

                item { PreferenceGroupHeader(stringResource(MR.strings.label_settings)) }
                options(BackupOptions.settingsOptions, state, model)
                item { Spacer(Modifier.height(12.dp)) }

                item { PreferenceGroupHeader(stringResource(MR.strings.backup_password_optional)) }
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = PrefsHorizontalPadding),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(MR.strings.password)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (password.isNotEmpty()) {
                            val mismatch = confirmation.isNotEmpty() && password != confirmation
                            OutlinedTextField(
                                value = confirmation,
                                onValueChange = { confirmation = it },
                                label = { Text(stringResource(MR.strings.backup_password_confirm)) },
                                supportingText = if (mismatch) {
                                    { Text(stringResource(MR.strings.backup_password_mismatch)) }
                                } else {
                                    null
                                },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                isError = mismatch,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (password.isEmpty()) {
                    item { InfoWidget(stringResource(MR.strings.backup_unencrypted_warning)) }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    private fun LazyListScope.options(
        options: ImmutableList<BackupOptions.Entry>,
        state: CreateBackupScreenModel.State,
        model: CreateBackupScreenModel,
    ) {
        options.forEach { option ->
            if (option.enabled(state.options)) {
                item {
                    SwitchPreferenceWidget(
                        modifier = if (option.nested) Modifier.padding(start = 16.dp) else Modifier,
                        title = stringResource(option.label),
                        subtitle = option.subtitle?.let { stringResource(it) },
                        checked = option.getter(state.options),
                        onCheckedChanged = { model.toggle(option.setter, it) },
                    )
                }
            }
        }
    }
}

private class CreateBackupScreenModel : StateScreenModel<CreateBackupScreenModel.State>(State()) {

    fun toggle(setter: (BackupOptions, Boolean) -> BackupOptions, enabled: Boolean) {
        mutableState.update {
            it.copy(
                options = setter(it.options, enabled),
            )
        }
    }

    suspend fun createBackup(context: Context, uri: Uri, options: BackupOptions, password: String): Boolean =
        BackupCreateJob.startNow(context, uri, options, password)

    @Immutable
    data class State(
        val options: BackupOptions = BackupOptions(),
    )
}
