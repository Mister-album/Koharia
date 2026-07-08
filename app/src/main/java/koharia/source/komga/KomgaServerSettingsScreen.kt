package koharia.source.komga

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.preference.DeferredSharedPreferencesDataStore
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.ui.source.DataStoreHolder
import eu.kanade.tachiyomi.ui.source.SourcePreferencesScreen
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KomgaServerSettingsScreen(
    private val sourceId: Long,
    private val titleOverride: String? = null,
    private val isNew: Boolean = false,
) : Screen() {

    @Composable
    override fun Content() {
        var showHelpDialog by rememberSaveable { mutableStateOf(false) }
        var showUnsavedDialog by rememberSaveable { mutableStateOf(false) }
        val navigator = LocalNavigator.currentOrThrow
        val serverRemovalManager = remember { Injekt.get<KomgaServerRemovalManager>() }

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

        fun onCancel() {
            if (deferredDataStore?.hasUnsavedChanges == true) {
                showUnsavedDialog = true
            } else {
                if (isNew) {
                    serverRemovalManager.removeServer(sourceId)
                }
                navigator.pop()
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
                IconButton(onClick = {
                    deferredDataStore?.applyChanges()
                    navigator.pop()
                }) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = stringResource(MR.strings.action_save),
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
                        if (isNew) {
                            serverRemovalManager.removeServer(sourceId)
                        }
                        navigator.pop()
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
