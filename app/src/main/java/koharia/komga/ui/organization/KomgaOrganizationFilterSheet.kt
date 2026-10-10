package koharia.komga.ui.organization

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AdaptiveSheet
import koharia.komga.api.KomgaOrganizationKind
import koharia.komga.domain.repository.KomgaOrganizationRepository
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.CollapsibleBox
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun KomgaOrganizationFilterSheet(
    kind: KomgaOrganizationKind,
    id: String?,
    state: KomgaOrganizationState,
    repository: KomgaOrganizationRepository,
    dismiss: () -> Unit,
    apply: (Map<String, List<String>>, String) -> Unit,
) {
    val draft = remember {
        mutableStateMapOf<String, List<String>>().apply { putAll(state.query.filters) }
    }
    var sort by remember { mutableStateOf(state.query.sort.substringBefore(',')) }
    var ascending by remember { mutableStateOf(state.query.sort.endsWith(",asc")) }
    val scopeFilters = buildMap {
        if (id != null) {
            put(
                if (kind == KomgaOrganizationKind.COLLECTION) "collection_id" else "readlist_id",
                listOf(id),
            )
        }
        draft["library_id"]?.let { put("library_id", it) }
    }
    AdaptiveSheet(onDismissRequest = dismiss) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        draft.clear()
                        sort = "name"
                        ascending = true
                    },
                ) {
                    Text(stringResource(MR.strings.action_reset))
                }
                Text(
                    stringResource(MR.strings.action_filter),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(
                    onClick = {
                        repository.checkActive()
                        apply(
                            draft.filterValues { it.isNotEmpty() }.toMap(),
                            "$sort,${if (ascending) "asc" else "desc"}",
                        )
                    },
                ) {
                    Text(stringResource(MR.strings.action_apply))
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(
                    stringResource(MR.strings.komga_filter_libraries),
                    style = MaterialTheme.typography.titleMedium,
                )
                state.libraries.forEach { library ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            library.id in draft["library_id"].orEmpty(),
                            { selected ->
                                draft["library_id"] =
                                    if (selected) {
                                        draft["library_id"].orEmpty() + library.id
                                    } else {
                                        draft["library_id"].orEmpty() - library.id
                                    }
                            },
                        )
                        Text(library.name)
                    }
                }
                if (id == null) {
                    listOf(
                        "name" to MR.strings.komga_filter_sort_alphabetically,
                        "createdDate" to MR.strings.komga_filter_sort_date_added,
                        "lastModifiedDate" to MR.strings.komga_filter_sort_date_updated,
                    )
                        .forEach { (key, label) ->
                            FilterChip(
                                selected = sort == key,
                                onClick = { sort = key },
                                label = { Text(stringResource(label)) },
                            )
                        }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(ascending, { ascending = it })
                        Text(stringResource(MR.strings.komga_sort_ascending))
                    }
                } else {
                    listOf(
                        "UNREAD" to MR.strings.komga_filter_unread,
                        "IN_PROGRESS" to MR.strings.komga_filter_in_progress,
                        "READ" to MR.strings.komga_filter_read,
                    )
                        .forEach { (key, label) ->
                            FilterChip(
                                selected = key in draft["read_status"].orEmpty(),
                                onClick = {
                                    draft["read_status"] =
                                        if (key in draft["read_status"].orEmpty()) {
                                            draft["read_status"].orEmpty() - key
                                        } else {
                                            draft["read_status"].orEmpty() + key
                                        }
                                },
                                label = { Text(stringResource(label)) },
                            )
                        }
                    if (kind == KomgaOrganizationKind.COLLECTION) {
                        Text(
                            stringResource(MR.strings.komga_organization_status),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        listOf(
                            "ONGOING" to MR.strings.ongoing,
                            "ENDED" to MR.strings.komga_filter_status_ended,
                            "HIATUS" to MR.strings.on_hiatus,
                            "ABANDONED" to MR.strings.komga_filter_status_abandoned,
                        )
                            .forEach { (value, label) ->
                                FilterChip(
                                    selected = value in draft["status"].orEmpty(),
                                    onClick = {
                                        draft["status"] =
                                            if (value in draft["status"].orEmpty()) {
                                                draft["status"].orEmpty() - value
                                            } else {
                                                draft["status"].orEmpty() + value
                                            }
                                    },
                                    label = { Text(stringResource(label)) },
                                )
                            }
                        Text(
                            stringResource(MR.strings.komga_organization_complete),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        listOf(
                            "true" to MR.strings.completed,
                            "false" to MR.strings.komga_organization_incomplete,
                        )
                            .forEach {
                                    (
                                        value,
                                        label,
                                    ),
                                ->
                                FilterChip(
                                    selected = value in draft["complete"].orEmpty(),
                                    onClick = {
                                        draft["complete"] =
                                            if (value in draft["complete"].orEmpty()) {
                                                emptyList()
                                            } else {
                                                listOf(value)
                                            }
                                    },
                                    label = { Text(stringResource(label)) },
                                )
                            }
                    }
                    val fields =
                        buildList<Pair<String, StringResource>> {
                            add("tag" to MR.strings.komga_filter_tags)
                            if (kind == KomgaOrganizationKind.COLLECTION) {
                                add("genre" to MR.strings.komga_filter_genres)
                                add("publisher" to MR.strings.komga_filter_publishers)
                                add("language" to MR.strings.komga_organization_language)
                                add("age_rating" to MR.strings.komga_organization_age)
                                add("release_year" to MR.strings.komga_organization_year)
                            }
                        }
                    fields.forEach { (key, label) ->
                        OrganizationFilterChoices(
                            label,
                            draft[key].orEmpty(),
                            scopeFilters,
                            { draft[key] = it },
                        ) {
                            val path =
                                when (key) {
                                    "tag" ->
                                        if (kind == KomgaOrganizationKind.READ_LIST) {
                                            "tags/book"
                                        } else {
                                            "tags"
                                        }
                                    "genre" -> "genres"
                                    "publisher" -> "publishers"
                                    "language" -> "languages"
                                    "age_rating" -> "age-ratings"
                                    else -> "series/release-dates"
                                }
                            repository.api.choices(path, scopeFilters).also {
                                repository.checkActive()
                            }
                        }
                    }
                    listOf(
                        "writer" to MR.strings.komga_filter_author_writer,
                        "penciller" to MR.strings.komga_filter_author_penciller,
                        "inker" to MR.strings.komga_filter_author_inker,
                        "colorist" to MR.strings.komga_filter_author_colorist,
                        "letterer" to MR.strings.komga_filter_author_letterer,
                        "cover" to MR.strings.komga_filter_author_cover,
                        "editor" to MR.strings.komga_filter_author_editor,
                        "translator" to MR.strings.komga_filter_author_translator,
                        "conceptor" to MR.strings.komga_filter_author_conceptor,
                        "illustrator" to MR.strings.komga_filter_author_illustrator,
                        "artist" to MR.strings.komga_filter_author_artist,
                        "narrator" to MR.strings.komga_filter_author_narrator,
                        "contributor" to MR.strings.komga_filter_author_contributor,
                    )
                        .forEach { (role, label) ->
                            OrganizationFilterChoices(
                                label,
                                draft["author"]
                                    .orEmpty()
                                    .filter { it.endsWith(",$role") }
                                    .map { it.removeSuffix(",$role") },
                                scopeFilters,
                                { selected ->
                                    draft["author"] =
                                        draft["author"].orEmpty().filterNot {
                                            it.endsWith(",$role")
                                        } + selected.map { "$it,$role" }
                                },
                            ) {
                                repository.api.authorNames(role, scopeFilters).also {
                                    repository.checkActive()
                                }
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun OrganizationFilterChoices(
    label: StringResource,
    selected: List<String>,
    scope: Map<String, List<String>>,
    onSelect: (List<String>) -> Unit,
    load: suspend () -> List<String>,
) {
    CollapsibleBox(stringResource(label)) {
        var choices by remember(scope) { mutableStateOf<List<String>>(emptyList()) }
        var error by remember(scope) { mutableStateOf<Throwable?>(null) }
        var search by remember { mutableStateOf("") }
        val context = LocalContext.current
        LaunchedEffect(scope) {
            try {
                choices = withIOContext { load() }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure
            }
        }
        Column {
            OutlinedTextField(
                search,
                { search = it },
                label = { Text(stringResource(MR.strings.action_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(organizationError(context, it), color = MaterialTheme.colorScheme.error)
            }
            (selected + choices)
                .distinct()
                .filter { it.contains(search, ignoreCase = true) }
                .forEach { value ->
                    CheckboxItem(label = value, checked = value in selected) {
                        onSelect(if (value in selected) selected - value else selected + value)
                    }
                }
        }
    }
}
