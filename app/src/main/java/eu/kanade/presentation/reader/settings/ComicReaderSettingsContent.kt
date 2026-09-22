package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.reader.ReadingModeSelectDialog
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import koharia.epub.settings.ComicThemePreference
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

private enum class ComicSettingsDialog { READING_MODE, MORE }

@Composable
fun ComicReaderSettingsContent(
    screenModel: ReaderSettingsScreenModel,
    modifier: Modifier = Modifier,
) {
    var activeDialog by rememberSaveable { mutableStateOf<ComicSettingsDialog?>(null) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ComicBrightnessRow(screenModel)
        ComicThemePreference(screenModel.preferences)
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ComicSettingsButton(
                text = stringResource(MR.strings.epub_reader_reading_mode_settings),
                modifier = Modifier.weight(1f),
                onClick = { activeDialog = ComicSettingsDialog.READING_MODE },
            )
            ComicSettingsButton(
                text = stringResource(MR.strings.epub_reader_more_reading_settings),
                modifier = Modifier.weight(1f),
                onClick = { activeDialog = ComicSettingsDialog.MORE },
            )
        }
    }

    when (activeDialog) {
        ComicSettingsDialog.READING_MODE -> ReadingModeSelectDialog(
            onDismissRequest = { activeDialog = null },
            screenModel = screenModel,
            onChange = {},
        )
        ComicSettingsDialog.MORE -> TabbedDialog(
            onDismissRequest = { activeDialog = null },
            tabTitles = persistentListOf(
                stringResource(MR.strings.pref_category_reader),
                stringResource(MR.strings.pref_category_general),
                stringResource(MR.strings.pref_custom_color_filter),
            ),
        ) { page ->
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            ) {
                when (page) {
                    0 -> ViewerSettingsPage(screenModel)
                    1 -> GeneralPage(screenModel)
                    2 -> ColorFilterPage(screenModel, showBrightnessAndTheme = false)
                }
            }
        }
        null -> Unit
    }
}

@Composable
private fun ComicBrightnessRow(screenModel: ReaderSettingsScreenModel) {
    val custom by screenModel.preferences.customBrightness.collectAsState()
    val value by screenModel.preferences.customBrightnessValue.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(MR.strings.epub_reader_brightness),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Slider(
            value = if (custom) value.toFloat() else 0f,
            onValueChange = {
                screenModel.preferences.customBrightness.set(true)
                screenModel.preferences.customBrightnessValue.set(it.toInt())
            },
            valueRange = -75f..100f,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                screenModel.preferences.customBrightness.set(false)
                screenModel.preferences.customBrightnessValue.set(0)
            },
        ) {
            Text(stringResource(MR.strings.epub_reader_follow_system_brightness))
        }
    }
}

@Composable
private fun ComicSettingsButton(
    text: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
