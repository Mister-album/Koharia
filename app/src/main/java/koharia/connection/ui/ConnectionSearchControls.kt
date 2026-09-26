package koharia.connection.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

data class ConnectionSearchChoice<T>(val value: T, val label: String)

data class ConnectionSearchSortOption<T>(
    val value: T,
    val label: String,
    val supportsDirection: Boolean = true,
    val defaultAscending: Boolean = true,
) {
    fun ascendingAfterSelection(selected: T, ascending: Boolean): Boolean = when {
        !supportsDirection -> defaultAscending
        selected == value -> !ascending
        else -> defaultAscending
    }
}

@Composable
fun <T> ConnectionSearchScope(
    choices: List<ConnectionSearchChoice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val choice = choices.firstOrNull { it.value == selected } ?: return
    var expanded by remember(choices) { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { option ->
                RadioMenuItem(text = { Text(option.label) }, isChecked = option.value == selected) {
                    expanded = false
                    onSelect(option.value)
                }
            }
        }
    }
}

@Composable
fun <T> ConnectionSearchResults(
    options: List<ConnectionSearchSortOption<T>>,
    selected: T,
    ascending: Boolean,
    onSelect: (T, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.padding(vertical = 8.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                stringResource(MR.strings.search_results),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.weight(1f))
        val current = options.firstOrNull { it.value == selected }
        if (current != null) {
            var expanded by remember(options) { mutableStateOf(false) }
            val direction = if (current.supportsDirection) {
                if (ascending) " ↑" else " ↓"
            } else {
                ""
            }
            Box {
                IconButton(
                    modifier = Modifier.semantics { stateDescription = current.label + direction },
                    onClick = { expanded = true },
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = stringResource(MR.strings.action_sort))
                }
                DropdownMenu(expanded, { expanded = false }, offset = DpOffset.Zero) {
                    options.forEach { option ->
                        RadioMenuItem(
                            text = { Text(option.label + if (option.value == selected) direction else "") },
                            isChecked = option.value == selected,
                        ) {
                            expanded = false
                            onSelect(option.value, option.ascendingAfterSelection(selected, ascending))
                        }
                    }
                }
            }
        }
    }
}
