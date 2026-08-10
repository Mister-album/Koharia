package koharia.source.komga

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.KomgaLibraryClassificationScreen
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun KomgaContentClassificationPreferenceGroup(): Preference.PreferenceGroup {
    val navigator = LocalNavigator.currentOrThrow
    val manager = remember { Injekt.get<KomgaLibraryClassificationManager>() }
    val serverPreferences = remember { Injekt.get<KomgaServerPreferences>() }
    val enabled by manager.enabled.collectAsState()
    val activeServerId by serverPreferences.activeServerId.collectAsState()
    val profiles by remember(serverPreferences) {
        serverPreferences.profilesChanges()
    }.collectAsState(initial = serverPreferences.getProfiles())
    val libraries by remember(activeServerId) {
        manager.classificationsChanges(activeServerId)
    }.collectAsState(initial = manager.getLibraries(activeServerId))
    val hasServer = profiles.any { it.id == activeServerId }
    val comicCount = libraries.count { it.kind == KomgaLibraryKind.COMIC }
    val bookCount = libraries.count { it.kind == KomgaLibraryKind.BOOK }
    var showEnableConfirmation by remember { mutableStateOf(false) }

    if (showEnableConfirmation) {
        AlertDialog(
            onDismissRequest = { showEnableConfirmation = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        manager.enableClassification()
                        showEnableConfirmation = false
                    },
                ) {
                    Text(stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEnableConfirmation = false }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
            title = { Text(stringResource(MR.strings.komga_library_classification_confirm_title)) },
            text = { Text(stringResource(MR.strings.komga_library_classification_confirm_message)) },
        )
    }

    return Preference.PreferenceGroup(
        title = stringResource(MR.strings.komga_library_classification_group),
        preferenceItems = persistentListOf<Preference.PreferenceItem<out Any, out Any>>(
            Preference.PreferenceItem.CustomPreference(
                title = stringResource(MR.strings.komga_library_classification_enable),
            ) {
                SwitchPreferenceWidget(
                    title = stringResource(MR.strings.komga_library_classification_enable),
                    subtitle = stringResource(
                        if (hasServer) {
                            MR.strings.komga_library_classification_summary
                        } else {
                            MR.strings.komga_library_classification_no_server
                        },
                    ),
                    checked = enabled,
                    enabled = hasServer,
                    onCheckedChanged = { checked ->
                        if (checked) {
                            showEnableConfirmation = true
                        } else {
                            manager.disableClassification()
                        }
                    },
                )
            },
        ).let { items ->
            if (enabled) {
                items.add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.komga_library_classification_configure),
                        subtitle = stringResource(
                            MR.strings.komga_library_classification_counts,
                            comicCount,
                            bookCount,
                        ),
                        enabled = hasServer,
                        onClick = { navigator.push(KomgaLibraryClassificationScreen()) }.takeIf { hasServer },
                    ),
                )
            } else {
                items
            }
        },
    )
}
