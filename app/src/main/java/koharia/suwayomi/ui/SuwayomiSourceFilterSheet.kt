package koharia.suwayomi.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.RadioMenuItem
import koharia.suwayomi.CheckBoxState
import koharia.suwayomi.GroupState
import koharia.suwayomi.SelectState
import koharia.suwayomi.SortState
import koharia.suwayomi.SuwayomiBrowseFilters
import koharia.suwayomi.SuwayomiBrowseSort
import koharia.suwayomi.SuwayomiFilterState
import koharia.suwayomi.SuwayomiSourceFilter
import koharia.suwayomi.TextState
import koharia.suwayomi.TriStateState
import tachiyomi.core.common.preference.TriState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.CollapsibleBox
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.Button
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

/** Everything the filter sheet works on: the source's own filters plus Koharia's narrowing. */
data class SuwayomiFilterDraft(
    val sourceFilters: List<SuwayomiFilterState>,
    val browseFilters: SuwayomiBrowseFilters,
    val sort: SuwayomiBrowseSort,
)

/**
 * The source filter workspace. Mirrors the server client's filter screen — a sticky Reset/Filter bar
 * over the source's own filter list — and adds Koharia's narrowing options below it.
 */
@Composable
internal fun SuwayomiSourceFilterSheet(
    filters: List<SuwayomiFilterState>,
    browseFilters: SuwayomiBrowseFilters,
    sort: SuwayomiBrowseSort,
    filtersNeedSearch: Boolean,
    onDismissRequest: () -> Unit,
    onApply: (SuwayomiFilterDraft) -> Unit,
    onReset: () -> Unit,
) {
    var draft by remember(filters) {
        mutableStateOf(SuwayomiFilterDraft(filters, browseFilters, sort))
    }
    // Bumping the revision rebuilds the rows so an edited field cannot keep its old local state.
    var revision by remember { mutableIntStateOf(0) }
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    draft = SuwayomiFilterDraft(
                        sourceFilters = filters.map { it.reset() },
                        browseFilters = SuwayomiBrowseFilters.NONE,
                        sort = sort,
                    )
                    revision++
                    onReset()
                }) {
                    Text(
                        text = stringResource(MR.strings.action_reset),
                        style = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.primary),
                    )
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onApply(draft) }) {
                    Text(stringResource(MR.strings.action_filter))
                }
            }
            HorizontalDivider()
            if (filtersNeedSearch) {
                Text(
                    text = stringResource(MR.strings.suwayomi_filters_switch_to_search),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp)
                        .secondaryItemAlpha(),
                )
            }
            Column(
                Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (draft.sourceFilters.isNotEmpty()) {
                    SuwayomiFilterSectionHeader(stringResource(MR.strings.suwayomi_filters_source_section))
                }
                draft.sourceFilters.forEachIndexed { index, state ->
                    SuwayomiFilterItem(
                        key = "$revision-$index",
                        state = state,
                        onChange = { updated ->
                            draft = draft.copy(
                                sourceFilters = draft.sourceFilters.toMutableList().also { it[index] = updated },
                            )
                        },
                    )
                }
                SuwayomiLocalFilters(
                    browseFilters = draft.browseFilters,
                    sort = draft.sort,
                    onBrowseFiltersChange = { draft = draft.copy(browseFilters = it) },
                    onSortChange = { draft = draft.copy(sort = it) },
                )
                Spacer(Modifier.padding(bottom = MaterialTheme.padding.medium))
            }
        }
    }
}

@Composable
private fun SuwayomiFilterSectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = MaterialTheme.padding.medium,
            end = MaterialTheme.padding.medium,
            top = MaterialTheme.padding.medium,
            bottom = MaterialTheme.padding.small,
        ),
    )
}

/** Koharia's own narrowing, kept beside the source filters so both apply to the same listing. */
@Composable
private fun SuwayomiLocalFilters(
    browseFilters: SuwayomiBrowseFilters,
    sort: SuwayomiBrowseSort,
    onBrowseFiltersChange: (SuwayomiBrowseFilters) -> Unit,
    onSortChange: (SuwayomiBrowseSort) -> Unit,
) {
    HorizontalDivider(Modifier.padding(top = MaterialTheme.padding.medium))
    SuwayomiFilterSectionHeader(stringResource(MR.strings.suwayomi_filters_local_section))
    CheckboxItem(
        label = stringResource(MR.strings.suwayomi_filters_hide_in_library),
        checked = browseFilters.hideInLibrary,
    ) {
        onBrowseFiltersChange(browseFilters.copy(hideInLibrary = !browseFilters.hideInLibrary))
    }
    SuwayomiFilterSectionHeader(stringResource(MR.strings.suwayomi_filters_sort_section))
    listOf(
        SuwayomiBrowseSort.SOURCE to MR.strings.suwayomi_filters_sort_source,
        SuwayomiBrowseSort.TITLE_ASCENDING to MR.strings.suwayomi_filters_sort_title_ascending,
        SuwayomiBrowseSort.TITLE_DESCENDING to MR.strings.suwayomi_filters_sort_title_descending,
    ).forEach { (value, label) ->
        RadioItem(
            label = stringResource(label),
            selected = sort == value,
            onClick = { onSortChange(value) },
        )
    }
}

