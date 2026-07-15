package koharia.epub.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DensityLarge
import androidx.compose.material.icons.outlined.DensityMedium
import androidx.compose.material.icons.outlined.DensitySmall
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import koharia.epub.EpubBrightnessAwareDialogContent
import koharia.epub.calculateEpubBrightness
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

private enum class EpubSettingsDialog {
    READING_MODE,
    TYPOGRAPHY,
    MORE,
}

private const val ADD_BACKGROUND_COLOR_INDEX = -1

@Composable
fun EpubReaderSettingsSheet(
    preferences: EpubLayoutPreferences,
    readerPreferences: ReaderPreferences,
    epubReaderPreferences: EpubReaderPreferences,
    onDismissRequest: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        EpubReaderSettingsContent(
            preferences = preferences,
            readerPreferences = readerPreferences,
            epubReaderPreferences = epubReaderPreferences,
        )
    }
}

@Composable
fun EpubReaderSettingsContent(
    preferences: EpubLayoutPreferences,
    readerPreferences: ReaderPreferences,
    epubReaderPreferences: EpubReaderPreferences,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
) {
    var activeDialog by rememberSaveable { mutableStateOf<EpubSettingsDialog?>(null) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BrightnessRow(readerPreferences)
        FontSizeRow(
            preferences = preferences,
            onOpenTypography = { activeDialog = EpubSettingsDialog.TYPOGRAPHY },
        )
        EpubThemePreference(preferences, readerPreferences)
        HorizontalDivider(
            modifier = Modifier.padding(
                start = 24.dp,
                top = 10.dp,
                end = 24.dp,
                bottom = 6.dp,
            ),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = { activeDialog = EpubSettingsDialog.READING_MODE },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(MR.strings.epub_reader_reading_mode_settings),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Surface(
                onClick = { activeDialog = EpubSettingsDialog.MORE },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(MR.strings.epub_reader_more_reading_settings),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }

    when (activeDialog) {
        EpubSettingsDialog.READING_MODE -> ReadingModeSettingsSheet(
            preferences = preferences,
            readerPreferences = readerPreferences,
            onDismissRequest = { activeDialog = null },
        )
        EpubSettingsDialog.TYPOGRAPHY -> TypographySettingsSheet(
            preferences = preferences,
            readerPreferences = readerPreferences,
            onDismissRequest = { activeDialog = null },
        )
        EpubSettingsDialog.MORE -> MoreReadingSettingsSheet(
            preferences = preferences,
            readerPreferences = readerPreferences,
            epubReaderPreferences = epubReaderPreferences,
            onDismissRequest = { activeDialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun BrightnessRow(readerPreferences: ReaderPreferences) {
    val customBrightness by readerPreferences.customBrightness.changes()
        .collectAsState(readerPreferences.customBrightness.get())
    val brightnessValue by readerPreferences.customBrightnessValue.changes()
        .collectAsState(readerPreferences.customBrightnessValue.get())
    val followsSystem = !customBrightness

    CompactSettingRow(label = stringResource(MR.strings.epub_reader_brightness)) {
        Slider(
            value = brightnessValue.toFloat(),
            onValueChange = { readerPreferences.customBrightnessValue.set(it.roundToInt()) },
            valueRange = -75f..100f,
            enabled = customBrightness,
            modifier = Modifier.weight(1f),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            color = if (customBrightness) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                            shape = CircleShape,
                        ),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    enabled = customBrightness,
                    modifier = Modifier.height(4.dp),
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                )
            },
        )
        Text(
            text = if (followsSystem || brightnessValue == 0) {
                stringResource(MR.strings.epub_reader_system_brightness_short)
            } else {
                "$brightnessValue%"
            },
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.widthIn(min = 40.dp),
        )
        FilterChip(
            selected = followsSystem,
            onClick = {
                if (followsSystem) {
                    if (brightnessValue == 0) {
                        readerPreferences.customBrightnessValue.set(50)
                    }
                    readerPreferences.customBrightness.set(true)
                } else {
                    readerPreferences.customBrightness.set(false)
                }
            },
            label = {
                Text(
                    text = stringResource(MR.strings.epub_reader_follow_system_brightness),
                    maxLines = 1,
                )
            },
        )
    }
}

@Composable
private fun FontSizeRow(
    preferences: EpubLayoutPreferences,
    onOpenTypography: () -> Unit,
) {
    val fontSize by preferences.fontSize.changes().collectAsState(preferences.fontSize.get())
    val currentValue = EpubLayoutPreferences.fontSizeFromScale(fontSize)
    val editFontSizeDescription = stringResource(MR.strings.epub_reader_edit_font_size)
    var showInputDialog by rememberSaveable { mutableStateOf(false) }

    val setFontSize = { value: Int ->
        preferences.fontSize.set(EpubLayoutPreferences.scaleFromFontSize(value))
    }

    CompactSettingRow(label = stringResource(MR.strings.pref_epub_font_size)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    enabled = currentValue > EpubLayoutPreferences.MIN_FONT_SIZE,
                    onClick = { setFontSize(currentValue - 1) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Remove,
                        contentDescription = stringResource(MR.strings.epub_reader_decrease_font_size),
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .clickable(onClick = { showInputDialog = true })
                        .semantics {
                            contentDescription = editFontSizeDescription
                            role = Role.Button
                        },
                ) {
                    Text(
                        text = currentValue.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                IconButton(
                    enabled = currentValue < EpubLayoutPreferences.MAX_FONT_SIZE,
                    onClick = { setFontSize(currentValue + 1) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(MR.strings.epub_reader_increase_font_size),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            onClick = onOpenTypography,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Text(
                text = stringResource(MR.strings.epub_reader_font_and_spacing),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }

    if (showInputDialog) {
        FontSizeInputDialog(
            initialValue = currentValue,
            onDismissRequest = { showInputDialog = false },
            onConfirm = { value ->
                setFontSize(value)
                showInputDialog = false
            },
        )
    }
}

@Composable
private fun FontSizeInputDialog(
    initialValue: Int,
    onDismissRequest: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var input by rememberSaveable(initialValue) { mutableStateOf(initialValue.toString()) }
    val validValue = input.toIntOrNull()?.takeIf {
        it in EpubLayoutPreferences.MIN_FONT_SIZE..EpubLayoutPreferences.MAX_FONT_SIZE
    }
    val isValid = validValue != null
    val confirm = {
        if (validValue != null) {
            onConfirm(validValue)
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.pref_epub_font_size)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { value -> input = value.filter(Char::isDigit).take(2) },
                supportingText = { Text(stringResource(MR.strings.epub_reader_font_size_input_range)) },
                isError = input.isNotEmpty() && !isValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = confirm,
            ) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
fun EpubThemePreference(
    preferences: EpubLayoutPreferences,
    readerPreferences: ReaderPreferences,
) {
    val currentTheme by preferences.theme.changes().collectAsState(preferences.theme.get())
    val currentCustomColor by preferences.customBackgroundColor.changes()
        .collectAsState(preferences.customBackgroundColor.get())
    val backgroundColors = rememberEpubBackgroundColors(preferences)
    val deleteColorLabel = stringResource(MR.strings.epub_reader_delete_custom_color)
    var showColorPicker by rememberSaveable { mutableStateOf(false) }

    fun saveBackgroundColors(colors: List<Int>) {
        preferences.backgroundColors.set(EpubLayoutPreferences.encodeBackgroundColors(colors))
    }

    fun selectColor(color: Int) {
        preferences.customBackgroundColor.set(color)
        preferences.theme.set(EpubLayoutPreferences.Theme.CUSTOM)
    }

    val selectedColor = when (currentTheme) {
        EpubLayoutPreferences.Theme.CUSTOM -> currentCustomColor
        else -> currentTheme.swatchColor().toArgb()
    }

    fun deleteBackgroundColor(index: Int) {
        val color = backgroundColors[index]
        val remainingColors = backgroundColors.toMutableList().apply { removeAt(index) }
        saveBackgroundColors(remainingColors)
        if (selectedColor == color) {
            remainingColors.getOrNull(index.coerceAtMost(remainingColors.lastIndex))?.let(::selectColor)
                ?: preferences.theme.set(EpubLayoutPreferences.Theme.LIGHT)
        }
    }

    CompactSettingRow(label = stringResource(MR.strings.pref_epub_theme)) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            backgroundColors.forEachIndexed { index, color ->
                key(color) {
                    ThemeSwatch(
                        color = Color(color),
                        label = "#%06X".format(color and 0xFFFFFF),
                        selected = selectedColor == color,
                        onClick = { selectColor(color) },
                        onLongClickLabel = deleteColorLabel,
                        onLongClick = { deleteBackgroundColor(index) },
                    )
                }
            }

            val canAddColor = backgroundColors.size < EpubLayoutPreferences.MAX_BACKGROUND_COLORS
            val addColorLabel = stringResource(MR.strings.epub_reader_add_background_color)
            val colorLimitLabel = stringResource(MR.strings.epub_reader_custom_color_limit)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .semantics {
                        contentDescription = if (canAddColor) {
                            addColorLabel
                        } else {
                            colorLimitLabel
                        }
                        role = Role.Button
                    }
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable(enabled = canAddColor, role = Role.Button) { showColorPicker = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    tint = if (canAddColor) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
        }
    }

    if (showColorPicker) {
        CustomBackgroundColorDialog(
            initialColor = currentCustomColor,
            readerPreferences = readerPreferences,
            onDismissRequest = { showColorPicker = false },
            onConfirm = { color ->
                val updatedColors = (backgroundColors + color)
                    .distinct()
                    .take(EpubLayoutPreferences.MAX_BACKGROUND_COLORS)
                saveBackgroundColors(updatedColors)
                selectColor(color)
                showColorPicker = false
            },
        )
    }
}

@Composable
private fun rememberEpubBackgroundColors(preferences: EpubLayoutPreferences): List<Int> {
    val serializedColors by preferences.backgroundColors.changes()
        .collectAsState(preferences.backgroundColors.get())
    val legacyColors by preferences.customBackgroundColors.changes()
        .collectAsState(preferences.customBackgroundColors.get())
    val needsMigration = !preferences.backgroundColors.isSet()
    val initialColors = remember(legacyColors) {
        EpubLayoutPreferences.initialBackgroundColors(legacyColors)
    }

    LaunchedEffect(needsMigration, legacyColors) {
        if (needsMigration) {
            preferences.backgroundColors.set(EpubLayoutPreferences.encodeBackgroundColors(initialColors))
        }
    }

    return remember(serializedColors, needsMigration, initialColors) {
        if (needsMigration) {
            initialColors
        } else {
            EpubLayoutPreferences.decodeBackgroundColors(serializedColors)
        }
    }
}

@Composable
fun EpubBackgroundSettingsPreference(
    preferences: EpubLayoutPreferences,
    readerPreferences: ReaderPreferences,
) {
    val currentTheme by preferences.theme.changes().collectAsState(preferences.theme.get())
    val currentCustomColor by preferences.customBackgroundColor.changes()
        .collectAsState(preferences.customBackgroundColor.get())
    val backgroundColors = rememberEpubBackgroundColors(preferences)
    var showManager by rememberSaveable { mutableStateOf(false) }
    var colorPickerIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    fun saveBackgroundColors(colors: List<Int>) {
        preferences.backgroundColors.set(EpubLayoutPreferences.encodeBackgroundColors(colors))
    }

    fun selectColor(color: Int) {
        preferences.customBackgroundColor.set(color)
        preferences.theme.set(EpubLayoutPreferences.Theme.CUSTOM)
    }

    val selectedColor = when (currentTheme) {
        EpubLayoutPreferences.Theme.CUSTOM -> currentCustomColor
        else -> currentTheme.swatchColor().toArgb()
    }

    fun deleteColor(index: Int) {
        val removedColor = backgroundColors[index]
        val remainingColors = backgroundColors.toMutableList().apply { removeAt(index) }
        saveBackgroundColors(remainingColors)
        if (selectedColor == removedColor) {
            remainingColors.getOrNull(index.coerceAtMost(remainingColors.lastIndex))?.let(::selectColor)
                ?: preferences.theme.set(EpubLayoutPreferences.Theme.LIGHT)
        }
    }

    fun moveColor(index: Int, offset: Int) {
        val targetIndex = index + offset
        if (targetIndex !in backgroundColors.indices) return
        val reorderedColors = backgroundColors.toMutableList()
        val color = reorderedColors.removeAt(index)
        reorderedColors.add(targetIndex, color)
        saveBackgroundColors(reorderedColors)
    }

    Surface(
        onClick = { showManager = true },
        color = Color.Transparent,
    ) {
        ListItem(
            headlineContent = { Text(stringResource(MR.strings.pref_epub_theme)) },
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }

    if (showManager && colorPickerIndex == null) {
        Dialog(onDismissRequest = { showManager = false }) {
            EpubDialogBrightnessContainer(
                followReaderBrightness = false,
                readerPreferences = readerPreferences,
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 6.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 640.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                    ) {
                        Text(
                            text = stringResource(MR.strings.pref_epub_theme),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        backgroundColors.forEachIndexed { index, color ->
                            val selected = selectedColor == color
                            Surface(
                                onClick = { selectColor(color) },
                                color = if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                },
                                shape = MaterialTheme.shapes.large,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .border(
                                                width = if (selected) 2.dp else 1.dp,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.outlineVariant
                                                },
                                                shape = CircleShape,
                                            )
                                            .padding(4.dp)
                                            .background(Color(color), CircleShape),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "#%06X".format(color and 0xFFFFFF),
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = { colorPickerIndex = index },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            Icons.Outlined.Edit,
                                            contentDescription = stringResource(
                                                MR.strings.epub_reader_edit_background_color,
                                            ),
                                        )
                                    }
                                    IconButton(
                                        enabled = index > 0,
                                        onClick = { moveColor(index, -1) },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            Icons.Outlined.KeyboardArrowUp,
                                            contentDescription = stringResource(
                                                MR.strings.epub_reader_move_color_up,
                                            ),
                                        )
                                    }
                                    IconButton(
                                        enabled = index < backgroundColors.lastIndex,
                                        onClick = { moveColor(index, 1) },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            Icons.Outlined.KeyboardArrowDown,
                                            contentDescription = stringResource(
                                                MR.strings.epub_reader_move_color_down,
                                            ),
                                        )
                                    }
                                    IconButton(
                                        onClick = { deleteColor(index) },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = stringResource(MR.strings.action_delete),
                                        )
                                    }
                                }
                            }
                        }
                        TextButton(
                            enabled = backgroundColors.size < EpubLayoutPreferences.MAX_BACKGROUND_COLORS,
                            onClick = { colorPickerIndex = ADD_BACKGROUND_COLOR_INDEX },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(MR.strings.epub_reader_add_background_color))
                        }
                        if (backgroundColors.size >= EpubLayoutPreferences.MAX_BACKGROUND_COLORS) {
                            Text(
                                text = stringResource(MR.strings.epub_reader_custom_color_limit),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { showManager = false }) {
                                Text(stringResource(MR.strings.action_ok))
                            }
                        }
                    }
                }
            }
        }
    }

    colorPickerIndex?.let { editIndex ->
        val initialColor = backgroundColors.getOrNull(editIndex) ?: selectedColor
        CustomBackgroundColorDialog(
            initialColor = initialColor,
            readerPreferences = readerPreferences,
            followReaderBrightness = false,
            isEditing = editIndex != ADD_BACKGROUND_COLOR_INDEX,
            onDismissRequest = { colorPickerIndex = null },
            onConfirm = { color ->
                if (editIndex == ADD_BACKGROUND_COLOR_INDEX) {
                    saveBackgroundColors(backgroundColors + color)
                    selectColor(color)
                } else {
                    val previousColor = backgroundColors[editIndex]
                    val updatedColors = backgroundColors.toMutableList().apply { set(editIndex, color) }
                    saveBackgroundColors(updatedColors)
                    if (selectedColor == previousColor) selectColor(color)
                }
                colorPickerIndex = null
            },
        )
    }
}

