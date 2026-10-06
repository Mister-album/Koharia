package koharia.suwayomi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiCheckBoxPreference
import koharia.suwayomi.SuwayomiEditTextPreference
import koharia.suwayomi.SuwayomiListPreference
import koharia.suwayomi.SuwayomiMultiSelectPreference
import koharia.suwayomi.SuwayomiSourcePreference
import koharia.suwayomi.SuwayomiSwitchPreference
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkCircularProgressIndicator
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.presentation.util.Screen as TachiyomiScreen

/**
 * Settings a source extension exposes, read and written through the server. Distinct from the
 * connection editor, which owns the server address and credentials.
 *
 * [sourceInfoId] is the server's source id; the connection's own profile id is a different value.
 */
data class SuwayomiSourcePreferencesScreen(
    private val sourceId: Long,
    private val sourceInfoId: Long,
    private val sourceName: String? = null,
) : TachiyomiScreen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val source = remember(sourceId) { Injekt.get<SourceManager>().get(sourceId) as? SuwayomiSource }
        if (source == null) {
            val title = sourceName ?: stringResource(MR.strings.source_settings)
            Scaffold(topBar = { AppBar(title = title, navigateUp = navigator::pop) }) { contentPadding ->
                Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
                    Text(stringResource(MR.strings.suwayomi_source_preferences_unavailable))
                }
            }
            return
        }
        val epoch by source.epoch.collectAsState()
        val model =
            rememberScreenModel(tag = "${source.instanceKey}:$epoch") {
                SuwayomiSourcePreferencesScreenModel(source, sourceInfoId)
            }
        val state by model.state.collectAsState()

        Scaffold(
            topBar = {
                AppBar(
                    title = sourceName ?: stringResource(MR.strings.source_settings),
                    navigateUp = navigator::pop,
                )
            },
        ) { padding ->
            when {
                state.loading && !state.loaded -> Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    EInkCircularProgressIndicator()
                }
                state.preferences.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (state.error != null) {
                                MR.strings.suwayomi_source_preferences_failed
                            } else {
                                MR.strings.suwayomi_source_preferences_empty
                            },
                        ),
                    )
                }
                else -> LazyColumn(Modifier.padding(padding)) {
                    item {
                        PreferenceGroupHeader(stringResource(MR.strings.suwayomi_source_preferences_group))
                    }
                    items(state.visiblePreferences, key = { it.position }) { preference ->
                        SourcePreferenceItem(
                            preference = preference,
                            enabled = preference.enabled && !state.saving,
                            onChange = { model.apply(preference, it) },
                        )
                    }
                    if (state.error != null) {
                        item {
                            Text(
                                text = stringResource(MR.strings.suwayomi_source_preferences_write_failed),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .secondaryItemAlpha(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePreferenceItem(
    preference: SuwayomiSourcePreference,
    enabled: Boolean,
    onChange: (Any) -> Unit,
) {
    when (preference) {
        is SuwayomiSwitchPreference -> SwitchPreferenceWidget(
            title = preference.displayTitle,
            subtitle = preference.resolvedSummary(
                if (preference.currentValue ?: preference.default) "true" else "false",
            ),
            checked = preference.currentValue ?: preference.default,
            enabled = enabled,
            onCheckedChanged = onChange,
        )
        is SuwayomiCheckBoxPreference -> PreferenceCheckboxItem(
            preference = preference,
            checked = preference.currentValue ?: preference.default,
            enabled = enabled,
            onChange = onChange,
        )
        is SuwayomiEditTextPreference -> PreferenceTextItem(preference, enabled, onChange)
        is SuwayomiListPreference -> PreferenceListItem(preference, enabled, onChange)
        is SuwayomiMultiSelectPreference -> PreferenceMultiSelectItem(preference, enabled, onChange)
    }
}

@Composable
private fun PreferenceCheckboxItem(
    preference: SuwayomiCheckBoxPreference,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    TextPreferenceWidget(
        title = preference.displayTitle,
        subtitle = preference.resolvedSummary(if (checked) "true" else "false"),
        enabled = enabled,
        widget = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
        onPreferenceClick = { if (enabled) onChange(!checked) },
    )
}

@Composable
private fun PreferenceTextItem(
    preference: SuwayomiEditTextPreference,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val current = preference.currentValue ?: preference.default.orEmpty()
    TextPreferenceWidget(
        title = preference.displayTitle,
        subtitle = preference.resolvedSummary(current) ?: current.ifBlank { preference.text.orEmpty() },
        enabled = enabled,
        onPreferenceClick = { if (enabled) editing = true },
    )
    if (editing) {
        var draft by remember(current) { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(preference.dialogTitle ?: preference.displayTitle) },
            text = {
                Column {
                    preference.dialogMessage?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    onChange(draft)
                }) { Text(stringResource(MR.strings.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}

@Composable
private fun PreferenceListItem(
    preference: SuwayomiListPreference,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    // The server reports labels and values as parallel arrays; the stored selection is the value.
    val labels = preference.entries.toTypedArray()
    val values = preference.entryValues.toTypedArray()
    val index = preference.selectedIndex.coerceIn(0, (values.size - 1).coerceAtLeast(0))
    val currentLabel = labels.getOrNull(index)?.toString().orEmpty()
    SelectItem(
        label = preference.displayTitle,
        options = if (labels.size == values.size) labels else values,
        selectedIndex = index,
    ) { selected ->
        values.getOrNull(selected)?.let(onChange)
    }
    // The dropdown already shows the selection, so a summary that only repeats it is dropped.
    preference.resolvedSummary(currentLabel)
        ?.takeIf { it != currentLabel && it.isNotBlank() }
        ?.let {
            Text(
                text = it,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).secondaryItemAlpha(),
            )
        }
}

@Composable
private fun PreferenceMultiSelectItem(
    preference: SuwayomiMultiSelectPreference,
    enabled: Boolean,
    onChange: (List<String>) -> Unit,
) {
    var selecting by remember { mutableStateOf(false) }
    val selected = preference.selected
    var draft by remember(selected, selecting) { mutableStateOf(selected) }
    val summary = if (selected.isEmpty()) {
        preference.resolvedSummary("").orEmpty()
    } else {
        preference.entries.zip(preference.entryValues)
            .filter { it.second in selected }
            .joinToString(", ") { it.first }
    }
    TextPreferenceWidget(
        title = preference.displayTitle,
        subtitle = summary,
        enabled = enabled,
        onPreferenceClick = {
            if (enabled) {
                draft = selected
                selecting = true
            }
        },
    )
    if (selecting) {
        AlertDialog(
            onDismissRequest = { selecting = false },
            title = { Text(preference.dialogTitle ?: preference.displayTitle) },
            text = {
                Column {
                    preference.dialogMessage?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(preference.entries.indices.toList()) { index ->
                            val value = preference.entryValues.getOrNull(index) ?: return@items
                            TextPreferenceWidget(
                                title = preference.entries[index],
                                widget = { Checkbox(checked = value in draft, onCheckedChange = null) },
                                onPreferenceClick = {
                                    draft = if (value in draft) draft - value else draft + value
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selecting = false
                    onChange(draft.toList())
                }) { Text(stringResource(MR.strings.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { selecting = false }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}
