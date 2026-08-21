package koharia.importing

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import koharia.connection.ConnectionLibraryShelf
import koharia.connection.ConnectionLibraryShelfAdapter
import koharia.connection.ConnectionLocalFileAdapter
import koharia.connection.ConnectionMediaGrouping
import koharia.connection.ConnectionMediaImportAdapter
import koharia.connection.ConnectionMediaImportDestination
import koharia.connection.ConnectionMediaImportItem
import koharia.connection.ConnectionMediaImportRequest
import koharia.connection.ConnectionMediaImportSeries
import koharia.connection.ConnectionMediaType
import koharia.connection.ConnectionPreferences
import koharia.connection.LibraryContentScope
import koharia.media.LocalMediaFormats
import koharia.source.local.LocalFolderConnectionProvider
import koharia.source.local.LocalFolderSource
import koharia.source.local.LocalLibraryEntryOpenManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

class ExternalMediaImportScreenModel(
    private val context: Context,
    private val uriValues: List<String>,
    private val connectionPreferences: ConnectionPreferences,
    private val sourceManager: SourceManager,
    private val mangaRepository: MangaRepository,
    private val openManager: IncomingMediaOpenManager,
    private val localLibraryEntryOpenManager: LocalLibraryEntryOpenManager,
    initialStep: Step = Step.ACTIONS,
) : StateScreenModel<ExternalMediaImportScreenModel.State>(State(step = initialStep)) {

    private val eventChannel = Channel<Event>(capacity = Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        reload()
    }

    fun reload() {
        if (state.value.isLoading || state.value.isImporting) return
        mutableState.update { it.copy(isLoading = true, loadFailure = null) }
        screenModelScope.launchIO {
            val items = IncomingMediaParser.parse(context, uriValues)
            val connections = loadConnections(items)
            val openSourceId = preferredOpenSourceId()
            val preferredConnectionId = connectionPreferences.activeConnectionId.get()
                .takeIf { id -> connections.any { it.id == id } }
                ?: connections.firstOrNull()?.id
            val selectedConnection = connections.firstOrNull { it.id == preferredConnectionId }
            val selectedDestination = selectedConnection?.destinations?.firstOrNull()
            mutableState.update {
                it.copy(
                    isLoading = false,
                    items = items,
                    connections = connections,
                    selectedConnectionId = selectedConnection?.id,
                    selectedDestinationId = selectedDestination?.id,
                    selectedShelfId = defaultShelfId(selectedConnection, selectedDestination, items),
                    openSourceId = openSourceId,
                    seriesName = it.seriesName.ifBlank { suggestedSeriesName(items) },
                    loadFailure = when {
                        items.isEmpty() -> LoadFailure.NO_SUPPORTED_MEDIA
                        connections.isEmpty() && openSourceId == null -> LoadFailure.NO_DESTINATION
                        else -> null
                    },
                )
            }
            if (state.value.step == Step.IMPORT_CONFIGURATION) {
                startMetadataScan(items)
            } else if (state.value.step == Step.OPENING) {
                open()
            }
        }
    }

    fun setSeriesName(value: String) {
        mutableState.update {
            it.copy(
                seriesName = value,
                seriesNameEditedByUser = true,
                metadataSeriesNameApplied = false,
            )
        }
    }

    fun setOpenAfterImport(value: Boolean) {
        mutableState.update { it.copy(openAfterImport = value) }
    }

    fun showImportConfiguration() {
        if (!state.value.canConfigureImport) return
        mutableState.update { it.copy(step = Step.IMPORT_CONFIGURATION) }
        startMetadataScan(state.value.items)
    }

    fun showExistingSeries() {
        val snapshot = state.value
        if (
            snapshot.selectedConnection == null ||
            snapshot.selectedDestination?.grouping != ConnectionMediaGrouping.SERIES
        ) {
            return
        }
        mutableState.update {
            it.copy(
                step = Step.SERIES_SELECTION,
                seriesTargetMode = SeriesTargetMode.EXISTING,
                existingSeriesSearchQuery = null,
            )
        }
        startExistingSeriesLoad()
    }

    fun selectNewSeries() {
        mutableState.update {
            it.copy(
                seriesTargetMode = SeriesTargetMode.NEW,
                selectedExistingSeriesId = null,
            )
        }
    }

    fun setExistingSeriesSearchQuery(value: String?) {
        mutableState.update { it.copy(existingSeriesSearchQuery = value) }
    }

    fun selectExistingSeries(seriesId: String) {
        val series = state.value.existingSeries.firstOrNull { it.id == seriesId } ?: return
        mutableState.update {
            it.copy(
                step = Step.IMPORT_CONFIGURATION,
                seriesTargetMode = SeriesTargetMode.EXISTING,
                selectedExistingSeriesId = series.id,
                selectedShelfId = series.shelfId
                    ?.takeIf { shelfId -> it.availableShelves.any { shelf -> shelf.id == shelfId } }
                    ?: it.selectedShelfId,
                existingSeriesSearchQuery = null,
            )
        }
    }

    fun showActions() {
        if (state.value.isImporting) return
        mutableState.update { it.copy(step = Step.ACTIONS) }
    }

    fun selectConnection(connectionId: Long) {
        val connection = state.value.connections.firstOrNull { it.id == connectionId } ?: return
        val destination = connection.destinations.firstOrNull()
        mutableState.update {
            it.copy(
                selectedConnectionId = connection.id,
                selectedDestinationId = destination?.id,
                selectedShelfId = defaultShelfId(connection, destination, it.items),
                seriesTargetMode = SeriesTargetMode.NEW,
                selectedExistingSeriesId = null,
                existingSeries = emptyList(),
                loadedSeriesDestinationKey = null,
                isLoadingExistingSeries = false,
            )
        }
    }

    fun selectDestination(destinationId: String) {
        val snapshot = state.value
        val connection = snapshot.selectedConnection ?: return
        val destination = connection.destinations.firstOrNull { it.id == destinationId } ?: return
        mutableState.update {
            it.copy(
                selectedDestinationId = destination.id,
                selectedShelfId = defaultShelfId(connection, destination, it.items),
                seriesTargetMode = SeriesTargetMode.NEW,
                selectedExistingSeriesId = null,
                existingSeries = emptyList(),
                loadedSeriesDestinationKey = null,
                isLoadingExistingSeries = false,
            )
        }
    }

    fun selectShelf(shelfId: String?) {
        val snapshot = state.value
        val destination = shelfId
            ?.let(snapshot::destinationForShelf)
            ?: snapshot.selectedDestination
        if (shelfId != null && destination == null) return
        mutableState.update {
            it.copy(
                selectedDestinationId = destination?.id,
                selectedShelfId = shelfId,
                seriesTargetMode = SeriesTargetMode.NEW,
                selectedExistingSeriesId = null,
                existingSeries = emptyList(),
                loadedSeriesDestinationKey = null,
                isLoadingExistingSeries = false,
            )
        }
    }

    fun import() {
        val snapshot = state.value
        val connection = snapshot.selectedConnection ?: return
        val destination = snapshot.selectedDestination ?: return
        if (!snapshot.canImport) return
        val adapter = sourceManager.get(connection.id) as? ConnectionMediaImportAdapter ?: return
        mutableState.update { it.copy(isImporting = true) }
        screenModelScope.launchIO {
            val result = adapter.importMedia(
                ConnectionMediaImportRequest(
                    destinationId = destination.id,
                    shelfId = snapshot.selectedShelfId,
                    seriesName = snapshot.effectiveSeriesName.takeUnless { snapshot.isIndividualDestination }
                        ?.trim()
                        .orEmpty(),
                    items = snapshot.items,
                    existingSeriesId = snapshot.selectedExistingSeriesId
                        .takeIf {
                            !snapshot.isIndividualDestination &&
                                snapshot.seriesTargetMode == SeriesTargetMode.EXISTING
                        },
                ),
            )
            val imported = result.getOrNull()
            val manga = imported?.primaryResourceUrl?.let {
                mangaRepository.getMangaByUrlAndSourceId(it, connection.id)
            }
            val readerIntent = if (
                imported != null &&
                snapshot.openAfterImport &&
                snapshot.isIndividualDestination &&
                manga != null
            ) {
                (sourceManager.get(connection.id) as? LocalFolderSource)?.let { source ->
                    runCatching {
                        localLibraryEntryOpenManager.prepareIntent(context, source, manga)
                    }.getOrNull()
                }
            } else {
                null
            }
            mutableState.update { it.copy(isImporting = false) }
            if (imported != null) {
                connectionPreferences.activeConnectionId.set(connection.id)
                eventChannel.send(
                    Event.Imported(
                        manga = manga,
                        openAfterImport = snapshot.openAfterImport && !snapshot.isIndividualDestination,
                        readerIntent = readerIntent,
                    ),
                )
            } else {
                eventChannel.send(Event.ImportFailed(result.exceptionOrNull()))
            }
        }
    }

    fun open() {
        val snapshot = state.value
        val item = snapshot.items.singleOrNull() ?: return
        val sourceId = snapshot.openSourceId ?: return
        if (!snapshot.canOpen) return
        mutableState.update { it.copy(isOpening = true) }
        screenModelScope.launchIO {
            val result = openManager.open(item, sourceId)
            mutableState.update { it.copy(isOpening = false) }
            result.fold(
                onSuccess = { intent -> eventChannel.send(Event.Opened(intent)) },
                onFailure = { error -> eventChannel.send(Event.OpenFailed(error)) },
            )
        }
    }

    private suspend fun loadConnections(items: List<ConnectionMediaImportItem>): List<ImportConnection> {
        if (items.isEmpty()) return emptyList()
        return connectionPreferences.getProfiles()
            .mapNotNull { profile ->
                val source = sourceManager.get(profile.id) ?: return@mapNotNull null
                val adapter = source as? ConnectionMediaImportAdapter ?: return@mapNotNull null
                val destinations = try {
                    adapter.mediaImportDestinations()
                        .filter { destination ->
                            items.all { item -> item.extension in destination.supportedExtensions }
                        }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    emptyList()
                }
                if (destinations.isEmpty()) return@mapNotNull null
                val shelves = try {
                    (source as? ConnectionLibraryShelfAdapter)
                        ?.libraryShelves
                        ?.first()
                        .orEmpty()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    emptyList()
                }
                ImportConnection(profile.id, profile.name, destinations, shelves)
            }
    }

    private fun preferredOpenSourceId(): Long? {
        val profiles = connectionPreferences.getProfiles()
            .filter { it.providerId == LocalFolderConnectionProvider.ID }
            .filter { profile -> sourceManager.get(profile.id) is ConnectionLocalFileAdapter }
        val active = connectionPreferences.activeConnectionId.get()
        return active.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id
    }

    private fun startMetadataScan(items: List<ConnectionMediaImportItem>) {
        val snapshot = state.value
        if (snapshot.hasScannedMetadata || snapshot.isScanningMetadata || items.isEmpty()) return
        mutableState.update { it.copy(isScanningMetadata = true) }
        screenModelScope.launchIO {
            val suggestedName = IncomingMediaMetadataScanner.suggestedSeriesName(context, items)
            mutableState.update {
                it.copy(
                    isScanningMetadata = false,
                    hasScannedMetadata = true,
                    seriesName = if (!it.seriesNameEditedByUser && suggestedName != null) {
                        suggestedName
                    } else {
                        it.seriesName
                    },
                    metadataSeriesNameApplied = !it.seriesNameEditedByUser && suggestedName != null,
                )
            }
        }
    }

    private fun startExistingSeriesLoad() {
        val snapshot = state.value
        val connection = snapshot.selectedConnection ?: return
        val destination = snapshot.selectedDestination ?: return
        val destinationKey = "${connection.id}:${destination.id}"
        if (snapshot.isLoadingExistingSeries || snapshot.loadedSeriesDestinationKey == destinationKey) return
        val adapter = sourceManager.get(connection.id) as? ConnectionMediaImportAdapter ?: return
        mutableState.update { it.copy(isLoadingExistingSeries = true) }
        screenModelScope.launchIO {
            val series = try {
                adapter.mediaImportSeries(destination.id)
                    .filter { it.destinationId == destination.id }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                emptyList()
            }
            mutableState.update {
                val currentDestinationKey = "${it.selectedConnectionId}:${it.selectedDestinationId}"
                if (currentDestinationKey != destinationKey) {
                    it
                } else {
                    it.copy(
                        isLoadingExistingSeries = false,
                        existingSeries = series,
                        loadedSeriesDestinationKey = destinationKey,
                    )
                }
            }
        }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = false,
        val isImporting: Boolean = false,
        val isOpening: Boolean = false,
        val isScanningMetadata: Boolean = false,
        val isLoadingExistingSeries: Boolean = false,
        val hasScannedMetadata: Boolean = false,
        val items: List<ConnectionMediaImportItem> = emptyList(),
        val connections: List<ImportConnection> = emptyList(),
        val selectedConnectionId: Long? = null,
        val selectedDestinationId: String? = null,
        val selectedShelfId: String? = null,
        val openSourceId: Long? = null,
        val seriesName: String = "",
        val seriesNameEditedByUser: Boolean = false,
        val metadataSeriesNameApplied: Boolean = false,
        val seriesTargetMode: SeriesTargetMode = SeriesTargetMode.NEW,
        val selectedExistingSeriesId: String? = null,
        val existingSeries: List<ConnectionMediaImportSeries> = emptyList(),
        val loadedSeriesDestinationKey: String? = null,
        val existingSeriesSearchQuery: String? = null,
        val openAfterImport: Boolean = true,
        val loadFailure: LoadFailure? = null,
        val step: Step = Step.ACTIONS,
    ) {
        val selectedConnection: ImportConnection?
            get() = connections.firstOrNull { it.id == selectedConnectionId }

        val selectedDestination: ConnectionMediaImportDestination?
            get() = selectedConnection?.destinations?.firstOrNull { it.id == selectedDestinationId }

        val availableShelves: List<ConnectionLibraryShelf>
            get() {
                val destination = selectedDestination ?: return emptyList()
                return selectedConnection?.shelves.orEmpty().filter { shelf ->
                    destination.supportsShelf(shelf, items)
                }
            }

        val selectableShelves: List<ConnectionLibraryShelf>
            get() {
                val connection = selectedConnection ?: return emptyList()
                return connection.shelves.filter { shelf ->
                    connection.destinations.any { destination -> destination.supportsShelf(shelf, items) }
                }
            }

        val selectableDestinations: List<ConnectionMediaImportDestination>
            get() {
                val connection = selectedConnection ?: return emptyList()
                val shelf = connection.shelves.firstOrNull { it.id == selectedShelfId }
                    ?: return connection.destinations
                return connection.destinations.filter { destination -> destination.supportsShelf(shelf, items) }
            }

        fun destinationForShelf(shelfId: String): ConnectionMediaImportDestination? {
            val connection = selectedConnection ?: return null
            val shelf = connection.shelves.firstOrNull { it.id == shelfId } ?: return null
            return selectedDestination?.takeIf { it.supportsShelf(shelf, items) }
                ?: connection.destinations.firstOrNull { it.supportsShelf(shelf, items) }
        }

        val isIndividualDestination: Boolean
            get() = selectedDestination?.grouping == ConnectionMediaGrouping.INDIVIDUAL

        val selectedExistingSeries: ConnectionMediaImportSeries?
            get() = existingSeries.firstOrNull { it.id == selectedExistingSeriesId }

        val filteredExistingSeries: List<ConnectionMediaImportSeries>
            get() {
                val query = existingSeriesSearchQuery.orEmpty().trim()
                return existingSeries.filter { series ->
                    (selectedShelfId == null || series.shelfId == selectedShelfId) &&
                        (query.isBlank() || series.name.contains(query, ignoreCase = true))
                }
            }

        val effectiveSeriesName: String
            get() = when (seriesTargetMode) {
                SeriesTargetMode.NEW -> seriesName
                SeriesTargetMode.EXISTING -> selectedExistingSeries?.name.orEmpty()
            }

        val canImport: Boolean
            get() = !isLoading && !isImporting && !isOpening &&
                (seriesTargetMode == SeriesTargetMode.EXISTING || !isScanningMetadata) &&
                items.isNotEmpty() && selectedConnection != null && selectedDestination != null &&
                (isIndividualDestination || effectiveSeriesName.isNotBlank())

        val canOpen: Boolean
            get() = !isLoading && !isImporting && !isOpening && items.size == 1 && openSourceId != null

        val canConfigureImport: Boolean
            get() = !isLoading && !isImporting && !isOpening && items.isNotEmpty()
    }

    @Immutable
    data class ImportConnection(
        val id: Long,
        val name: String,
        val destinations: List<ConnectionMediaImportDestination>,
        val shelves: List<ConnectionLibraryShelf>,
    )

    enum class LoadFailure {
        NO_SUPPORTED_MEDIA,
        NO_DESTINATION,
    }

    enum class Step {
        ACTIONS,
        IMPORT_CONFIGURATION,
        SERIES_SELECTION,
        OPENING,
    }

    enum class SeriesTargetMode {
        NEW,
        EXISTING,
    }

    sealed interface Event {
        data class Imported(
            val manga: Manga?,
            val openAfterImport: Boolean,
            val readerIntent: Intent? = null,
        ) : Event
        data class ImportFailed(val error: Throwable?) : Event
        data class Opened(val intent: Intent) : Event
        data class OpenFailed(val error: Throwable?) : Event
    }
}