@Composable
private fun ThemeSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .semantics {
                contentDescription = label
                role = Role.RadioButton
                this.selected = selected
            }
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = CircleShape,
            )
            .padding(4.dp)
            .background(color, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .combinedClickable(
                role = Role.RadioButton,
                onClick = onClick,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
            ),
    )
}

@Composable
private fun CustomBackgroundColorDialog(
    initialColor: Int,
    readerPreferences: ReaderPreferences,
    followReaderBrightness: Boolean = true,
    isEditing: Boolean = false,
    onDismissRequest: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initialHsv = remember(initialColor) {
        FloatArray(3).also { AndroidColor.colorToHSV(initialColor, it) }
    }
    var hue by remember(initialColor) { mutableStateOf(initialHsv[0]) }
    var saturation by remember(initialColor) { mutableStateOf(initialHsv[1]) }
    var brightness by remember(initialColor) { mutableStateOf(initialHsv[2]) }
    var hexValue by remember(initialColor) {
        mutableStateOf("%06X".format(initialColor and 0xFFFFFF))
    }
    val color = remember(hue, saturation, brightness) {
        AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness))
    }
    val isHexValid = hexValue.length == 6 && hexValue.toIntOrNull(16) != null

    LaunchedEffect(color) {
        val colorHex = "%06X".format(color and 0xFFFFFF)
        if (!hexValue.equals(colorHex, ignoreCase = true)) {
            hexValue = colorHex
        }
    }

    Dialog(onDismissRequest = onDismissRequest) {
        EpubDialogBrightnessContainer(
            followReaderBrightness = followReaderBrightness,
            readerPreferences = readerPreferences,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(
                            if (isEditing) {
                                MR.strings.epub_reader_edit_background_color
                            } else {
                                MR.strings.epub_reader_add_background_color
                            },
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Box(
                        modifier = Modifier
                            .padding(vertical = 20.dp)
                            .size(64.dp)
                            .align(Alignment.CenterHorizontally)
                            .background(Color(color), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                    OutlinedTextField(
                        value = hexValue,
                        onValueChange = { input ->
                            val normalized = input
                                .removePrefix("#")
                                .filter { it.isHexDigit() }
                                .uppercase()
                                .take(6)
                            hexValue = normalized
                            if (normalized.length == 6) {
                                normalized.toIntOrNull(16)?.let { rgb ->
                                    val parsedColor = rgb or 0xFF000000.toInt()
                                    val parsedHsv = FloatArray(3).also {
                                        AndroidColor.colorToHSV(parsedColor, it)
                                    }
                                    hue = parsedHsv[0]
                                    saturation = parsedHsv[1]
                                    brightness = parsedHsv[2]
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(MR.strings.epub_reader_hex_color)) },
                        prefix = { Text("#") },
                        singleLine = true,
                        isError = hexValue.isNotEmpty() && !isHexValid,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (isHexValid) onConfirm(color) },
                        ),
                    )
                    ColorComponentSlider(
                        label = stringResource(MR.strings.epub_reader_color_hue),
                        value = hue,
                        valueRange = 0f..360f,
                        onValueChange = { hue = it },
                    )
                    ColorComponentSlider(
                        label = stringResource(MR.strings.epub_reader_color_saturation),
                        value = saturation,
                        valueRange = 0f..1f,
                        onValueChange = { saturation = it },
                    )
                    ColorComponentSlider(
                        label = stringResource(MR.strings.epub_reader_brightness),
                        value = brightness,
                        valueRange = 0f..1f,
                        onValueChange = { brightness = it },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismissRequest) {
                            Text(stringResource(MR.strings.action_cancel))
                        }
                        TextButton(
                            enabled = isHexValid,
                            onClick = { onConfirm(color) },
                        ) {
                            Text(stringResource(MR.strings.action_ok))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpubDialogBrightnessContainer(
    followReaderBrightness: Boolean,
    readerPreferences: ReaderPreferences,
    content: @Composable () -> Unit,
) {
    if (followReaderBrightness) {
        EpubPreferenceBrightnessAwareDialogContent(readerPreferences, content = content)
    } else {
        content()
    }
}

@Composable
private fun ColorComponentSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 12.dp),
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
    )
}

private fun Char.isHexDigit(): Boolean {
    return this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}

@Composable
private fun ReadingModeSettingsSheet(
    preferences: EpubLayoutPreferences,
    readerPreferences: ReaderPreferences,
    onDismissRequest: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        EpubPreferenceBrightnessAwareDialogContent(readerPreferences) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            ) {
                DialogTitle(stringResource(MR.strings.epub_reader_reading_mode_settings))
                ReadingModeOptions(
                    preferences = preferences,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun EpubPreferenceBrightnessAwareDialogContent(
    readerPreferences: ReaderPreferences,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val customBrightnessEnabled by readerPreferences.customBrightness.changes()
        .collectAsState(readerPreferences.customBrightness.get())
    val customBrightnessValue by readerPreferences.customBrightnessValue.changes()
        .collectAsState(readerPreferences.customBrightnessValue.get())
    val brightnessState = remember(customBrightnessEnabled, customBrightnessValue) {
        calculateEpubBrightness(customBrightnessEnabled, customBrightnessValue)
    }

    EpubBrightnessAwareDialogContent(
        brightnessState = brightnessState,
        modifier = modifier,
        content = content,
    )
}

@Composable
private fun ReadingModeOptions(
    preferences: EpubLayoutPreferences,
    modifier: Modifier = Modifier,
) {
    val readingMode by preferences.readingMode.changes().collectAsState(preferences.readingMode.get())
    val pageDirection by preferences.pageDirection.changes().collectAsState(preferences.pageDirection.get())

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReadingModeOption(
                selected = readingMode == EpubLayoutPreferences.ReadingMode.PAGINATED &&
                    pageDirection == EpubLayoutPreferences.PageDirection.LEFT_TO_RIGHT,
                icon = R.drawable.ic_reader_ltr_24dp,
                label = stringResource(MR.strings.left_to_right_viewer),
                onClick = {
                    preferences.readingMode.set(EpubLayoutPreferences.ReadingMode.PAGINATED)
                    preferences.pageDirection.set(EpubLayoutPreferences.PageDirection.LEFT_TO_RIGHT)
                },
                modifier = Modifier.weight(1f),
            )
            ReadingModeOption(
                selected = readingMode == EpubLayoutPreferences.ReadingMode.PAGINATED &&
                    pageDirection == EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT,
                icon = R.drawable.ic_reader_rtl_24dp,
                label = stringResource(MR.strings.right_to_left_viewer),
                onClick = {
                    preferences.readingMode.set(EpubLayoutPreferences.ReadingMode.PAGINATED)
                    preferences.pageDirection.set(EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT)
                },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReadingModeOption(
                selected = readingMode == EpubLayoutPreferences.ReadingMode.SCROLL,
                icon = R.drawable.ic_reader_webtoon_24dp,
                label = stringResource(MR.strings.pref_epub_layout_scrolled),
                onClick = { preferences.readingMode.set(EpubLayoutPreferences.ReadingMode.SCROLL) },
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReadingModeOption(
    selected: Boolean,
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.semantics {
            role = Role.RadioButton
            this.selected = selected
        },
        shape = MaterialTheme.shapes.extraLarge,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CompactSettingRow(
    label: String,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        content()
    }
}

@Composable
private fun TypographySettingsSheet(
    preferences: EpubLayoutPreferences,
    readerPreferences: ReaderPreferences,
    onDismissRequest: () -> Unit,
) {
    var showCustomSpacing by rememberSaveable { mutableStateOf(false) }
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        EpubPreferenceBrightnessAwareDialogContent(readerPreferences) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
            ) {
                DialogTitle(stringResource(MR.strings.epub_reader_font_and_spacing))
                SpacingPresetSection(
                    preferences = preferences,
                    onOpenCustom = { showCustomSpacing = true },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                FontFamilySection(preferences)
                PublisherStylesSection(preferences)
            }
        }
    }

    if (showCustomSpacing) {
        CustomSpacingDialog(
            preferences = preferences,
            readerPreferences = readerPreferences,
            followReaderBrightness = true,
            onDismissRequest = { showCustomSpacing = false },
        )
    }
}

@Composable
private fun SpacingPresetSection(
    preferences: EpubLayoutPreferences,
    onOpenCustom: () -> Unit,
) {
    val spacingMode by preferences.spacingMode.changes().collectAsState(preferences.spacingMode.get())

    ChipSection(title = stringResource(MR.strings.pref_epub_spacing)) {
        listOf(
            EpubLayoutPreferences.SpacingMode.COMPACT,
            EpubLayoutPreferences.SpacingMode.STANDARD,
            EpubLayoutPreferences.SpacingMode.RELAXED,
        ).forEach { mode ->
            val label = when (mode) {
                EpubLayoutPreferences.SpacingMode.RELAXED -> stringResource(MR.strings.pref_epub_spacing_relaxed)
                EpubLayoutPreferences.SpacingMode.STANDARD -> stringResource(MR.strings.pref_epub_spacing_standard)
                EpubLayoutPreferences.SpacingMode.COMPACT -> stringResource(MR.strings.pref_epub_spacing_compact)
                else -> error("Only visual spacing presets are rendered here")
            }
            val icon = when (mode) {
                EpubLayoutPreferences.SpacingMode.RELAXED -> Icons.Outlined.DensityLarge
                EpubLayoutPreferences.SpacingMode.STANDARD -> Icons.Outlined.DensityMedium
                EpubLayoutPreferences.SpacingMode.COMPACT -> Icons.Outlined.DensitySmall
                else -> error("Only visual spacing presets are rendered here")
            }
            Surface(
                onClick = { preferences.applySpacingMode(mode) },
                color = if (spacingMode == mode) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (spacingMode == mode) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .size(width = 52.dp, height = 44.dp)
                    .semantics { contentDescription = label },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null)
                }
            }
        }
        FilterChip(
            selected = spacingMode == EpubLayoutPreferences.SpacingMode.NONE,
            onClick = { preferences.applySpacingMode(EpubLayoutPreferences.SpacingMode.NONE) },
            label = { Text(stringResource(MR.strings.pref_epub_spacing_none)) },
        )
        FilterChip(
            selected = spacingMode == EpubLayoutPreferences.SpacingMode.CUSTOM,
            onClick = {
                preferences.applySpacingMode(EpubLayoutPreferences.SpacingMode.CUSTOM)
                onOpenCustom()
            },
            label = { Text(stringResource(MR.strings.pref_epub_spacing_custom)) },
        )
    }
}

@Composable
private fun CustomSpacingDialog(
    preferences: EpubLayoutPreferences,
    readerPreferences: ReaderPreferences,
    followReaderBrightness: Boolean,
    onDismissRequest: () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        EpubDialogBrightnessContainer(
            followReaderBrightness = followReaderBrightness,
            readerPreferences = readerPreferences,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 640.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                ) {
                    Text(
                        text = stringResource(MR.strings.pref_epub_custom_spacing),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = stringResource(MR.strings.pref_epub_spacing_disables_publisher_styles),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    SpacingValueRow(
                        label = stringResource(MR.strings.pref_epub_line_height),
                        value = preferences.lineHeight.changes().collectAsState(preferences.lineHeight.get()).value,
                        range = 1.0f..2.0f,
                        step = 0.1f,
                        onValueChanged = { preferences.applyCustomSpacing(preferences.lineHeight, it) },
                    )
                    SpacingValueRow(
                        label = stringResource(MR.strings.pref_epub_paragraph_spacing),
                        value = preferences.paragraphSpacing.changes()
                            .collectAsState(preferences.paragraphSpacing.get()).value,
                        range = 0.0f..2.0f,
                        step = 0.1f,
                        onValueChanged = { preferences.applyCustomSpacing(preferences.paragraphSpacing, it) },
                    )
                    SpacingValueRow(
                        label = stringResource(MR.strings.pref_epub_paragraph_indent),
                        value = preferences.paragraphIndent.changes()
                            .collectAsState(preferences.paragraphIndent.get()).value,
                        range = 0.0f..3.0f,
                        step = 0.2f,
                        onValueChanged = { preferences.applyCustomSpacing(preferences.paragraphIndent, it) },
                    )
                    SpacingValueRow(
                        label = stringResource(MR.strings.pref_epub_vertical_margins),
                        value = preferences.verticalMargins.changes()
                            .collectAsState(preferences.verticalMargins.get()).value,
                        range = 0.0f..2.0f,
                        step = 0.1f,
                        onValueChanged = { preferences.applyCustomSpacing(preferences.verticalMargins, it) },
                    )
                    SpacingValueRow(
                        label = stringResource(MR.strings.pref_epub_horizontal_margins),
                        value = preferences.pageMargins.changes().collectAsState(preferences.pageMargins.get()).value,
                        range = 0.0f..4.0f,
                        step = 0.1f,
                        onValueChanged = { preferences.applyCustomSpacing(preferences.pageMargins, it) },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismissRequest) {
                            Text(stringResource(MR.strings.action_ok))
                        }
                    }
                }
            }
        }
    }
}

