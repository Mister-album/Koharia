package koharia.lanraragi.ui

import androidx.compose.runtime.Composable
import koharia.connection.ui.ConnectionLibraryTabs
import koharia.domain.lanraragi.LanraragiEntry

@Composable
internal fun LanraragiCategoryTabs(
    categories: List<LanraragiEntry>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    ConnectionLibraryTabs(
        entries = categories,
        key = { it.id },
        label = { it.title },
        isSelected = { it.id == selectedId },
        onSelect = { onSelect(it.id) },
        allSelected = selectedId == null,
        onSelectAll = { onSelect(null) },
    )
}
