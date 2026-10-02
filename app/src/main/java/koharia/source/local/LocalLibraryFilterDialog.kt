package koharia.source.local

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CollapsibleBox
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun LocalLibraryFilterDialog(
    filters: LocalLibraryFilters,
    rememberFilters: Boolean,
    onDismissRequest: () -> Unit,
    onApply: (LocalLibraryFilters, Boolean) -> Unit,
) {
    var draft by remember(filters) { mutableStateOf(filters) }
    var rememberSelection by remember(rememberFilters) { mutableStateOf(rememberFilters) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        LazyColumn {
            stickyHeader {
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .padding(8.dp),
                ) {
                    TextButton(onClick = {
                        draft = LocalLibraryFilters()
                        rememberSelection = false
                    }) {
                        Text(
                            text = stringResource(MR.strings.action_reset),
                            style = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.primary),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            onApply(draft, rememberSelection)
                            onDismissRequest()
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_filter))
                    }
                }
                HorizontalDivider()
            }

            item {
                tachiyomi.presentation.core.components.CheckboxItem(
                    label = stringResource(MR.strings.remember_filters),
                    checked = rememberSelection,
                    onClick = { rememberSelection = !rememberSelection },
                )
            }
            item {
                FilterTextField(
                    value = draft.series,
                    onValueChange = { draft = draft.copy(series = it) },
                    label = stringResource(MR.strings.local_library_filter_series),
                )
            }
            item {
                tachiyomi.presentation.core.components.CheckboxItem(
                    label = stringResource(MR.strings.local_library_folders_first),
                    checked = draft.foldersFirst,
                    onClick = { draft = draft.copy(foldersFirst = !draft.foldersFirst) },
                )
            }
            item {
                CollapsibleBox(heading = stringResource(MR.strings.action_sort)) {
                    androidx.compose.foundation.layout.Column {
                        listOf(
                            MR.strings.title,
                            MR.strings.local_library_sort_added,
                            MR.strings.local_library_sort_modified,
                        ).forEachIndexed { index, label ->
                            SortItem(
                                label = stringResource(label),
                                sortDescending = draft.descending.takeIf { draft.sort == index },
                            ) {
                                draft = draft.copy(
                                    sort = index,
                                    descending = if (draft.sort == index) !draft.descending else false,
                                )
                            }
                        }
                    }
                }
            }
            item {
                FilterTextField(
                    value = draft.format,
                    onValueChange = { draft = draft.copy(format = it) },
                    label = stringResource(MR.strings.local_library_filter_format),
                )
            }
            item {
                FilterTextField(
                    value = draft.chapter,
                    onValueChange = { draft = draft.copy(chapter = it) },
                    label = stringResource(MR.strings.local_library_filter_chapter),
                )
            }
            item {
                FilterTextField(
                    value = draft.author,
                    onValueChange = { draft = draft.copy(author = it) },
                    label = stringResource(MR.strings.author),
                )
            }
            item {
                FilterTextField(
                    value = draft.artist,
                    onValueChange = { draft = draft.copy(artist = it) },
                    label = stringResource(MR.strings.artist),
                )
            }
            item {
                FilterTextField(
                    value = draft.genre,
                    onValueChange = { draft = draft.copy(genre = it) },
                    label = stringResource(MR.strings.local_library_filter_genre),
                )
            }
        }
    }
}

@Composable
private fun FilterTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        label = { Text(text = label) },
        singleLine = true,
    )
}
