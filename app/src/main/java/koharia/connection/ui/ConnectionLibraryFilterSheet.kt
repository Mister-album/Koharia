package koharia.connection.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.core.common.preference.TriState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.CollapsibleBox
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.Button
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

/** A shelf condition the reader can require, exclude, or ignore. */
enum class ConnectionTriState {
    IGNORE,
    INCLUDE,
    EXCLUDE,
    ;

    fun next(): ConnectionTriState = when (this) {
        IGNORE -> INCLUDE
        INCLUDE -> EXCLUDE
        EXCLUDE -> IGNORE
    }
}

/** One tri-state shelf condition a provider can offer, with its own label and current state. */
data class ConnectionLibraryFilterRow(
    val key: String,
    val label: String,
    val state: ConnectionTriState,
)

/**
 * Handed to the provider's [ConnectionLibraryFilterSheet] extras so Reset can clear their drafts too;
 * an extras composable holds its own text-field state, which the sheet cannot reach.
 */
class ConnectionFilterExtrasScope {
    private var revision by mutableStateOf(0)

    /** Changes whenever Reset is pressed, so an extras composable can drop its drafts. */
    val resetKey: Int get() = revision

    fun notifyReset() {
        revision++
    }
}

/**
 * The shelf filter workspace: Reset/Filter in a sticky bar, the provider's tri-state conditions,
 * sort, downloaded-only, a persistence choice, plus whatever extra narrowing the provider supplies.
 */
@Composable
fun ConnectionLibraryFilterSheet(
    rows: List<ConnectionLibraryFilterRow>,
    sortOptions: List<ConnectionSearchSortOption<String>>,
    currentOrder: String,
    downloadedOnly: Boolean,
    persistentFilters: Boolean,
    onDismissRequest: () -> Unit,
    onApply: (Map<String, ConnectionTriState>, String, Boolean, Boolean) -> Unit,
    onReset: () -> Unit,
    extras: (@Composable (ConnectionFilterExtrasScope) -> Unit)? = null,
) {
    var draft by remember(rows) { mutableStateOf(rows.associate { it.key to it.state }) }
    var field by remember(currentOrder, sortOptions) {
        mutableStateOf(sortOptions.indexOfFirst { it.value == currentOrder.substringBefore(' ') }.coerceAtLeast(0))
    }
    var descending by remember(currentOrder) { mutableStateOf(currentOrder.endsWith("desc")) }
    var downloaded by remember(downloadedOnly) { mutableStateOf(downloadedOnly) }
    var persistent by remember(persistentFilters) { mutableStateOf(persistentFilters) }
    val extrasScope = remember { ConnectionFilterExtrasScope() }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    draft = rows.associate { it.key to ConnectionTriState.IGNORE }
                    downloaded = false
                    extrasScope.notifyReset()
                    onReset()
                }) {
                    Text(
                        text = stringResource(MR.strings.action_reset),
                        style = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.primary),
                    )
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    onApply(
                        draft,
                        "${sortOptions[field].value} ${if (descending) "desc" else "asc"}",
                        downloaded,
                        persistent,
                    )
                }) {
                    Text(stringResource(MR.strings.action_filter))
                }
            }
            HorizontalDivider()
            Column(
                Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (rows.isNotEmpty()) {
                    SectionHeader(stringResource(MR.strings.shelf_filter_conditions))
                    rows.forEach { row ->
                        TriStateItem(
                            label = row.label,
                            state = (draft[row.key] ?: row.state).toTriState(),
                        ) {
                            draft = draft + (
                                row.key to (draft[row.key] ?: row.state).next()
                                )
                        }
                    }
                }
                SectionHeader(stringResource(MR.strings.action_sort))
                SelectItem(
                    stringResource(MR.strings.action_sort),
                    sortOptions.map { it.label }.toTypedArray(),
                    field,
                ) { field = it }
                CheckboxItem(stringResource(MR.strings.shelf_sort_descending), descending) {
                    descending = !descending
                }
                CheckboxItem(stringResource(MR.strings.shelf_downloaded_only), downloaded) {
                    downloaded = !downloaded
                }
                if (extras != null) {
                    HorizontalDivider(Modifier.padding(top = MaterialTheme.padding.medium))
                    extras(extrasScope)
                }
                // Last, and without an explanation: a stored preference rather than a filter.
                HorizontalDivider(Modifier.padding(top = MaterialTheme.padding.medium))
                CheckboxItem(stringResource(MR.strings.shelf_persistent_filters), persistent) {
                    persistent = !persistent
                }
                Spacer(Modifier.padding(bottom = MaterialTheme.padding.medium))
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
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

/** Maps the shared tri-state onto the presentation component's, which only models is/is-not. */
private fun ConnectionTriState.toTriState(): TriState = when (this) {
    ConnectionTriState.IGNORE -> TriState.DISABLED
    ConnectionTriState.INCLUDE -> TriState.ENABLED_IS
    ConnectionTriState.EXCLUDE -> TriState.ENABLED_NOT
}

/** A collapsible group of checkbox options, used for the genre narrowing. */
@Composable
fun ConnectionFilterOptionGroup(
    heading: String,
    options: List<String>,
    selected: Set<String>,
    emptyLabel: String,
    onToggle: (String) -> Unit,
) {
    CollapsibleBox(heading = heading, initiallyExpanded = selected.isNotEmpty()) {
        if (options.isEmpty()) {
            Text(
                text = emptyLabel,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small)
                    .secondaryItemAlpha(),
            )
        } else {
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(options, key = { it }) { option ->
                    CheckboxItem(option, option in selected) { onToggle(option) }
                }
            }
        }
    }
}
