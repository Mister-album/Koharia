package koharia.connection.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import koharia.connection.ConnectionIdentityChanges
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
private fun requiresReplacement(sourceId: Long, changes: ConnectionIdentityChanges): Boolean {
    val paused by changes.identityPausedIds.collectAsStateWithLifecycle()
    return sourceId in paused
}

@Composable
fun connectionSaveLabel(
    sourceId: Long,
    changes: ConnectionIdentityChanges = Injekt.get(),
    activate: Boolean = false,
): String = stringResource(
    if (!requiresReplacement(sourceId, changes)) {
        MR.strings.action_save
    } else if (activate) {
        MR.strings.connection_activate_verified
    } else {
        MR.strings.connection_reconnect_as_new
    },
)

@Composable
fun ConnectionReactivationPreference(
    sourceId: Long,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChanged: (Boolean) -> Unit,
    changes: ConnectionIdentityChanges = Injekt.get(),
) {
    if (requiresReplacement(sourceId, changes)) {
        eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget(
            title = stringResource(MR.strings.connection_activate_verified),
            subtitle = stringResource(MR.strings.connection_activate_verified_help),
            checked = checked,
            enabled = enabled,
            onCheckedChanged = onCheckedChanged,
        )
    }
}

@Composable
fun ConnectionReconnectionNotice(sourceId: Long, changes: ConnectionIdentityChanges = Injekt.get()) {
    if (requiresReplacement(sourceId, changes)) {
        Text(stringResource(MR.strings.connection_reconnect_as_new_help), Modifier.padding(16.dp))
    }
}