private fun EpubLayoutPreferences.applyCustomSpacing(
    preference: tachiyomi.core.common.preference.Preference<Float>,
    value: Float,
) {
    spacingMode.set(EpubLayoutPreferences.SpacingMode.CUSTOM)
    publisherStyles.set(false)
    preference.set(value)
}

@Composable
private fun SpacingValueRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onValueChanged: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        IconButton(
            enabled = value > range.start,
            onClick = { onValueChanged((value - step).roundedToTenth().coerceAtLeast(range.start)) },
        ) {
            Icon(Icons.Outlined.Remove, contentDescription = null)
        }
        Text(
            text = value.formatOneDecimal(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(48.dp),
        )
        IconButton(
            enabled = value < range.endInclusive,
            onClick = { onValueChanged((value + step).roundedToTenth().coerceAtMost(range.endInclusive)) },
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
        }
    }
}

private fun Float.roundedToTenth(): Float = (this * 10).roundToInt() / 10f

@Composable
private fun MoreReadingSettingsSheet(
    preferences: EpubLayoutPreferences,
    readerPreferences: ReaderPreferences,
    epubReaderPreferences: EpubReaderPreferences,
    onDismissRequest: () -> Unit,
) {
    val tabTitles = persistentListOf(
        stringResource(MR.strings.pref_category_reading),
        stringResource(MR.strings.pref_category_general),
        stringResource(MR.strings.custom_filter),
    )
    val pagerState = rememberPagerState { tabTitles.size }
    val maxDialogHeight = LocalConfiguration.current.screenHeightDp.dp * 0.82f

    TabbedDialog(
        modifier = Modifier.heightIn(max = maxDialogHeight),
        onDismissRequest = onDismissRequest,
        tabTitles = tabTitles,
        pagerState = pagerState,
        contentOverlay = {
            EpubPreferenceBrightnessAwareDialogContent(
                readerPreferences = readerPreferences,
                modifier = Modifier.matchParentSize(),
            ) {}
        },
    ) { page ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> EpubReadingSettingsPage(preferences, readerPreferences)
                1 -> EpubGeneralSettingsPage(readerPreferences, epubReaderPreferences)
                2 -> EpubFilterSettingsPage(readerPreferences)
            }
        }
    }
}

