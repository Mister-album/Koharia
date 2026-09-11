package koharia.lanraragi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import koharia.lanraragi.LanraragiFilter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.CollapsibleBox
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TextItem
import tachiyomi.presentation.core.components.material.Button
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.random.Random

@Composable
internal fun LanraragiFilterSheet(
    initial: LanraragiFilter,
    downloadedOnly: Boolean,
    onDismissRequest: () -> Unit,
    onApply: (LanraragiFilter, Boolean) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var downloads by remember { mutableStateOf(downloadedOnly) }
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        LazyColumn {
            stickyHeader {
                Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                    Row(Modifier.padding(8.dp)) {
                        TextButton(onClick = {
                            draft = LanraragiFilter(query = initial.query, category = initial.category)
                            downloads = false
                        }) { Text(stringResource(MR.strings.action_reset)) }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = {
                            onApply(draft, downloads)
                            onDismissRequest()
                        }) { Text(stringResource(MR.strings.action_filter)) }
                    }
                    HorizontalDivider()
                }
            }
            item {
                CheckboxItem(stringResource(MR.strings.lanraragi_group_tanks), draft.grouped) {
                    draft = draft.copy(grouped = !draft.grouped)
                }
                CheckboxItem(stringResource(MR.strings.lanraragi_downloaded), downloads) { downloads = !downloads }
                HorizontalDivider()
                SelectItem(
                    label = stringResource(MR.strings.lanraragi_read_status),
                    options = arrayOf(
                        stringResource(MR.strings.lanraragi_all),
                        stringResource(MR.strings.lanraragi_unread),
                        stringResource(MR.strings.lanraragi_in_progress),
                        stringResource(MR.strings.lanraragi_completed),
                    ),
                    selectedIndex = draft.readStatus,
                ) { draft = draft.copy(readStatus = it) }
                CheckboxItem(stringResource(MR.strings.lanraragi_new_only), draft.newOnly) {
                    draft = draft.copy(newOnly = !draft.newOnly)
                }
                CheckboxItem(stringResource(MR.strings.lanraragi_untagged), draft.untaggedOnly) {
                    draft = draft.copy(untaggedOnly = !draft.untaggedOnly)
                }
                TextItem(stringResource(MR.strings.lanraragi_tag), draft.tag.orEmpty()) {
                    draft = draft.copy(tag = it.trim().takeIf(String::isNotEmpty))
                }
                CollapsibleBox(heading = stringResource(MR.strings.action_sort)) {
                    Column {
                        listOf(
                            MR.strings.lanraragi_title,
                            MR.strings.lanraragi_added,
                            MR.strings.lanraragi_last_read,
                            MR.strings.lanraragi_random,
                        ).forEachIndexed { index, label ->
                            SortItem(
                                label = stringResource(label),
                                sortDescending = draft.descending.takeIf { draft.sort == index },
                            ) {
                                draft = draft.copy(
                                    sort = index,
                                    descending = if (draft.sort == index) !draft.descending else draft.descending,
                                    randomSeed = Random.nextInt(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
