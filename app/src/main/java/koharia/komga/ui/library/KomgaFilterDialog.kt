package koharia.komga.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import tachiyomi.core.common.preference.TriState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.CollapsibleBox
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TextItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.Button
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun KomgaFilterDialog(
    onDismissRequest: () -> Unit,
    filters: FilterList,
    persistentFilteringEnabled: Boolean,
    onReset: () -> Unit,
    onFilter: () -> Unit,
    onUpdate: (FilterList) -> Unit,
    onPersistentFilteringChange: (Boolean) -> Unit,
) {
    val updateFilters = { onUpdate(filters) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        LazyColumn {
            stickyHeader {
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .padding(8.dp),
                ) {
                    TextButton(onClick = onReset) {
                        Text(
                            text = stringResource(MR.strings.action_reset),
                            style = LocalTextStyle.current.copy(
                                color = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Button(onClick = {
                        onFilter()
                        onDismissRequest()
                    }) {
                        Text(stringResource(MR.strings.action_filter))
                    }
                }
                HorizontalDivider()
            }

            items(filters) {
                FilterItem(it, updateFilters)
            }

            item {
                HorizontalDivider()
                CheckboxItem(
                    label = stringResource(MR.strings.komga_filter_persist),
                    checked = persistentFilteringEnabled,
                ) {
                    onPersistentFilteringChange(!persistentFilteringEnabled)
                }
            }
        }
    }
}

@Composable
private fun FilterItem(filter: Filter<*>, onUpdate: () -> Unit) {
    when (filter) {
        is Filter.Header -> {
            HeadingItem(komgaFilterLabel(filter.name))
        }
        is Filter.Separator -> {
            HorizontalDivider()
        }
        is Filter.CheckBox -> {
            CheckboxItem(
                label = komgaFilterLabel(filter.name),
                checked = filter.state,
            ) {
                filter.state = !filter.state
                onUpdate()
            }
        }
        is Filter.TriState -> {
            TriStateItem(
                label = komgaFilterLabel(filter.name),
                state = filter.state.toTriStateFilter(),
            ) {
                filter.state = filter.state.toTriStateFilter().next().toTriStateInt()
                onUpdate()
            }
        }
        is Filter.Text -> {
            TextItem(
                label = komgaFilterLabel(filter.name),
                value = filter.state,
            ) {
                filter.state = it
                onUpdate()
            }
        }
        is Filter.Select<*> -> {
            SelectItem(
                label = komgaFilterLabel(filter.name),
                options = filter.values.map { komgaFilterLabel(it.toString()) }.toTypedArray(),
                selectedIndex = filter.state,
            ) {
                filter.state = it
                onUpdate()
            }
        }
        is Filter.Sort -> {
            CollapsibleBox(
                heading = komgaFilterLabel(filter.name),
            ) {
                Column {
                    filter.values.mapIndexed { index, item ->
                        val sortAscending = filter.state?.ascending
                            ?.takeIf { index == filter.state?.index }
                        SortItem(
                            label = komgaFilterLabel(item),
                            sortDescending = sortAscending?.not(),
                            onClick = {
                                val ascending = if (index == filter.state?.index) {
                                    !filter.state!!.ascending
                                } else {
                                    filter.state?.ascending ?: true
                                }
                                filter.state = Filter.Sort.Selection(
                                    index = index,
                                    ascending = ascending,
                                )
                                onUpdate()
                            },
                        )
                    }
                }
            }
        }
        is Filter.Group<*> -> {
            CollapsibleBox(
                heading = komgaFilterLabel(filter.name),
            ) {
                Column {
                    filter.state
                        .filterIsInstance<Filter<*>>()
                        .map { FilterItem(filter = it, onUpdate = onUpdate) }
                }
            }
        }
    }
}

@Composable
private fun komgaFilterLabel(label: String): String {
    return when (label) {
        "Search for" -> stringResource(MR.strings.komga_filter_search_for)
        "Series" -> stringResource(MR.strings.komga_filter_series)
        "Read lists" -> stringResource(MR.strings.komga_filter_read_lists)
        "Books" -> stringResource(MR.strings.komga_filter_books)
        "Sort" -> stringResource(MR.strings.action_sort)
        "Relevance" -> stringResource(MR.strings.komga_filter_sort_relevance)
        "Alphabetically" -> stringResource(MR.strings.komga_filter_sort_alphabetically)
        "Date added" -> stringResource(MR.strings.komga_filter_sort_date_added)
        "Date updated" -> stringResource(MR.strings.komga_filter_sort_date_updated)
        "Random" -> stringResource(MR.strings.action_sort_random)
        "Unread" -> stringResource(MR.strings.unread)
        "In Progress" -> stringResource(MR.strings.komga_filter_in_progress)
        "Read" -> stringResource(MR.strings.komga_filter_read)
        "Oneshot" -> stringResource(MR.strings.komga_filter_oneshot)
        "Libraries" -> stringResource(MR.strings.komga_filter_libraries)
        "Collection" -> stringResource(MR.strings.komga_filter_collection)
        "None" -> stringResource(MR.strings.none)
        "Status" -> stringResource(MR.strings.status)
        "Ongoing" -> stringResource(MR.strings.ongoing)
        "Ended" -> stringResource(MR.strings.komga_filter_status_ended)
        "Abandoned" -> stringResource(MR.strings.komga_filter_status_abandoned)
        "Hiatus" -> stringResource(MR.strings.on_hiatus)
        "Genres" -> stringResource(MR.strings.komga_filter_genres)
        "Tags" -> stringResource(MR.strings.komga_filter_tags)
        "Publishers" -> stringResource(MR.strings.komga_filter_publishers)
        "Writer" -> stringResource(MR.strings.komga_filter_author_writer)
        "Penciller" -> stringResource(MR.strings.komga_filter_author_penciller)
        "Inker" -> stringResource(MR.strings.komga_filter_author_inker)
        "Colorist" -> stringResource(MR.strings.komga_filter_author_colorist)
        "Letterer" -> stringResource(MR.strings.komga_filter_author_letterer)
        "Cover" -> stringResource(MR.strings.komga_filter_author_cover)
        "Editor" -> stringResource(MR.strings.komga_filter_author_editor)
        "Translator" -> stringResource(MR.strings.komga_filter_author_translator)
        "Conceptor" -> stringResource(MR.strings.komga_filter_author_conceptor)
        else -> label
    }
}

private fun Int.toTriStateFilter(): TriState {
    return when (this) {
        Filter.TriState.STATE_IGNORE -> TriState.DISABLED
        Filter.TriState.STATE_INCLUDE -> TriState.ENABLED_IS
        Filter.TriState.STATE_EXCLUDE -> TriState.ENABLED_NOT
        else -> throw IllegalStateException("Unknown TriState state: $this")
    }
}

private fun TriState.toTriStateInt(): Int {
    return when (this) {
        TriState.DISABLED -> Filter.TriState.STATE_IGNORE
        TriState.ENABLED_IS -> Filter.TriState.STATE_INCLUDE
        TriState.ENABLED_NOT -> Filter.TriState.STATE_EXCLUDE
    }
}