@Composable
private fun ColumnScope.EpubReadingSettingsPage(
    preferences: EpubLayoutPreferences,
    readerPreferences: ReaderPreferences,
) {
    val orientationValue by readerPreferences.defaultOrientationType.changes()
        .collectAsState(readerPreferences.defaultOrientationType.get())
    val currentOrientation = ReaderOrientation.fromPreference(orientationValue)
    ChipSection(title = stringResource(MR.strings.rotation_type)) {
        ReaderOrientation.entries
            .filterNot { it == ReaderOrientation.FREE }
            .forEach { orientation ->
                FilterChip(
                    selected = currentOrientation == orientation,
                    onClick = { readerPreferences.defaultOrientationType.set(orientation.flagValue) },
                    label = { Text(stringResource(orientation.stringRes)) },
                )
            }
    }

    HeadingItem(stringResource(MR.strings.pref_reader_navigation))
    val readWithVolumeKeys by preferences.readWithVolumeKeys.changes()
        .collectAsState(preferences.readWithVolumeKeys.get())
    CheckboxItem(
        label = stringResource(MR.strings.pref_read_with_volume_keys),
        pref = preferences.readWithVolumeKeys,
    )
    if (readWithVolumeKeys) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_read_with_volume_keys_inverted),
            pref = preferences.readWithVolumeKeysInverted,
        )
    }
    TapZoneSection(preferences, readerPreferences)
    CheckboxItem(
        label = stringResource(MR.strings.pref_show_navigation_mode),
        pref = readerPreferences.showNavigationOverlayOnStart,
    )
}

