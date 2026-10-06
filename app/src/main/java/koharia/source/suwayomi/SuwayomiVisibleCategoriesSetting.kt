package koharia.source.suwayomi

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import koharia.suwayomi.SuwayomiCategory
import koharia.suwayomi.ui.suwayomiError
import kotlinx.coroutines.CancellationException
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkCircularProgressIndicator
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun SuwayomiVisibleCategoriesSetting(
    accountKey: String?,
    selected: Set<Int>?,
    enabled: Boolean,
    onChange: (Set<Int>?) -> Unit,
    load: suspend () -> List<SuwayomiCategory>,
) {
    var open by remember(accountKey) { mutableStateOf(false) }
    val context = LocalContext.current
    TextPreferenceWidget(
        title = stringResource(MR.strings.komga_pref_default_libraries_title),
        subtitle = if (selected == null) {
            stringResource(MR.strings.suwayomi_libraries_all)
        } else {
            stringResource(MR.strings.suwayomi_libraries_selected, selected.size)
        },
        enabled = enabled,
        onPreferenceClick = { open = true },
    )
    if (!open) return
    var categories by remember { mutableStateOf<List<SuwayomiCategory>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf(selected) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(accountKey, attempt) {
        error = null
        try {
            categories = load()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = context.suwayomiError(e)
        }
    }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(MR.strings.komga_pref_default_libraries_title)) },
        text = {
            LazyColumn {
                item {
                    CategoryChoice(stringResource(MR.strings.suwayomi_libraries_all), draft == null) {
                        draft = if (draft == null) categories.orEmpty().map { it.id }.toSet() else null
                    }
                }
                if (categories == null && error == null) item { EInkCircularProgressIndicator() }
                error?.let { message ->
                    item {
                        Text(message)
                        TextButton(onClick = { attempt++ }) { Text(stringResource(MR.strings.action_retry)) }
                    }
                }
                items(categories.orEmpty(), key = { it.id }) { category ->
                    CategoryChoice(category.name, draft == null || category.id in draft.orEmpty()) {
                        val current = draft ?: categories.orEmpty().map { it.id }.toSet()
                        draft = if (category.id in current) current - category.id else current + category.id
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = categories != null, onClick = {
                onChange(draft)
                open = false
            }) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun CategoryChoice(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, onValueChange = {
            onClick()
        }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label)
    }
}
