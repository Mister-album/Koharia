package koharia.source.komga

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.preference.DeferredSharedPreferencesDataStore
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.ui.source.DataStoreHolder
import eu.kanade.tachiyomi.ui.source.SourcePreferencesScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkCircularProgressIndicator
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KomgaServerSettingsScreen(
    private val sourceId: Long,
    private val titleOverride: String? = null,
    private val isNew: Boolean = false,
    private val completeOnboardingOnSave: Boolean = false,
) : Screen() {

    @Composable
    override fun Content() {
        var showHelpDialog by rememberSaveable { mutableStateOf(false) }
        var showUnsavedDialog by rememberSaveable { mutableStateOf(false) }
        var isSaving by rememberSaveable { mutableStateOf(false) }
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val basePreferences = remember { Injekt.get<BasePreferences>() }
        val serverRemovalManager = remember { Injekt.get<KomgaServerRemovalManager>() }
        val serverProfileManager = remember { Injekt.get<KomgaServerProfileManager>() }
        val serverPreferences = remember { Injekt.get<KomgaServerPreferences>() }
        val scope = rememberCoroutineScope()

        val komgaSource = remember(sourceId) {
            Injekt.get<SourceManager>().getOrStub(sourceId) as? KomgaSource
        }

        val deferredDataStore = remember(komgaSource) {
            val prefs = komgaSource?.sourcePreferences() ?: return@remember null
            DeferredSharedPreferencesDataStore(prefs)
        }

        DisposableEffect(deferredDataStore) {
            DataStoreHolder.dataStore = deferredDataStore
            onDispose {
                DataStoreHolder.dataStore = null
            }
        }

        fun discardAndPop() {
            scope.launch {
                if (isNew) {
                    val result = serverRemovalManager.removeServer(sourceId)
                    if (result.isFailure) {
                        context.toast(MR.strings.komga_server_delete_failed)
                    }
                }
                navigator.pop()
            }
        }

        fun onCancel() {
            if (deferredDataStore?.hasUnsavedChanges == true) {
                showUnsavedDialog = true
            } else {
                discardAndPop()
            }
        }

        fun saveAndClose() {
            if (isSaving) return
            isSaving = true
            scope.launch {
                try {
                    val currentName = serverPreferences.getProfiles()
                        .find { it.id == sourceId }
                        ?.name
                        .orEmpty()
                    val requestedName = deferredDataStore
                        ?.getString(KomgaSource.PREF_SERVER_PROFILE_NAME, currentName)
                        ?.trim()
                        ?: currentName
                    val result = serverProfileManager.renameServer(sourceId, requestedName)
                    if (result.isFailure) {
                        context.toast(MR.strings.komga_server_rename_failed)
                        return@launch
                    }

                    deferredDataStore?.putString(
                        KomgaSource.PREF_SERVER_PROFILE_NAME,
                        requestedName,
                    )
                    deferredDataStore?.applyChanges()
                    if (completeOnboardingOnSave) {
                        basePreferences.shownOnboardingFlow.set(true)
                        navigator.popUntilRoot()
                    } else {
                        navigator.pop()
                    }
                } finally {
                    isSaving = false
                }
            }
        }

        BackHandler {
            onCancel()
        }

        SourcePreferencesScreen(
            sourceId = sourceId,
            titleOverride = titleOverride ?: stringResource(MR.strings.pref_komga_server),
            navigateUpOverride = { onCancel() },
            actions = {
                IconButton(onClick = { showHelpDialog = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = stringResource(MR.strings.komga_server_settings_help_title),
                    )
                }
            },
            bottomBar = {
                Button(
                    enabled = !isSaving,
                    onClick = ::saveAndClose,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    if (isSaving) {
                        EInkCircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        text = stringResource(MR.strings.action_save),
                        modifier = Modifier.padding(start = if (isSaving) 8.dp else 0.dp),
                    )
                }
            },
        ).Content()

        if (showUnsavedDialog) {
            AlertDialog(
                onDismissRequest = { showUnsavedDialog = false },
                title = { Text(text = stringResource(MR.strings.komga_unsaved_changes_title)) },
                text = { Text(text = stringResource(MR.strings.komga_unsaved_changes_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        discardAndPop()
                    }) {
                        Text(text = stringResource(MR.strings.komga_action_discard))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        if (showHelpDialog) {
            AlertDialog(
                onDismissRequest = { showHelpDialog = false },
                title = { Text(text = stringResource(MR.strings.komga_server_settings_help_title)) },
                text = { Text(text = stringResource(MR.strings.komga_server_settings_help_content)) },
                confirmButton = {
                    TextButton(onClick = { showHelpDialog = false }) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
            )
        }
    }
}
