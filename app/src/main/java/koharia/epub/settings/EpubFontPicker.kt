package koharia.epub.settings

import android.graphics.Typeface
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import eu.kanade.presentation.components.AdaptiveSheet
import koharia.epub.font.EpubFontFaceDescriptor
import koharia.epub.font.EpubFontFamilyDescriptor
import koharia.epub.font.EpubFontId
import koharia.epub.font.EpubFontImportFailure
import koharia.epub.font.EpubFontImportResult
import koharia.epub.font.EpubFontManager
import koharia.epub.font.EpubFontSource
import koharia.source.komga.KomgaScopedPreferenceStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun EpubFontPreference(
    preferences: EpubLayoutPreferences,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val manager = remember { Injekt.get<EpubFontManager>() }
    val catalog by manager.catalogState.collectAsState()
    val rawId by preferences.selectedFontId.changes().collectAsState(preferences.selectedFontId.get())
    val id = EpubFontId.fromPreference(rawId)
    val selected = catalog.allFamilies.firstOrNull { it.id == id }
        ?: catalog.builtInFamilies.first { it.id == EpubFontId.ORIGINAL }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(MR.strings.pref_epub_font_family)) },
        supportingContent = {
            Text(
                if (enabled) selected.localizedName() else stringResource(MR.strings.epub_font_fixed_layout_disabled),
            )
        },
        modifier = modifier.clickable(enabled = enabled) {
            if (onClick != null) onClick() else showPicker = true
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    if (showPicker && onClick == null) {
        EpubFontPickerSheet(
            preferences = preferences,
            manager = manager,
            onDismissRequest = { showPicker = false },
        )
    }
}

@Composable
internal fun EpubFontPickerSheet(
    preferences: EpubLayoutPreferences,
    manager: EpubFontManager,
    onDismissRequest: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        EpubFontPickerContent(
            preferences = preferences,
            manager = manager,
            modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp),
            showTitle = true,
            onFontSelected = {
                preferences.selectFont(it)
                onDismissRequest()
            },
        )
    }
}

@Composable
internal fun EpubFontPickerPage(
    preferences: EpubLayoutPreferences,
    manager: EpubFontManager,
    modifier: Modifier = Modifier,
) {
    EpubFontPickerContent(
        preferences = preferences,
        manager = manager,
        modifier = modifier,
        showTitle = false,
        onFontSelected = preferences::selectFont,
    )
}

