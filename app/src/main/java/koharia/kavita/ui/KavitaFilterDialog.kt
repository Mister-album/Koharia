package koharia.kavita.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import koharia.kavita.KavitaFilter
import koharia.kavita.KavitaFilterStatement
import koharia.kavita.KavitaLibrary
import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import tachiyomi.core.common.preference.TriState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.CollapsibleBox
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.TextItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.Button
import tachiyomi.presentation.core.i18n.stringResource

internal fun KavitaFilter.replaceField(field: Int, values: List<KavitaFilterStatement>) =
    copy(statements = statements.filterNot { it.field == field } + values)

internal fun KavitaFilter.choiceState(field: Int, value: String): TriState = when {
    statements.any {
        it.field == field && it.comparison in listOf(8, 9) && value in it.value.split(',')
    } -> TriState.ENABLED_NOT
    statements.any {
        it.field == field && it.comparison in listOf(0, 5) && value in it.value.split(',')
    } -> TriState.ENABLED_IS
    else -> TriState.DISABLED
}

internal fun KavitaFilter.toggleChoice(field: Int, value: String): KavitaFilter {
    val included = statements.filter { it.field == field && it.comparison in listOf(0, 5) }
        .flatMap { it.value.split(',') }.toMutableSet()
    val excluded = statements.filter { it.field == field && it.comparison in listOf(8, 9) }
        .flatMap { it.value.split(',') }.toMutableSet()
    val next = choiceState(field, value).next()
    included.remove(value)
    excluded.remove(value)
    if (next == TriState.ENABLED_IS) included.add(value)
    if (next == TriState.ENABLED_NOT) excluded.add(value)
    return replaceField(
        field,
        buildList {
            if (included.isNotEmpty()) add(KavitaFilterStatement(field, 5, included.sorted().joinToString(",")))
            if (excluded.isNotEmpty()) add(KavitaFilterStatement(field, 8, excluded.sorted().joinToString(",")))
        },
    )
}

