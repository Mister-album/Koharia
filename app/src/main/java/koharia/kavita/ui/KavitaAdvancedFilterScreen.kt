package koharia.kavita.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import koharia.kavita.KavitaFilter
import koharia.kavita.KavitaFilterStatement
import koharia.kavita.KavitaSort
import koharia.kavita.longValue
import koharia.kavita.textValue
import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KavitaAdvancedFilterScreen(
    private val sourceId: Long,
    private val initial: String? = null,
    private val filterId: Long = 0,
    private val filterName: String = "",
    private val contentScope: koharia.connection.LibraryContentScope = koharia.connection.LibraryContentScope.ALL,
) : Screen() {
    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? KavitaSource
        if (source == null || !source.hasValidConnection()) {
            EmptyScreen(stringRes = MR.strings.connection_unavailable)
            return
        }
        val session = remember(source) { source.session() }
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var filter by remember {
            mutableStateOf(initial?.let { session.api.decode<KavitaFilter>(it) } ?: KavitaFilter())
        }
        var editing by remember { mutableStateOf<Int?>(null) }
        var name by remember { mutableStateOf(filterName) }
        var saving by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf(false) }
        var renaming by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        fun run(block: suspend () -> Unit) {
            if (busy) return
            busy = true
            scope.launch {
                try {
                    block()
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = context.kavitaError(failure)
                } finally {
                    busy = false
                }
            }
        }
        val fields = availableKavitaFilterFields(source)
        Scaffold(topBar = {
            AppBar(title = stringResource(MR.strings.kavita_advanced_filter), navigateUp = {
                navigator.pop()
            }, actions = {
                TextButton(enabled = !busy, onClick = {
                    navigator.push(
                        KavitaLibraryScreen(
                            sourceId,
                            null,
                            true,
                            session.api.json.encodeToString(filter),
                            contentScope = contentScope,
                        ),
                    )
                }) { Text(stringResource(MR.strings.action_filter)) }
            })
        }) { padding ->
            LazyColumn(Modifier.padding(padding)) {
                item {
                    SelectItem(
                        stringResource(MR.strings.kavita_filter_match),
                        arrayOf(
                            stringResource(MR.strings.kavita_filter_any),
                            stringResource(MR.strings.kavita_filter_all),
                        ),
                        filter.combination,
                    ) { filter = filter.copy(combination = it) }
                }
                itemsIndexed(filter.statements) { index, statement ->
                    Row {
                        TextButton(
                            onClick = { editing = index },
                            enabled = fields.any { it.id == statement.field },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                fields.firstOrNull { it.id == statement.field }?.let { stringResource(it.label) }
                                    ?: stringResource(MR.strings.kavita_preserved_filter),
                            )
                            Text("  ${comparisonLabel(statement.comparison)}  ${statement.value}")
                        }
                        TextButton(onClick = {
                            filter =
                                filter.copy(statements = filter.statements.filterIndexed { i, _ -> i != index })
                        }) {
                            Text(stringResource(MR.strings.action_remove))
                        }
                    }
                }
                item {
                    TextButton(onClick = {
                        editing = filter.statements.size
                    }) { Text(stringResource(MR.strings.action_add)) }
                    val sorts = listOf(
                        1 to MR.strings.kavita_sort_name,
                        2 to MR.strings.kavita_sort_created,
                        3 to MR.strings.kavita_sort_updated,
                        4 to MR.strings.kavita_sort_chapter_added,
                        6 to MR.strings.kavita_release_year,
                        7 to MR.strings.kavita_read_progress,
                        10 to MR.strings.kavita_rating,
                        5 to MR.strings.kavita_read_hours,
                        9 to MR.strings.kavita_random,
                        11 to MR.strings.kavita_unread_chapters,
                    )
                    SelectItem(
                        stringResource(MR.strings.action_sort),
                        sorts.map {
                            stringResource(it.second)
                        }.toTypedArray(),
                        sorts.indexOfFirst { it.first == filter.sortOptions.sortField }.coerceAtLeast(0),
                    ) {
                        filter = filter.copy(sortOptions = filter.sortOptions.copy(sortField = sorts[it].first))
                    }
                    CheckboxItem(stringResource(MR.strings.kavita_descending), !filter.sortOptions.isAscending) {
                        filter =
                            filter.copy(
                                sortOptions = filter.sortOptions.copy(isAscending = !filter.sortOptions.isAscending),
                            )
                    }
                    OutlinedTextField(filter.limitTo.toString(), { value ->
                        value.toIntOrNull()?.takeIf { it >= 0 }?.let { filter = filter.copy(limitTo = it) }
                    }, label = {
                        Text(stringResource(MR.strings.kavita_filter_limit))
                    }, modifier = Modifier.padding(16.dp))
                    if (source.preferences.capabilities.writable) {
                        TextButton(enabled = !busy, onClick = {
                            saving = true
                        }) { Text(stringResource(MR.strings.kavita_save_filter)) }
                        if (filterId > 0) {
                            if (source.preferences.capabilities.version >= koharia.kavita.KavitaVersion(0, 9, 1)) {
                                TextButton(enabled = !busy, onClick = { renaming = true }) {
                                    Text(stringResource(MR.strings.kavita_rename_filter))
                                }
                            }
                            TextButton(enabled = !busy, onClick = {
                                run {
                                    withContext(Dispatchers.IO) { session.organization.addDashboardFilter(filterId) }
                                    navigator.push(KavitaDashboardScreen(sourceId))
                                }
                            }) { Text(stringResource(MR.strings.kavita_add_dashboard)) }
                            TextButton(enabled = !busy, onClick = { deleting = true }) {
                                Text(stringResource(MR.strings.action_delete))
                            }
                        }
                    }
                }
            }
        }
        editing?.let { index ->
            KavitaStatementEditor(source, filter.statements.getOrNull(index), onDismiss = {
                editing = null
            }) { statement ->
                filter = filter.copy(
                    statements = filter.statements.toMutableList().apply {
                        if (index == size) add(statement) else set(index, statement)
                    },
                )
                editing = null
            }
        }
        if (saving || deleting || renaming) {
            AlertDialog(
                onDismissRequest = {
                    if (!busy) {
                        saving = false
                        deleting = false
                        renaming = false
                        name = filterName
                    }
                },
                title = { Text(stringResource(MR.strings.kavita_smart_filters)) },
                text = {
                    if (deleting) {
                        Text(stringResource(MR.strings.kavita_delete_filter_confirm))
                    } else {
                        OutlinedTextField(name, {
                            name = it
                        }, enabled = !busy && (filterId == 0L || renaming), label = {
                            Text(stringResource(MR.strings.kavita_name))
                        })
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy && (deleting || name.isNotBlank()), onClick = {
                        run {
                            withContext(Dispatchers.IO) {
                                if (deleting) {
                                    session.organization.deleteFilter(filterId)
                                } else if (renaming) {
                                    session.organization.renameFilter(filterId, name)
                                } else {
                                    session.organization.saveFilter(filterId, name, filter)
                                }
                            }
                            navigator.pop()
                        }
                    }) { Text(stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    TextButton(enabled = !busy, onClick = {
                        saving = false
                        deleting = false
                        renaming = false
                        name = filterName
                    }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
        KavitaErrorDialog(error) { error = null }
    }
}

internal data class KavitaFilterField(
    val id: Int,
    val label: dev.icerock.moko.resources.StringResource,
    val comparisons: List<Int>,
    val path: String? = null,
)

@Composable
internal fun availableKavitaFilterFields(source: KavitaSource): List<KavitaFilterField> {
    var plus by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source) {
        try {
            plus = withContext(Dispatchers.IO) {
                source.session().catalog.resource("License/valid-license").toString() == "true"
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
        }
    }
    val maximum = if (source.preferences.capabilities.version >= koharia.kavita.KavitaVersion(0, 9, 1)) 34 else 28
    return kavitaFilterFields().filter { it.id <= maximum && (it.id != 28 || plus) }
}

internal fun kavitaFilterFields(): List<KavitaFilterField> {
    val text = listOf(7, 0, 9, 10, 11)
    val number = listOf(0, 1, 2, 3, 4, 9)
    val choice = listOf(0, 5, 8, 9)
    return listOf(
        KavitaFilterField(1, MR.strings.kavita_sort_name, text),
        KavitaFilterField(0, MR.strings.kavita_summary, text),
        KavitaFilterField(6, MR.strings.kavita_tags, choice, "Metadata/tags"),
        KavitaFilterField(18, MR.strings.genres, choice, "Metadata/genres"),
        KavitaFilterField(17, MR.strings.author, choice, "Metadata/people-by-role?role=3"),
        KavitaFilterField(7, MR.strings.kavita_collections, choice, "Collection"),
        KavitaFilterField(19, MR.strings.kavita_libraries, choice, "Library/libraries"),
        KavitaFilterField(21, MR.strings.kavita_format, choice),
        KavitaFilterField(2, MR.strings.status, choice, "Metadata/publication-status"),
        KavitaFilterField(4, MR.strings.kavita_age_rating, choice, "Metadata/age-ratings"),
        KavitaFilterField(3, MR.strings.kavita_language, choice, "Metadata/languages"),
        KavitaFilterField(5, MR.strings.kavita_rating, number),
        KavitaFilterField(20, MR.strings.kavita_read_progress, number),
        KavitaFilterField(22, MR.strings.kavita_release_year, number),
        KavitaFilterField(26, MR.strings.kavita_want_to_read, listOf(0)),
        KavitaFilterField(8, MR.strings.kavita_translators, choice, "Metadata/people-by-role?role=12"),
        KavitaFilterField(9, MR.strings.kavita_characters, choice, "Metadata/people-by-role?role=11"),
        KavitaFilterField(10, MR.strings.kavita_publisher, choice, "Metadata/people-by-role?role=10"),
        KavitaFilterField(11, MR.strings.kavita_editor, choice, "Metadata/people-by-role?role=9"),
        KavitaFilterField(12, MR.strings.kavita_cover_artist, choice, "Metadata/people-by-role?role=8"),
        KavitaFilterField(13, MR.strings.kavita_letterer, choice, "Metadata/people-by-role?role=7"),
        KavitaFilterField(14, MR.strings.kavita_colorist, choice, "Metadata/people-by-role?role=6"),
        KavitaFilterField(15, MR.strings.kavita_inker, choice, "Metadata/people-by-role?role=5"),
        KavitaFilterField(16, MR.strings.kavita_penciller, choice, "Metadata/people-by-role?role=4"),
        KavitaFilterField(23, MR.strings.kavita_read_hours, number),
        KavitaFilterField(24, MR.strings.kavita_series_path, text),
        KavitaFilterField(25, MR.strings.kavita_file_path, text),
        KavitaFilterField(27, MR.strings.kavita_reading_date, listOf(12, 13)),
        KavitaFilterField(28, MR.strings.kavita_average_rating, number),
        KavitaFilterField(29, MR.strings.kavita_imprint, choice, "Metadata/people-by-role?role=13"),
        KavitaFilterField(30, MR.strings.kavita_team, choice, "Metadata/people-by-role?role=14"),
        KavitaFilterField(31, MR.strings.kavita_location, choice, "Metadata/people-by-role?role=15"),
        KavitaFilterField(32, MR.strings.kavita_read_days, listOf(0, 1, 2, 3, 4)),
        KavitaFilterField(33, MR.strings.kavita_file_size, listOf(0, 1, 2, 3, 4)),
        KavitaFilterField(34, MR.strings.kavita_collapse_related, listOf(0)),

    )
}

@Composable
private fun comparisonLabel(value: Int): String = when (value) {
    0 -> "="
    1 -> ">"
    2 -> "≥"
    3 -> "<"
    4 -> "≤"
    5, 7 -> stringResource(MR.strings.kavita_contains)
    8 -> stringResource(MR.strings.kavita_not_contains)
    9 -> "≠"
    10 -> stringResource(MR.strings.kavita_begins_with)
    11 -> stringResource(MR.strings.kavita_ends_with)
    12 -> stringResource(MR.strings.kavita_before)
    13 -> stringResource(MR.strings.kavita_after)
    else -> stringResource(MR.strings.kavita_preserved_filter)
}

@Composable
private fun KavitaStatementEditor(
    source: KavitaSource,
    current: KavitaFilterStatement?,
    onDismiss: () -> Unit,
    onSave: (KavitaFilterStatement) -> Unit,
) {
    val available = availableKavitaFilterFields(source)
    val currentField = kavitaFilterFields().firstOrNull { it.id == current?.field }
    val fields = if (currentField != null && currentField !in available) listOf(currentField) + available else available
    var field by remember {
        mutableStateOf(kavitaFilterFields().firstOrNull { it.id == current?.field } ?: fields.first())
    }
    var comparison by remember { mutableStateOf(current?.comparison ?: field.comparisons.first()) }
    var value by remember { mutableStateOf(current?.value.orEmpty()) }
    var choiceQuery by remember { mutableStateOf("") }
    var choices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(field.id) {
        choices = emptyList()
        choiceQuery = ""
        error = null
        try {
            choices = when (field.id) {
                21 -> listOf(
                    "0" to context.stringResource(MR.strings.kavita_images),
                    "1" to "CBZ/CBR/7z",
                    "3" to "EPUB",
                    "4" to "PDF",
                )
                26, 34 -> listOf(
                    "true" to context.stringResource(MR.strings.kavita_yes),
                    "false" to context.stringResource(MR.strings.kavita_no),
                )
                else -> field.path?.let { path ->
                    withContext(Dispatchers.IO) {
                        (source.session().catalog.resource(path) as JsonArray).mapNotNull(::kavitaFilterChoice)
                    }
                }.orEmpty()
            }
            if (value.isBlank() && choices.isNotEmpty()) value = choices.first().first
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = context.kavitaError(failure)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.kavita_advanced_filter)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SelectItem(
                    stringResource(MR.strings.kavita_filter_field),
                    fields.map {
                        stringResource(it.label)
                    }.toTypedArray(),
                    fields.indexOf(field).coerceAtLeast(0),
                ) {
                    field = fields[it]
                    comparison = field.comparisons.first()
                    value = ""
                }
                SelectItem(
                    stringResource(MR.strings.kavita_filter_comparison),
                    field.comparisons.map { comparisonLabel(it) }.toTypedArray(),
                    field.comparisons.indexOf(comparison).coerceAtLeast(0),
                ) { comparison = field.comparisons[it] }
                if (field.path != null || field.id in listOf(21, 26, 34)) {
                    if (choices.isNotEmpty()) {
                        if (comparison in listOf(5, 8) && field.id !in listOf(26, 34)) {
                            OutlinedTextField(
                                choiceQuery,
                                { choiceQuery = it },
                                label = { Text(stringResource(MR.strings.action_search)) },
                            )
                            choices.filter {
                                it.second.contains(choiceQuery, ignoreCase = true)
                            }.forEach { (id, label) ->
                                val selected = value.split(',').filter(String::isNotBlank).toSet()
                                CheckboxItem(label, id in selected) {
                                    value = (if (id in selected) selected - id else selected + id).joinToString(",")
                                }
                            }
                        } else {
                            val offered = if (value.isNotBlank() && choices.none { it.first == value }) {
                                listOf(value to value) + choices
                            } else {
                                choices
                            }
                            SelectItem(
                                stringResource(MR.strings.kavita_filter_value),
                                offered.map { it.second }.toTypedArray(),
                                offered.indexOfFirst { it.first == value }.coerceAtLeast(0),
                            ) {
                                value = offered[it].first
                            }
                        }
                    } else {
                        Text(error ?: stringResource(MR.strings.no_results_found))
                    }
                } else {
                    OutlinedTextField(value, {
                        value = it
                    }, label = { Text(stringResource(MR.strings.kavita_filter_value)) })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = validKavitaFilterValue(field.id, value) && comparison in field.comparisons, onClick = {
                onSave(KavitaFilterStatement(field.id, comparison, value))
            }) { Text(stringResource(MR.strings.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}