@Composable
private fun ColumnScope.EpubGeneralSettingsPage(
    readerPreferences: ReaderPreferences,
    epubReaderPreferences: EpubReaderPreferences,
) {
    CheckboxItem(
        label = stringResource(MR.strings.pref_epub_persist_reader_settings),
        pref = epubReaderPreferences.persistReaderSettingsChanges,
    )

    CheckboxItem(
        label = stringResource(MR.strings.epub_reader_show_reading_progress),
        pref = readerPreferences.showPageNumber,
    )

    val fullscreen by readerPreferences.fullscreen.changes().collectAsState(readerPreferences.fullscreen.get())
    CheckboxItem(
        label = stringResource(MR.strings.pref_fullscreen),
        pref = readerPreferences.fullscreen,
    )
    if (fullscreen && LocalActivity.current?.hasDisplayCutout() == true) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_cutout_short),
            pref = readerPreferences.drawUnderCutout,
        )
    }
    CheckboxItem(
        label = stringResource(MR.strings.pref_keep_screen_on),
        pref = readerPreferences.keepScreenOn,
    )
}

@Composable
private fun ColumnScope.EpubFilterSettingsPage(readerPreferences: ReaderPreferences) {
    val colorFilter by readerPreferences.colorFilter.changes().collectAsState(readerPreferences.colorFilter.get())
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_color_filter),
        pref = readerPreferences.colorFilter,
    )
    if (colorFilter) {
        val colorValue by readerPreferences.colorFilterValue.changes()
            .collectAsState(readerPreferences.colorFilterValue.get())
        ColorSlider(
            value = colorValue.red,
            label = stringResource(MR.strings.color_filter_r_value),
            onChange = { newValue ->
                readerPreferences.colorFilterValue.set(updateColorValue(colorValue, newValue, RED_MASK, 16))
            },
        )
        ColorSlider(
            value = colorValue.green,
            label = stringResource(MR.strings.color_filter_g_value),
            onChange = { newValue ->
                readerPreferences.colorFilterValue.set(updateColorValue(colorValue, newValue, GREEN_MASK, 8))
            },
        )
        ColorSlider(
            value = colorValue.blue,
            label = stringResource(MR.strings.color_filter_b_value),
            onChange = { newValue ->
                readerPreferences.colorFilterValue.set(updateColorValue(colorValue, newValue, BLUE_MASK, 0))
            },
        )
        ColorSlider(
            value = colorValue.alpha,
            label = stringResource(MR.strings.color_filter_a_value),
            onChange = { newValue ->
                readerPreferences.colorFilterValue.set(updateColorValue(colorValue, newValue, ALPHA_MASK, 24))
            },
        )

        val filterMode by readerPreferences.colorFilterMode.changes()
            .collectAsState(readerPreferences.colorFilterMode.get())
        ChipSection(title = stringResource(MR.strings.pref_color_filter_mode)) {
            ReaderPreferences.ColorFilterMode.forEachIndexed { index, mode ->
                FilterChip(
                    selected = filterMode == index,
                    onClick = { readerPreferences.colorFilterMode.set(index) },
                    label = { Text(stringResource(mode.first)) },
                )
            }
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_grayscale),
        pref = readerPreferences.grayscale,
    )
    CheckboxItem(
        label = stringResource(MR.strings.pref_inverted_colors),
        pref = readerPreferences.invertedColors,
    )
}

