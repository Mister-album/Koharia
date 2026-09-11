package koharia.lanraragi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import koharia.domain.lanraragi.LanraragiEntry
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun LanraragiCategoryTabs(
    categories: List<LanraragiEntry>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    if (categories.isEmpty()) return
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "all") {
            FilterChip(selected = selectedId == null, onClick = {
                onSelect(null)
            }, label = { Text(stringResource(MR.strings.all)) })
        }
        items(categories, key = { it.id }) { category ->
            FilterChip(selected = selectedId == category.id, onClick = {
                onSelect(category.id)
            }, label = { Text(category.title) })
        }
    }
}
