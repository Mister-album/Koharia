package koharia.source.local

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun LocalLibraryOrganizationModePicker(
    selectedMode: LocalLibraryOrganizationMode?,
    enabled: Boolean,
    onSelect: (LocalLibraryOrganizationMode) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LocalLibraryOrganizationMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = selectedMode == mode,
                    enabled = enabled,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, LocalLibraryOrganizationMode.entries.size),
                ) {
                    Text(organizationModeLabel(mode))
                }
            }
        }
        Text(
            text = stringResource(
                when (selectedMode) {
                    LocalLibraryOrganizationMode.SERIES -> MR.strings.local_library_mode_series_summary
                    LocalLibraryOrganizationMode.INDIVIDUAL_FILES -> MR.strings.local_library_mode_individual_summary
                    null -> MR.strings.local_library_mode_choice_hint
                },
            ),
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