@Composable
private fun SuwayomiFilterItem(
    key: String,
    state: SuwayomiFilterState,
    onChange: (SuwayomiFilterState) -> Unit,
) {
    when (state) {
        is SelectState -> SelectItem(
            label = state.filter.name,
            options = state.filter.values.toTypedArray(),
            selectedIndex = state.index.coerceIn(0, (state.filter.values.size - 1).coerceAtLeast(0)),
        ) { onChange(state.copy(index = it)) }
        is TextState -> TextFilterItem(key, state, onChange)
        is CheckBoxState -> CheckboxItem(state.filter.name, state.checked) {
            onChange(state.copy(checked = !state.checked))
        }
        is TriStateState -> TriStateItem(
            label = state.filter.name,
            state = state.state.toTriState(),
            onClick = { next -> onChange(state.copy(state = next.toServerState())) },
        )
        is SortState -> SortFilterItem(state, onChange)
        is GroupState -> FilterGroupItem(state, onChange)
        else -> SuwayomiFilterLabel(state.filter)
    }
}

@Composable
private fun TextFilterItem(
    key: String,
    state: TextState,
    onChange: (TextState) -> Unit,
) {
    var text by remember(key) { mutableStateOf(state.text) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(state.copy(text = it))
        },
        label = { Text(state.filter.name) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = MaterialTheme.padding.medium,
            vertical = MaterialTheme.padding.small,
        ),
    )
}

/** Sort is grouped like the server client's expansion tile: one selection plus its own direction. */
@Composable
private fun SortFilterItem(
    state: SortState,
    onChange: (SortState) -> Unit,
) {
    CollapsibleBox(heading = state.filter.name, initiallyExpanded = state.diverged) {
        Column(Modifier.fillMaxWidth()) {
            state.filter.values.forEachIndexed { index, value ->
                RadioMenuItem(text = { Text(value) }, isChecked = state.index == index) {
                    onChange(state.copy(index = index))
                }
            }
            CheckboxItem(
                label = stringResource(MR.strings.suwayomi_descending),
                checked = !state.ascending,
            ) {
                onChange(state.copy(ascending = !state.ascending))
            }
        }
    }
}

@Composable
private fun FilterGroupItem(
    state: GroupState,
    onChange: (GroupState) -> Unit,
) {
    CollapsibleBox(heading = state.filter.name, initiallyExpanded = state.diverged) {
        Column(Modifier.fillMaxWidth()) {
            state.children.forEachIndexed { index, child ->
                SuwayomiFilterItem(
                    key = "${state.filter.name}-$index",
                    state = child,
                    onChange = { updated ->
                        onChange(state.copy(children = state.children.toMutableList().also { it[index] = updated }))
                    },
                )
            }
        }
    }
}

/** A header, separator or unrecognized filter only contributes its label. */
@Composable
internal fun SuwayomiFilterLabel(filter: SuwayomiSourceFilter) {
    Text(
        text = filter.name,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small)
            .secondaryItemAlpha(),
    )
}

private fun SuwayomiFilterState.reset(): SuwayomiFilterState = when (this) {
    is GroupState -> GroupState(filter, children.map { it.reset() })
    is SelectState -> SelectState(filter)
    is TextState -> TextState(filter)
    is CheckBoxState -> CheckBoxState(filter)
    is TriStateState -> TriStateState(filter)
    is SortState -> SortState(filter)
    else -> this
}

private fun String.toTriState(): TriState = when (this) {
    "INCLUDE" -> TriState.ENABLED_IS
    "EXCLUDE" -> TriState.ENABLED_NOT
    else -> TriState.DISABLED
}

private fun TriState.toServerState(): String = when (this) {
    TriState.ENABLED_IS -> "INCLUDE"
    TriState.ENABLED_NOT -> "EXCLUDE"
    TriState.DISABLED -> "IGNORE"
}
