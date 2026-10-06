package koharia.source.local

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import koharia.storage.LibraryStorageMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun LocalStorageModeOptions(
    selected: LibraryStorageMode,
    enabled: Boolean,
    onSelect: (LibraryStorageMode) -> Unit,
) {
    Column {
        PreferenceGroupHeader(title = stringResource(MR.strings.storage_add_mode))
        LibraryStorageMode.entries.forEach { mode ->
            ListItem(
                modifier = Modifier.testTag(
                    "storage-mode-${mode.name}",
                ).selectable(selected == mode, enabled = enabled, role = Role.RadioButton, onClick = {
                    onSelect(mode)
                }),
                headlineContent = {
                    Text(
                        when (mode) {
                            LibraryStorageMode.LOCAL -> stringResource(MR.strings.storage_mode_local)
                            LibraryStorageMode.WEBDAV -> "WebDAV"
                            LibraryStorageMode.SMB -> "SMB"
                        },
                    )
                },
                leadingContent = { RadioButton(selected = selected == mode, onClick = null, enabled = enabled) },
            )
        }
    }
}