@Composable
internal fun KavitaFilterDialog(
    source: KavitaSource,
    initial: KavitaFilter,
    libraries: List<KavitaLibrary>,
    cachedOnly: Boolean,
    onDismissRequest: () -> Unit,
    onApply: (KavitaFilter) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    val commonFields = listOf(19, 21, 2, 18, 6, 7, 3, 4, 26)
    val fields = if (cachedOnly) {
        emptyList()
    } else {
        commonFields.mapNotNull { id ->
            kavitaFilterFields().firstOrNull { it.id == id }
        }
    }
    // Imported OR expressions cannot be represented by independent include/exclude groups.
    val importedAny = draft.combination == 0 && draft.statements.size > 1
    val sorts = listOf(
        1 to MR.strings.kavita_sort_name, 2 to MR.strings.kavita_sort_created,
        3 to MR.strings.kavita_sort_updated, 4 to MR.strings.kavita_sort_chapter_added,
        6 to MR.strings.kavita_release_year, 7 to MR.strings.kavita_read_progress,
        10 to MR.strings.kavita_rating, 5 to MR.strings.kavita_read_hours,
        9 to MR.strings.kavita_random, 11 to MR.strings.kavita_unread_chapters,
    )
    AdaptiveSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxHeight(0.75f),
        forceBottomSheet = true,
    ) {
        LazyColumn {
            stickyHeader {
                Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                    Row(Modifier.padding(8.dp)) {
                        TextButton(onClick = {
                            draft = KavitaFilter()
                        }) { Text(stringResource(MR.strings.action_reset)) }
                        Spacer(Modifier.weight(1f))
                        Button(
                            enabled = draft.statements.all { validKavitaFilterValue(it.field, it.value) },
                            onClick = {
                                onApply(draft)
                                onDismissRequest()
                            },
                        ) { Text(stringResource(MR.strings.action_filter)) }
                    }
                    HorizontalDivider()
                }
            }
            item {
                HeadingItem(stringResource(MR.strings.action_sort))
                SelectItem(
                    label = stringResource(MR.strings.action_sort),
                    options = sorts.map { stringResource(it.second) }.toTypedArray(),
                    selectedIndex = sorts.indexOfFirst { it.first == draft.sortOptions.sortField }.coerceAtLeast(0),
                ) { index ->
                    draft = draft.copy(sortOptions = draft.sortOptions.copy(sortField = sorts[index].first))
                }
                CheckboxItem(stringResource(MR.strings.kavita_descending), !draft.sortOptions.isAscending) {
                    draft =
                        draft.copy(sortOptions = draft.sortOptions.copy(isAscending = !draft.sortOptions.isAscending))
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                HeadingItem(stringResource(MR.strings.action_filter))
                if (!importedAny && draft.statements.any { it.field !in commonFields }) {
                    Text(
                        stringResource(MR.strings.kavita_filter_hidden_conditions),
                        Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            if (cachedOnly || importedAny) {
                item {
                    val message = if (cachedOnly) {
                        MR.strings.kavita_offline_scope
                    } else {
                        MR.strings.kavita_filter_imported_reset
                    }
                    Text(stringResource(message), Modifier.padding(24.dp))
                }
            } else {
                items(fields, key = { it.id }) { field ->
                    CollapsibleBox(stringResource(field.label)) {
                        KavitaFilterGroup(source, field, libraries, draft) { draft = it }
                    }
                }
            }
        }
    }
}

@Composable
private fun KavitaFilterGroup(
    source: KavitaSource,
    field: KavitaFilterField,
    libraries: List<KavitaLibrary>,
    draft: KavitaFilter,
    onChange: (KavitaFilter) -> Unit,
) {
    val values = draft.statements.filter { it.field == field.id }
    val context = LocalContext.current
    var choices by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(source.instanceKey, field.id, retry) {
        error = null
        try {
            choices = when (field.id) {
                19 -> libraries.map { it.id.toString() to it.name }
                21 -> listOf("0" to "Images", "1" to "CBZ/CBR/7z", "3" to "EPUB", "4" to "PDF")
                else -> field.path?.let { path ->
                    withContext(Dispatchers.IO) {
                        (source.session().catalog.resource(path) as JsonArray).mapNotNull(::kavitaFilterChoice)
                    }
                }.orEmpty()
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = context.kavitaError(failure)
        }
    }
    Column {
        when {
            field.id == 26 -> SelectItem(
                stringResource(field.label),
                arrayOf(
                    stringResource(MR.strings.all),
                    stringResource(MR.strings.kavita_yes),
                    stringResource(MR.strings.kavita_no),
                ),
                when (values.firstOrNull()?.value) {
                    "true" -> 1
                    "false" -> 2
                    else -> 0
                },
            ) { index ->
                onChange(
                    draft.replaceField(
                        field.id,
                        if (index == 0) {
                            emptyList()
                        } else {
                            listOf(
                                KavitaFilterStatement(field.id, 0, (index == 1).toString()),
                            )
                        },
                    ),
                )
            }
            field.path != null || field.id == 21 -> {
                TextItem(stringResource(MR.strings.action_search), query) { query = it }
                if (choices == null && error == null) Text(stringResource(MR.strings.loading), Modifier.padding(24.dp))
                error?.let {
                    Text(it, Modifier.padding(24.dp))
                    TextButton(onClick = { retry++ }) { Text(stringResource(MR.strings.action_retry)) }
                }
                choices.orEmpty().filter { it.second.contains(query, true) }.forEach { (id, label) ->
                    TriStateItem(
                        label = if (field.id == 21 && id == "0") stringResource(MR.strings.kavita_images) else label,
                        state = draft.choiceState(field.id, id),
                    ) { onChange(draft.toggleChoice(field.id, id)) }
                }
                if (choices?.isEmpty() == true) {
                    Text(stringResource(MR.strings.no_results_found), Modifier.padding(24.dp))
                }
            }
        }
        if (values.isNotEmpty()) {
            TextButton(onClick = { onChange(draft.replaceField(field.id, emptyList())) }) {
                Text(stringResource(MR.strings.action_reset))
            }
        }
    }
}