@Composable
private fun ColorSlider(
    value: Int,
    label: String,
    onChange: (Int) -> Unit,
) {
    SliderItem(
        value = value,
        valueRange = 0..255,
        steps = 0,
        label = label,
        onChange = onChange,
        pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}

@Composable
private fun DialogTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

@Composable
private fun TapZoneSection(
    preferences: EpubLayoutPreferences,
    readerPreferences: ReaderPreferences,
) {
    val currentReadingMode by preferences.readingMode.changes().collectAsState(preferences.readingMode.get())
    val navigationModePreference = when (currentReadingMode) {
        EpubLayoutPreferences.ReadingMode.SCROLL -> readerPreferences.navigationModeWebtoon
        EpubLayoutPreferences.ReadingMode.PAGINATED -> readerPreferences.navigationModePager
    }
    val invertModePreference = when (currentReadingMode) {
        EpubLayoutPreferences.ReadingMode.SCROLL -> readerPreferences.webtoonNavInverted
        EpubLayoutPreferences.ReadingMode.PAGINATED -> readerPreferences.pagerNavInverted
    }
    val navigationMode by navigationModePreference.changes().collectAsState(navigationModePreference.get())
    val invertMode by invertModePreference.changes().collectAsState(invertModePreference.get())

    ChipSection(title = stringResource(MR.strings.pref_viewer_nav)) {
        ReaderPreferences.TapZones.forEachIndexed { index, item ->
            FilterChip(
                selected = navigationMode == index,
                onClick = { navigationModePreference.set(index) },
                label = { Text(stringResource(item)) },
            )
        }
    }

    if (navigationMode != 5) {
        ChipSection(title = stringResource(MR.strings.pref_read_with_tapping_inverted)) {
            ReaderPreferences.TappingInvertMode.entries.forEach { mode ->
                FilterChip(
                    selected = invertMode == mode,
                    onClick = { invertModePreference.set(mode) },
                    label = { Text(stringResource(mode.titleRes)) },
                )
            }
        }
    }
}

@Composable
private fun FontFamilySection(preferences: EpubLayoutPreferences) {
    val currentFont by preferences.fontFamily.changes().collectAsState(preferences.fontFamily.get())

    ChipSection(title = stringResource(MR.strings.pref_epub_font_family)) {
        EpubLayoutPreferences.FontFamily.entries.forEach { fontFamily ->
            FilterChip(
                selected = currentFont == fontFamily,
                onClick = { preferences.fontFamily.set(fontFamily) },
                label = {
                    Text(
                        text = fontFamily.label(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.semantics { role = Role.RadioButton },
            )
        }
    }
}

@Composable
private fun PublisherStylesSection(preferences: EpubLayoutPreferences) {
    val publisherStyles by preferences.publisherStyles.changes().collectAsState(preferences.publisherStyles.get())

    Surface(
        onClick = { preferences.publisherStyles.set(!publisherStyles) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (publisherStyles) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (publisherStyles) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(MR.strings.pref_epub_publisher_styles),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = publisherStyles,
                onCheckedChange = null,
            )
        }
    }
}

@Composable
private fun ChipSection(
    title: String,
    content: @Composable FlowRowScope.() -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun EpubLayoutPreferences.FontFamily.label(): String {
    return when (this) {
        EpubLayoutPreferences.FontFamily.ORIGINAL -> stringResource(MR.strings.pref_epub_font_original)
        EpubLayoutPreferences.FontFamily.SERIF -> stringResource(MR.strings.pref_epub_font_serif)
        EpubLayoutPreferences.FontFamily.SANS_SERIF -> stringResource(MR.strings.pref_epub_font_sans_serif)
        EpubLayoutPreferences.FontFamily.MONOSPACE -> stringResource(MR.strings.pref_epub_font_monospace)
        EpubLayoutPreferences.FontFamily.OPEN_DYSLEXIC -> stringResource(MR.strings.pref_epub_font_open_dyslexic)
    }
}

private fun EpubLayoutPreferences.Theme.swatchColor(): Color {
    return when (this) {
        EpubLayoutPreferences.Theme.LIGHT -> Color.White
        EpubLayoutPreferences.Theme.DARK -> Color.Black
        EpubLayoutPreferences.Theme.SEPIA -> Color(0xFFFBF0D9)
        EpubLayoutPreferences.Theme.MINT -> Color(0xFFC4EDC8)
        EpubLayoutPreferences.Theme.BLUE -> Color(0xFFE0F0FC)
        EpubLayoutPreferences.Theme.PINK -> Color(0xFFFBE4EE)
        EpubLayoutPreferences.Theme.GRAY -> Color(0xFFF1F3F5)
        EpubLayoutPreferences.Theme.CUSTOM -> Color(EpubLayoutPreferences.DEFAULT_CUSTOM_BACKGROUND_COLOR)
    }
}

private fun updateColorValue(currentColor: Int, color: Int, mask: Long, bitShift: Int): Int {
    return (color shl bitShift) or (currentColor and mask.inv().toInt())
}

private fun Float.formatOneDecimal(): String {
    return "%.1f".format(this)
}

private const val ALPHA_MASK: Long = 0xFF000000
private const val RED_MASK: Long = 0x00FF0000
private const val GREEN_MASK: Long = 0x0000FF00
private const val BLUE_MASK: Long = 0x000000FF