private fun defaultShelfId(
    connection: ExternalMediaImportScreenModel.ImportConnection?,
    destination: ConnectionMediaImportDestination?,
    items: List<ConnectionMediaImportItem>,
): String? {
    if (connection == null || destination == null) return null
    val shelves = connection.shelves.filter { shelf ->
        shelf.contentScope == destination.shelfScope(items) &&
            (destination.compatibleShelfIds.isEmpty() || shelf.id in destination.compatibleShelfIds)
    }
    return destination.defaultShelfId?.takeIf { id -> shelves.any { it.id == id } }
        ?: shelves.firstOrNull { it.isDefault }?.id
        ?: shelves.firstOrNull()?.id
}

private fun ConnectionMediaImportDestination.shelfScope(
    items: List<ConnectionMediaImportItem>,
): LibraryContentScope = when (mediaType) {
    ConnectionMediaType.COMIC -> LibraryContentScope.COMIC
    ConnectionMediaType.BOOK -> LibraryContentScope.BOOK
    ConnectionMediaType.MIXED -> if (items.all { it.extension in LocalMediaFormats.bookExtensions }) {
        LibraryContentScope.BOOK
    } else {
        LibraryContentScope.COMIC
    }
}

private fun ConnectionMediaImportDestination.supportsShelf(
    shelf: ConnectionLibraryShelf,
    items: List<ConnectionMediaImportItem>,
): Boolean {
    return shelf.contentScope == shelfScope(items) &&
        (compatibleShelfIds.isEmpty() || shelf.id in compatibleShelfIds)
}

internal fun suggestedSeriesName(items: List<ConnectionMediaImportItem>): String {
    val names = items.map { it.displayName.substringBeforeLast('.', missingDelimiterValue = it.displayName) }
    if (names.isEmpty()) return ""
    if (names.size == 1) return names.single()
    val prefix = names.reduce { current, name -> current.commonPrefixWith(name, ignoreCase = true) }
        .trimEnd(' ', '-', '_', '.', '[', '(')
        .replace(
            Regex("(?i)(?:\\s*[-_.]\\s*|\\s+)(?:(?:vol(?:ume)?|v|ch(?:apter)?|第)\\s*)?\\d+$"),
            "",
        )
        .trim()
    return prefix.takeIf { it.length >= 2 } ?: names.first()
}
