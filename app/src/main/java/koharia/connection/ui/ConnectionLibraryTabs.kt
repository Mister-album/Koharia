package koharia.connection.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun <T> ConnectionLibraryTabs(
    entries: List<T>,
    key: (T) -> Any,
    label: (T) -> String,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    allSelected: Boolean = false,
    onSelectAll: (() -> Unit)? = null,
) {
    if (entries.isEmpty()) return
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onSelectAll != null) {
            item(key = "all") {
                FilterChip(
                    selected = allSelected,
                    onClick = onSelectAll,
                    label = { Text(stringResource(MR.strings.all)) },
                )
            }
        }
        items(entries, key = key) { entry ->
            FilterChip(
                selected = isSelected(entry),
                onClick = { onSelect(entry) },
                label = { Text(label(entry)) },
            )
        }
    }
}
