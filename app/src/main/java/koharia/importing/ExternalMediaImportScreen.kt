package koharia.importing

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.system.toast
import koharia.connection.ConnectionLibraryShelf
import koharia.connection.ConnectionMediaImportDestination
import koharia.connection.ConnectionPreferences
import koharia.connection.ui.LibraryConnectionProfilesScreen
import koharia.epub.EpubReaderLauncher
import koharia.source.local.LocalLibraryEntryOpenManager
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class ExternalMediaImportScreen(
    private val uriValues: List<String>,
    private val startAtImportConfiguration: Boolean = false,
    private val openImmediately: Boolean = false,
    private val skipActionSelection: Boolean = startAtImportConfiguration || openImmediately,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val flowHost = context as? ExternalMediaFlowHost
        val screenModel = rememberScreenModel(
            tag = uriValues.joinToString() + startAtImportConfiguration + openImmediately,
        ) {
            ExternalMediaImportScreenModel(
                context = context.applicationContext,
                uriValues = uriValues,
                connectionPreferences = Injekt.get<ConnectionPreferences>(),
                sourceManager = Injekt.get<SourceManager>(),
                mangaRepository = Injekt.get<MangaRepository>(),
                openManager = IncomingMediaOpenManager(
                    context = context.applicationContext,
                    mangaRepository = Injekt.get<MangaRepository>(),
                    chapterRepository = Injekt.get<ChapterRepository>(),
                    epubReaderLauncher = EpubReaderLauncher(),
                ),
                localLibraryEntryOpenManager = LocalLibraryEntryOpenManager(
                    syncChaptersWithSource = Injekt.get(),
                    chapterRepository = Injekt.get(),
                    epubReaderLauncher = EpubReaderLauncher(),
                ),
                initialStep = when {
                    openImmediately -> ExternalMediaImportScreenModel.Step.OPENING
                    startAtImportConfiguration -> ExternalMediaImportScreenModel.Step.IMPORT_CONFIGURATION
                    else -> ExternalMediaImportScreenModel.Step.ACTIONS
                },
            )
        }
        val state by screenModel.state.collectAsState()
        var dialog by remember { mutableStateOf<SelectionDialog?>(null) }
        var resetNavigationWhenCovered by remember { mutableStateOf(false) }
        val lifecycleOwner = LocalLifecycleOwner.current

        DisposableEffect(lifecycleOwner, navigator, flowHost) {
            val observer = LifecycleEventObserver { _, event ->
                if (flowHost == null && event == Lifecycle.Event.ON_STOP && resetNavigationWhenCovered) {
                    navigator.popUntilRoot()
                    resetNavigationWhenCovered = false
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        BackHandler(
            enabled = flowHost != null || state.step != ExternalMediaImportScreenModel.Step.ACTIONS,
            onBack = {
                when (state.step) {
                    ExternalMediaImportScreenModel.Step.SERIES_SELECTION -> screenModel.showImportConfiguration()
                    ExternalMediaImportScreenModel.Step.IMPORT_CONFIGURATION -> {
                        if (skipActionSelection) {
                            flowHost?.closeFlow() ?: navigator.pop()
                        } else {
                            screenModel.showActions()
                        }
                    }
                    ExternalMediaImportScreenModel.Step.OPENING -> flowHost?.closeFlow() ?: navigator.pop()
                    ExternalMediaImportScreenModel.Step.ACTIONS -> flowHost?.closeFlow() ?: Unit
                }
            },
        )

        Scaffold(
            topBar = {
                if (state.step == ExternalMediaImportScreenModel.Step.SERIES_SELECTION) {
                    SearchToolbar(
                        searchQuery = state.existingSeriesSearchQuery,
                        onChangeSearchQuery = screenModel::setExistingSeriesSearchQuery,
                        titleContent = { Text(stringResource(MR.strings.external_media_choose_existing_series)) },
                        navigateUp = screenModel::showImportConfiguration,
                        scrollBehavior = it,
                    )
                } else {
                    AppBar(
                        title = stringResource(
                            if (state.step == ExternalMediaImportScreenModel.Step.IMPORT_CONFIGURATION) {
                                MR.strings.external_media_import_configuration_title
                            } else if (state.step == ExternalMediaImportScreenModel.Step.OPENING) {
                                MR.strings.external_media_action_open
                            } else {
                                MR.strings.external_media_import_title
                            },
                        ),
                        navigateUp = if (
                            state.step == ExternalMediaImportScreenModel.Step.IMPORT_CONFIGURATION
                        ) {
                            {
                                if (skipActionSelection) {
                                    flowHost?.closeFlow() ?: navigator.pop()
                                } else {
                                    screenModel.showActions()
                                }
                            }
                        } else if (state.step == ExternalMediaImportScreenModel.Step.OPENING) {
                            {
                                flowHost?.closeFlow() ?: navigator.pop()
                                Unit
                            }
                        } else {
                            {
                                flowHost?.closeFlow() ?: navigator.pop()
                                Unit
                            }
                        },
                        scrollBehavior = it,
                    )
                }
            },
            bottomBar = {
                if (
                    state.step == ExternalMediaImportScreenModel.Step.IMPORT_CONFIGURATION &&
                    state.connections.isNotEmpty() &&
                    state.loadFailure == null
                ) {
                    Button(
                        onClick = screenModel::import,
                        enabled = state.canImport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        if (state.isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(
                            text = stringResource(MR.strings.external_media_action_import),
                            modifier = Modifier.padding(start = if (state.isImporting) 8.dp else 0.dp),
                        )
                    }
                }
            },
        ) { contentPadding ->
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(contentPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.loadFailure != null -> {
                    ImportFailureContent(
                        failure = state.loadFailure!!,
                        onConfigure = {
                            navigator.push(LibraryConnectionProfilesScreen(openAddDialog = true))
                        },
                        onRetry = screenModel::reload,
                        modifier = Modifier.padding(contentPadding),
                    )
                }
                else -> {
                    ScrollbarLazyColumn(contentPadding = contentPadding) {
                        if (state.step == ExternalMediaImportScreenModel.Step.OPENING) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(48.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        } else if (state.step == ExternalMediaImportScreenModel.Step.SERIES_SELECTION) {
                            when {
                                state.isLoadingExistingSeries -> item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(48.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                                state.filteredExistingSeries.isEmpty() -> item {
                                    TextPreferenceWidget(
                                        title = stringResource(MR.strings.external_media_no_existing_series),
                                        subtitle = if (state.existingSeriesSearchQuery.isNullOrBlank()) {
                                            stringResource(MR.strings.external_media_no_existing_series_summary)
                                        } else {
                                            stringResource(MR.strings.external_media_no_matching_series)
                                        },
                                    )
                                }
                                else -> items(
                                    items = state.filteredExistingSeries,
                                    key = { it.id },
                                ) { series ->
                                    TextPreferenceWidget(
                                        title = series.name,
                                        subtitle = state.availableShelves
                                            .firstOrNull { it.id == series.shelfId }
                                            ?.name,
                                        widget = {
                                            RadioButton(
                                                selected = series.id == state.selectedExistingSeriesId,
                                                onClick = null,
                                            )
                                        },
                                        onPreferenceClick = { screenModel.selectExistingSeries(series.id) },
                                    )
                                }
                            }
                        } else if (state.step == ExternalMediaImportScreenModel.Step.ACTIONS) {
                            item {
                                PreferenceGroupHeader(
                                    title = stringResource(MR.strings.external_media_files_group),
                                )
                            }
                            items(state.items, key = { it.uri }) { item ->
                                TextPreferenceWidget(
                                    title = item.displayName,
                                    subtitle = buildString {
                                        append(item.extension.uppercase())
                                        item.sizeBytes?.takeIf { it >= 0L }?.let { size ->
                                            append(" · ")
                                            append(Formatter.formatFileSize(context, size))
                                        }
                                    },
                                )
                            }
                            item {
                                PreferenceGroupHeader(
                                    title = stringResource(MR.strings.external_media_actions_group),
                                )
                            }
                            item {
                                val openSummary = when {
                                    state.isOpening -> MR.strings.external_media_open_preparing
                                    state.items.size != 1 -> MR.strings.external_media_open_single_file_only
                                    state.openSourceId == null -> MR.strings.external_media_open_requires_local_library
                                    else -> MR.strings.external_media_action_open_summary
                                }
                                TextPreferenceWidget(
                                    title = stringResource(MR.strings.external_media_action_open),
                                    subtitle = stringResource(openSummary),
                                    enabled = state.canOpen,
                                    onPreferenceClick = screenModel::open,
                                )
                            }
                            item {
                                TextPreferenceWidget(
                                    title = stringResource(MR.strings.external_media_action_import),
                                    subtitle = stringResource(MR.strings.external_media_action_import_summary),
                                    enabled = state.canConfigureImport,
                                    onPreferenceClick = screenModel::showImportConfiguration,
                                )
                            }
                        } else if (state.connections.isEmpty()) {
                            item {
                                PreferenceGroupHeader(
                                    title = stringResource(MR.strings.external_media_destination_group),
                                )
                            }
                            item {
                                TextPreferenceWidget(
                                    title = stringResource(MR.strings.external_media_import_unavailable),
                                    subtitle = stringResource(MR.strings.external_media_no_destination),
                                    onPreferenceClick = {
                                        navigator.push(LibraryConnectionProfilesScreen(openAddDialog = false))
                                    },
                                )
                            }
                        } else {
                            item {
                                PreferenceGroupHeader(
                                    title = stringResource(MR.strings.external_media_destination_group),
                                )
                            }
                            item {
                                TextPreferenceWidget(
                                    title = stringResource(MR.strings.external_media_connection),
                                    subtitle = state.selectedConnection?.name,
                                    onPreferenceClick = { dialog = SelectionDialog.Connection },
                                )
                            }
                            if (state.selectableShelves.isNotEmpty()) {
                                item {
                                    TextPreferenceWidget(
                                        title = stringResource(MR.strings.local_library_bookshelves),
                                        subtitle = state.selectableShelves
                                            .firstOrNull { it.id == state.selectedShelfId }
                                            ?.name,
                                        onPreferenceClick = { dialog = SelectionDialog.Shelf },
                                    )
                                }
                            }
                            item {
                                TextPreferenceWidget(
                                    title = stringResource(MR.strings.local_library_directories),
                                    subtitle = state.selectedDestination?.name,
                                    onPreferenceClick = { dialog = SelectionDialog.Destination },
                                )
                            }
                            if (!state.isIndividualDestination) {
                                item {
                                    PreferenceGroupHeader(
                                        title = stringResource(MR.strings.external_media_series_group),
                                    )
                                }
                                item {
                                    TextPreferenceWidget(
                                        title = stringResource(MR.strings.external_media_create_new_series),
                                        subtitle = stringResource(MR.strings.external_media_create_new_series_summary),
                                        widget = {
                                            RadioButton(
                                                selected = state.seriesTargetMode ==
                                                    ExternalMediaImportScreenModel.SeriesTargetMode.NEW,
                                                onClick = null,
                                            )
                                        },
                                        onPreferenceClick = screenModel::selectNewSeries,
                                    )
                                }
                                item {
                                    TextPreferenceWidget(
                                        title = stringResource(MR.strings.external_media_choose_existing_series),
                                        subtitle = state.selectedExistingSeries?.name
                                            ?: stringResource(MR.strings.external_media_choose_existing_series_summary),
                                        widget = {
                                            RadioButton(
                                                selected = state.seriesTargetMode ==
                                                    ExternalMediaImportScreenModel.SeriesTargetMode.EXISTING,
                                                onClick = null,
                                            )
                                        },
                                        onPreferenceClick = screenModel::showExistingSeries,
                                    )
                                }
                                if (state.seriesTargetMode == ExternalMediaImportScreenModel.SeriesTargetMode.NEW) {
                                    item {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                        ) {
                                            OutlinedTextField(
                                                value = state.seriesName,
                                                onValueChange = screenModel::setSeriesName,
                                                label = { Text(stringResource(MR.strings.external_media_series_name)) },
                                                singleLine = true,
                                                enabled = !state.isImporting,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            when {
                                                state.isScanningMetadata -> Text(
                                                    text = stringResource(MR.strings.external_media_metadata_scanning),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(top = 4.dp),
                                                )
                                                state.metadataSeriesNameApplied -> Text(
                                                    text = stringResource(MR.strings.external_media_metadata_applied),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(top = 4.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            item {
                                SwitchPreferenceWidget(
                                    title = stringResource(MR.strings.external_media_open_after_import),
                                    checked = state.openAfterImport,
                                    enabled = !state.isImporting,
                                    onCheckedChanged = screenModel::setOpenAfterImport,
                                )
                            }
                        }
                    }
                }
            }
        }

        when (dialog) {
            SelectionDialog.Connection -> SelectionListDialog(
                title = stringResource(MR.strings.external_media_connection),
                options = state.connections,
                selected = state.selectedConnection,
                label = ExternalMediaImportScreenModel.ImportConnection::name,
                onSelect = {
                    screenModel.selectConnection(it.id)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
            SelectionDialog.Destination -> SelectionListDialog(
                title = stringResource(MR.strings.local_library_directories),
                options = state.selectableDestinations,
                selected = state.selectedDestination,
                label = ConnectionMediaImportDestination::name,
                onSelect = {
                    screenModel.selectDestination(it.id)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
            SelectionDialog.Shelf -> SelectionListDialog(
                title = stringResource(MR.strings.local_library_bookshelves),
                options = state.selectableShelves,
                selected = state.selectableShelves.firstOrNull { it.id == state.selectedShelfId },
                label = ConnectionLibraryShelf::name,
                onSelect = {
                    screenModel.selectShelf(it.id)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
            null -> Unit
        }

        LaunchedEffect(screenModel) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    is ExternalMediaImportScreenModel.Event.Imported -> {
                        context.toast(MR.strings.external_media_import_success)
                        if (event.readerIntent != null && flowHost != null) {
                            flowHost.openReader(event.readerIntent)
                        } else if (event.readerIntent != null) {
                            resetNavigationWhenCovered = true
                            context.startActivity(event.readerIntent)
                        } else if (flowHost != null) {
                            flowHost.finishImport(event.manga, event.openAfterImport)
                        } else {
                            navigator.popUntilRoot()
                            if (event.openAfterImport && event.manga != null) {
                                navigator.push(
                                    MangaScreen(
                                        mangaId = event.manga.id,
                                        fromSource = true,
                                        sourceId = event.manga.source,
                                        mangaUrl = event.manga.url,
                                    ),
                                )
                            } else {
                                HomeScreen.openTab(HomeScreen.Tab.Library())
                            }
                        }
                    }
                    is ExternalMediaImportScreenModel.Event.ImportFailed -> {
                        event.error?.let { error ->
                            context.toast(with(context) { error.formattedMessage })
                        } ?: context.toast(MR.strings.external_media_import_failed)
                    }
                    is ExternalMediaImportScreenModel.Event.Opened -> {
                        if (flowHost != null) {
                            flowHost.openReader(event.intent)
                        } else {
                            resetNavigationWhenCovered = true
                            context.startActivity(event.intent)
                        }
                    }
                    is ExternalMediaImportScreenModel.Event.OpenFailed -> {
                        event.error?.let { error ->
                            context.toast(with(context) { error.formattedMessage })
                        } ?: context.toast(MR.strings.external_media_open_failed)
                    }
                }
            }
        }
    }

    private enum class SelectionDialog {
        Connection,
        Destination,
        Shelf,
    }
}

@Composable
private fun ImportFailureContent(
    failure: ExternalMediaImportScreenModel.LoadFailure,
    onConfigure: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                when (failure) {
                    ExternalMediaImportScreenModel.LoadFailure.NO_SUPPORTED_MEDIA ->
                        MR.strings.external_media_unsupported
                    ExternalMediaImportScreenModel.LoadFailure.NO_DESTINATION ->
                        MR.strings.external_media_no_destination
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (failure == ExternalMediaImportScreenModel.LoadFailure.NO_DESTINATION) {
                TextButton(onClick = onConfigure) {
                    Text(stringResource(MR.strings.pref_connection_management))
                }
            }
            TextButton(onClick = onRetry) {
                Text(stringResource(MR.strings.action_retry))
            }
        }
    }
}

@Composable
private fun <T> SelectionListDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(options) { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = null,
                        )
                        Text(
                            text = label(option),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
    )
}