@Composable
private fun EpubFontPickerContent(
    preferences: EpubLayoutPreferences,
    manager: EpubFontManager,
    modifier: Modifier,
    showTitle: Boolean,
    onFontSelected: (EpubFontId) -> Unit,
) {
    val scopedPreferenceStoreFactory = remember { Injekt.get<KomgaScopedPreferenceStoreFactory>() }
    val catalog by manager.catalogState.collectAsState()
    val rawSelectedId by preferences.selectedFontId.changes().collectAsState(preferences.selectedFontId.get())
    val selectedId = EpubFontId.fromPreference(rawSelectedId)
    val scope = rememberCoroutineScope()
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var expandedSeriesKey by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedFamilyId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingConflict by remember { mutableStateOf<List<android.net.Uri>?>(null) }
    var deleteCandidate by remember { mutableStateOf<EpubFontFamilyDescriptor?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    val invalidFormat = stringResource(MR.strings.epub_font_invalid_format)
    val tooLarge = stringResource(MR.strings.epub_font_file_too_large)
    val libraryFull = stringResource(MR.strings.epub_font_library_full)
    val readFailed = stringResource(MR.strings.epub_font_import_failed)
    val deleteFailed = stringResource(MR.strings.epub_font_delete_failed)
    val imported = stringResource(MR.strings.epub_font_import_success)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            when (val result = manager.importFonts(uris)) {
                is EpubFontImportResult.Success -> importMessage = imported
                is EpubFontImportResult.Conflict -> pendingConflict = uris
                is EpubFontImportResult.Failure -> importMessage = when (result.reason) {
                    EpubFontImportFailure.INVALID_FORMAT -> invalidFormat
                    EpubFontImportFailure.FILE_TOO_LARGE -> tooLarge
                    EpubFontImportFailure.LIBRARY_FULL -> libraryFull
                    EpubFontImportFailure.READ_FAILED -> readFailed
                }
            }
        }
    }
    val tabs = listOf(
        stringResource(MR.strings.epub_font_builtin),
        stringResource(MR.strings.epub_font_system),
        stringResource(MR.strings.epub_font_local),
    )
    val currentFamilies = when (tabIndex) {
        0 -> catalog.builtInFamilies
        1 -> catalog.systemFamilies
        else -> catalog.localFamilies
    }.filter { family ->
        query.isBlank() ||
            family.displayName.contains(query, ignoreCase = true) ||
            family.faces.any { it.familyName.contains(query, ignoreCase = true) } ||
            family.faces.any { it.postScriptName?.contains(query, ignoreCase = true) == true }
    }
    val currentSeries = currentFamilies.groupIntoFontSeries()

    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = if (showTitle) 12.dp else 4.dp, end = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showTitle) {
                Text(
                    text = stringResource(MR.strings.epub_font_choose),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            IconButton(onClick = {
                if (showSearch) {
                    query = ""
                    showSearch = false
                } else {
                    showSearch = true
                }
            }) {
                Icon(
                    imageVector = if (showSearch) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = stringResource(MR.strings.action_search_hint),
                )
            }
            IconButton(onClick = { launcher.launch(arrayOf("*/*")) }) {
                Icon(Icons.Outlined.UploadFile, contentDescription = stringResource(MR.strings.epub_font_import))
            }
        }
        AnimatedVisibility(visible = showSearch) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(MR.strings.action_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = {
                        query = ""
                        showSearch = false
                    }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(MR.strings.action_cancel))
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        top = 8.dp,
                        end = 16.dp,
                        bottom = 8.dp,
                    ),
            )
        }
        PrimaryTabRow(
            selectedTabIndex = tabIndex,
            containerColor = Color.Transparent,
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = tabIndex == index, onClick = { tabIndex = index }, text = { Text(title) })
            }
        }
        val loading = (tabIndex == 1 && catalog.isSystemLoading) || (tabIndex == 2 && catalog.isLocalLoading)
        if (loading) {
            Row(modifier = Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(currentSeries, key = { it.key }) { series ->
                    if (series.families.size == 1) {
                        val family = series.families.single()
                        EpubFontFamilyItemWithPreview(
                            family = family,
                            selectedId = selectedId,
                            expandedFamilyId = expandedFamilyId,
                            manager = manager,
                            onExpand = {
                                expandedFamilyId = if (expandedFamilyId == family.id.value) {
                                    null
                                } else {
                                    family.id.value
                                }
                            },
                            onSelect = { onFontSelected(family.id) },
                            onDelete = if (family.source == EpubFontSource.LOCAL) {
                                { deleteCandidate = family }
                            } else {
                                null
                            },
                        )
                    } else {
                        EpubFontSeriesItem(
                            series = series,
                            selectedFamily = series.families.firstOrNull { it.id == selectedId },
                            expanded = series.key == expandedSeriesKey || query.isNotBlank(),
                            onExpand = {
                                expandedSeriesKey = if (expandedSeriesKey == series.key) null else series.key
                            },
                        ) {
                            series.families.forEachIndexed { index, family ->
                                key(family.id.value) {
                                    if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                                    EpubFontFamilyItemWithPreview(
                                        family = family,
                                        selectedId = selectedId,
                                        expandedFamilyId = expandedFamilyId,
                                        manager = manager,
                                        onExpand = {
                                            expandedFamilyId = if (expandedFamilyId == family.id.value) {
                                                null
                                            } else {
                                                family.id.value
                                            }
                                        },
                                        onSelect = { onFontSelected(family.id) },
                                        onDelete = if (family.source == EpubFontSource.LOCAL) {
                                            { deleteCandidate = family }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingConflict?.let { uris ->
        AlertDialog(
            onDismissRequest = { pendingConflict = null },
            title = { Text(stringResource(MR.strings.epub_font_conflict_title)) },
            text = { Text(stringResource(MR.strings.epub_font_conflict_message)) },
            dismissButton = {
                TextButton(onClick = { pendingConflict = null }) { Text(stringResource(MR.strings.action_cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingConflict = null
                    scope.launch {
                        importMessage = when (manager.importFonts(uris, replaceConflicts = true)) {
                            is EpubFontImportResult.Success -> imported
                            else -> readFailed
                        }
                    }
                }) { Text(stringResource(MR.strings.epub_font_replace)) }
            },
        )
    }
    deleteCandidate?.let { family ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(MR.strings.epub_font_delete_title)) },
            text = { Text(stringResource(MR.strings.epub_font_delete_message, family.displayName)) },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text(stringResource(MR.strings.action_cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    scope.launch {
                        if (manager.deleteFamily(family.id)) {
                            scopedPreferenceStoreFactory.resetEpubFontSelection(family.id)
                        } else {
                            importMessage = deleteFailed
                        }
                    }
                }) { Text(stringResource(MR.strings.action_delete)) }
            },
        )
    }
    importMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { importMessage = null },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { importMessage = null }) { Text(stringResource(MR.strings.action_ok)) }
            },
        )
    }
}

@Composable
private fun EpubFontFamilyItemWithPreview(
    family: EpubFontFamilyDescriptor,
    selectedId: EpubFontId,
    expandedFamilyId: String?,
    manager: EpubFontManager,
    onExpand: () -> Unit,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val typeface by produceState<Typeface?>(null, family.fingerprint) {
        value = withContext(Dispatchers.IO) { manager.previewTypeface(family.id) }
    }
    EpubFontFamilyItem(
        family = family,
        selected = family.id == selectedId,
        expanded = family.id.value == expandedFamilyId,
        typeface = typeface,
        onExpand = onExpand,
        onSelect = onSelect,
        onDelete = onDelete,
    )
}

@Composable
private fun EpubFontSeriesItem(
    series: EpubFontSeries,
    selectedFamily: EpubFontFamilyDescriptor?,
    expanded: Boolean,
    onExpand: () -> Unit,
    content: @Composable () -> Unit,
) {
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "fontSeriesArrow")
    Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Surface(
            onClick = onExpand,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = if (selectedFamily != null) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            contentColor = if (selectedFamily != null) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = series.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(MR.strings.epub_font_families_count, series.families.size),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    selectedFamily?.let {
                        Text(
                            text = it.localizedName(),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onExpand) {
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) {
                                MR.strings.epub_font_series_collapse
                            } else {
                                MR.strings.epub_font_series_expand
                            },
                        ),
                        modifier = Modifier.graphicsLayer(rotationZ = arrowRotation),
                    )
                }
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun EpubFontFamilyItem(
    family: EpubFontFamilyDescriptor,
    selected: Boolean,
    expanded: Boolean,
    typeface: Typeface?,
    onExpand: () -> Unit,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val canExpand = family.faces.isNotEmpty()
    val previewText = stringResource(MR.strings.epub_font_preview_sample)
    val secondaryTextColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val previewColor = secondaryTextColor.toArgb()
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "fontFamilyArrow")
    Surface(
        onClick = if (canExpand) onExpand else onSelect,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
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
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 68.dp)
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = onSelect)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = family.localizedName(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = family.source.localizedName(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (canExpand) {
                            Text(text = " · ", style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = stringResource(MR.strings.epub_font_faces_count, family.faces.size),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    AndroidView(
                        factory = { context -> TextView(context).apply { includeFontPadding = false } },
                        update = { view ->
                            view.text = previewText
                            view.typeface = typeface ?: Typeface.DEFAULT
                            view.setTextColor(previewColor)
                        },
                    )
                }
                onDelete?.let { action ->
                    IconButton(onClick = action, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.strings.action_delete))
                    }
                }
                if (canExpand) {
                    IconButton(onClick = onExpand, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.ExpandMore,
                            contentDescription = stringResource(
                                if (expanded) {
                                    MR.strings.epub_font_family_collapse
                                } else {
                                    MR.strings.epub_font_family_expand
                                },
                            ),
                            modifier = Modifier.graphicsLayer(rotationZ = arrowRotation),
                        )
                    }
                }
            }
            AnimatedVisibility(visible = expanded && canExpand) {
                Column(modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp)) {
                    HorizontalDivider(
                        modifier = Modifier.padding(bottom = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    family.faces
                        .sortedWith(compareBy({ it.italic }, { it.minWeight }, { it.maxWeight }))
                        .forEach { face ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = face.localizedStyleName(),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    face.postScriptName?.takeIf { it.isNotBlank() }?.let { postScriptName ->
                                        Text(
                                            text = postScriptName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = secondaryTextColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = if (face.isVariableWeight) {
                                        "${face.minWeight}–${face.maxWeight}"
                                    } else {
                                        face.weight.toString()
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun EpubFontFaceDescriptor.localizedStyleName(): String {
    val weightName = when {
        isVariableWeight -> stringResource(MR.strings.epub_font_style_variable)
        weight <= 150 -> stringResource(MR.strings.epub_font_weight_thin)
        weight <= 250 -> stringResource(MR.strings.epub_font_weight_extra_light)
        weight <= 350 -> stringResource(MR.strings.epub_font_weight_light)
        weight <= 450 -> stringResource(MR.strings.epub_font_weight_regular)
        weight <= 550 -> stringResource(MR.strings.epub_font_weight_medium)
        weight <= 650 -> stringResource(MR.strings.epub_font_weight_semi_bold)
        weight <= 750 -> stringResource(MR.strings.epub_font_weight_bold)
        weight <= 850 -> stringResource(MR.strings.epub_font_weight_extra_bold)
        else -> stringResource(MR.strings.epub_font_weight_black)
    }
    return if (italic) {
        stringResource(MR.strings.epub_font_style_italic_format, weightName)
    } else {
        weightName
    }
}

private data class EpubFontSeries(
    val key: String,
    val name: String,
    val families: List<EpubFontFamilyDescriptor>,
)

private fun List<EpubFontFamilyDescriptor>.groupIntoFontSeries(): List<EpubFontSeries> {
    return groupBy { it.seriesName() }
        .map { (name, families) ->
            EpubFontSeries(
                key = "${families.first().source.name}:$name",
                name = name,
                families = families,
            )
        }
}

private fun EpubFontFamilyDescriptor.seriesName(): String {
    val normalizedName = displayName.trim().replace(Regex("\\s+"), " ")
    val knownSeries = listOf("Noto Sans", "Noto Serif")
    return knownSeries.firstOrNull { series ->
        normalizedName.equals(series, ignoreCase = true) ||
            normalizedName.startsWith("$series ", ignoreCase = true)
    } ?: normalizedName
}

@Composable
private fun EpubFontFamilyDescriptor.localizedName(): String = when (id) {
    EpubFontId.ORIGINAL -> stringResource(MR.strings.pref_epub_font_original)
    EpubFontId.SERIF -> stringResource(MR.strings.pref_epub_font_serif)
    EpubFontId.SANS_SERIF -> stringResource(MR.strings.pref_epub_font_sans_serif)
    EpubFontId.MONOSPACE -> stringResource(MR.strings.pref_epub_font_monospace)
    EpubFontId.CURSIVE -> stringResource(MR.strings.pref_epub_font_cursive)
    EpubFontId.OPEN_DYSLEXIC -> stringResource(MR.strings.pref_epub_font_open_dyslexic)
    EpubFontId("${EpubFontId.SYSTEM_PREFIX}generic-serif") -> stringResource(MR.strings.epub_font_system_serif)
    EpubFontId("${EpubFontId.SYSTEM_PREFIX}generic-sans-serif") ->
        stringResource(MR.strings.epub_font_system_sans_serif)
    EpubFontId("${EpubFontId.SYSTEM_PREFIX}generic-monospace") -> stringResource(MR.strings.epub_font_system_monospace)
    else -> displayName
}

@Composable
private fun EpubFontSource.localizedName(): String = when (this) {
    EpubFontSource.BUILTIN -> stringResource(MR.strings.epub_font_builtin)
    EpubFontSource.SYSTEM -> stringResource(MR.strings.epub_font_system)
    EpubFontSource.LOCAL -> stringResource(MR.strings.epub_font_local)
}
