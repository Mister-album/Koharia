package koharia.connection.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.presentation.manga.SeriesMetadataEditScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import koharia.connection.ConnectionMetadataAdapter
import koharia.connection.ConnectionMetadataConflictAdapter
import koharia.connection.ConnectionMetadataGenerationAdapter
import koharia.connection.LibraryMetadata
import koharia.connection.LibraryMetadataField
import koharia.connection.LibraryMetadataSuggestion
import koharia.connection.MetadataFilenameTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkCircularProgressIndicator
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SeriesMetadataEditScreen(
    private val mangaId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val loaded by produceState<Result<Manga>?>(null, mangaId) {
            value = runCatching {
                withIOContext {
                    Injekt.get<MangaRepository>().getMangaById(mangaId)
                }
            }.onFailure { if (it is CancellationException) throw it }
        }
        val result = loaded
        if (result == null) {
            EInkCircularProgressIndicator()
            return
        }
        val manga = result.getOrNull()
        if (manga == null) {
            LaunchedEffect(Unit) {
                context.toast(MR.strings.series_details_load_failed)
                navigator.pop()
            }
            return
        }
        val sourceManager: SourceManager = Injekt.get()
        val source = remember(manga.source) { sourceManager.get(manga.source) }
        val adapter = source as? ConnectionMetadataAdapter
        if (adapter == null) {
            LaunchedEffect(Unit) { navigator.pop() }
            return
        }
        if (!adapter.isMetadataEditable(manga.url)) {
            LaunchedEffect(Unit) { navigator.pop() }
            return
        }
        val editableFields = remember(adapter, manga.url) { adapter.editableMetadataFields(manga.url) }

        val screenModel = rememberScreenModel {
            SeriesMetadataEditScreenModel(
                manga = manga,
                metadataAdapter = adapter,
                metadataGenerationAdapter = (source as? ConnectionMetadataGenerationAdapter)
                    ?.takeIf { LibraryMetadataField.TITLE in editableFields },
                editableFields = editableFields,
            )
        }
        val state by screenModel.state.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }
        val saveFailedMessage = stringResource(MR.strings.series_details_save_failed)
        val generationFailedMessage = stringResource(MR.strings.metadata_generation_failed)

        SeriesMetadataEditScreen(
            state = state,
            snackbarHostState = snackbarHostState,
            navigateUp = navigator::pop,
            onTitleChange = screenModel::updateTitle,
            onAuthorChange = screenModel::updateAuthor,
            onArtistChange = screenModel::updateArtist,
            onDescriptionChange = screenModel::updateDescription,
            onGenresChange = screenModel::updateGenres,
            onOpenMetadataGeneration = screenModel::openMetadataGeneration,
            onImportLegacyMetadata = screenModel::previewLegacyMetadata,
            onDismissMetadataGeneration = screenModel::dismissMetadataGeneration,
            onFilenameTemplateChange = screenModel::selectFilenameTemplate,
            onGenerateMetadataPreview = screenModel::generateMetadataPreview,
            onApplyGeneratedMetadata = screenModel::applyGeneratedMetadata,
            onSave = screenModel::save,
        )

        if (state.metadataConflict) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = screenModel::dismissMetadataConflict,
                title = { androidx.compose.material3.Text(stringResource(MR.strings.local_library_metadata_conflict)) },
                text = {
                    androidx.compose.material3.Text(stringResource(MR.strings.local_library_metadata_conflict_detail))
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = screenModel::overwriteExternalMetadata) {
                        androidx.compose.material3.Text(stringResource(MR.strings.local_library_metadata_keep_local))
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = screenModel::useExternalMetadata) {
                        androidx.compose.material3.Text(stringResource(MR.strings.local_library_metadata_use_external))
                    }
                },
            )
        }

        LaunchedEffect(screenModel) {
            screenModel.events.receiveAsFlow().collect { event ->
                when (event) {
                    SeriesMetadataEditScreenModel.Event.Saved -> {
                        context.toast(MR.strings.series_details_saved)
                        navigator.pop()
                    }
                    SeriesMetadataEditScreenModel.Event.SaveFailed -> {
                        snackbarHostState.showSnackbar(saveFailedMessage)
                    }
                    SeriesMetadataEditScreenModel.Event.GenerationFailed -> {
                        snackbarHostState.showSnackbar(generationFailedMessage)
                    }
                }
            }
        }
    }
}

class SeriesMetadataEditScreenModel(
    private val manga: Manga,
    private val metadataAdapter: ConnectionMetadataAdapter,
    private val metadataGenerationAdapter: ConnectionMetadataGenerationAdapter?,
    private val editableFields: Set<LibraryMetadataField> = LibraryMetadataField.entries.toSet(),
    private val updateManga: UpdateManga = Injekt.get(),
) : StateScreenModel<SeriesMetadataEditScreenModel.State>(
    State.from(
        manga,
        supportsMetadataGeneration = metadataGenerationAdapter != null,
        editableFields = editableFields,
    ),
) {

    private var storedMetadata: LibraryMetadata? = null
    val events = Channel<Event>(capacity = Channel.BUFFERED)

    init {
        screenModelScope.launchIO {
            storedMetadata = runCatching { metadataAdapter.readMetadata(manga.url) }.getOrNull()
            val legacy = runCatching { metadataGenerationAdapter?.legacyMetadataSuggestion(manga.url) }.getOrNull()
            mutableState.update { it.copy(isLoading = false, legacyMetadata = legacy) }
        }
    }

    fun updateTitle(value: String) = updateField(FIELD_TITLE) { copy(title = value) }

    fun updateAuthor(value: String) = updateField(FIELD_AUTHOR) { copy(author = value) }

    fun updateArtist(value: String) = updateField(FIELD_ARTIST) { copy(artist = value) }

    fun updateDescription(value: String) = updateField(FIELD_DESCRIPTION) { copy(description = value) }

    fun updateGenres(value: String) = updateField(FIELD_GENRES) { copy(genres = value) }

    fun openMetadataGeneration() {
        if (!state.value.supportsMetadataGeneration) return
        mutableState.update {
            it.copy(
                showMetadataGeneration = true,
                generatedMetadata = null,
                isGeneratingMetadata = false,
            )
        }
    }

    fun dismissMetadataGeneration() {
        if (state.value.isGeneratingMetadata) return
        mutableState.update { it.copy(showMetadataGeneration = false) }
    }

    fun selectFilenameTemplate(template: MetadataFilenameTemplate) {
        if (state.value.isGeneratingMetadata) return
        mutableState.update {
            it.copy(
                filenameTemplate = template,
                generatedMetadata = null,
            )
        }
    }

    fun generateMetadataPreview() {
        val adapter = metadataGenerationAdapter ?: return
        val snapshot = state.value
        if (snapshot.isGeneratingMetadata) return
        mutableState.update { it.copy(isGeneratingMetadata = true, generatedMetadata = null) }
        screenModelScope.launchIO {
            val result = adapter.generateMetadataSuggestion(manga.url, snapshot.filenameTemplate)
            result.onSuccess { suggestion ->
                mutableState.update {
                    it.copy(
                        isGeneratingMetadata = false,
                        generatedMetadata = suggestion,
                    )
                }
            }.onFailure {
                mutableState.update { it.copy(isGeneratingMetadata = false) }
                events.send(Event.GenerationFailed)
            }
        }
    }

    fun applyGeneratedMetadata() {
        val suggestion = state.value.generatedMetadata ?: return
        val protected = storedMetadata?.lockedFields.orEmpty() + state.value.changedFields
        val fields = suggestion.fieldSources.keys.filterTo(mutableSetOf()) {
            it in editableFields && metadataFieldKey(it) !in protected
        }
        if (fields.isEmpty()) return
        mutableState.update { current ->
            current.copy(
                title = suggestion.metadata.title.takeIf { LibraryMetadataField.TITLE in fields }
                    ?: current.title,
                author = suggestion.metadata.author.takeIf { LibraryMetadataField.AUTHOR in fields }
                    ?: current.author,
                artist = suggestion.metadata.artist.takeIf { LibraryMetadataField.ARTIST in fields }
                    ?: current.artist,
                description = suggestion.metadata.description
                    .takeIf { LibraryMetadataField.DESCRIPTION in fields }
                    ?: current.description,
                genres = suggestion.metadata.genres
                    .takeIf { LibraryMetadataField.GENRES in fields }
                    ?.joinToString(", ")
                    ?: current.genres,
                status = suggestion.metadata.status.takeIf { LibraryMetadataField.STATUS in fields }
                    ?: current.status,
                changedFields = current.changedFields + fields.mapNotNull(::metadataFieldKey),
                showMetadataGeneration = false,
            )
        }
    }

    fun previewLegacyMetadata() {
        val preview = state.value.legacyMetadata ?: return
        mutableState.update { it.copy(showMetadataGeneration = true, generatedMetadata = preview) }
    }

    fun save() = saveInternal(false)

    fun dismissMetadataConflict() = mutableState.update { it.copy(metadataConflict = false) }

    fun overwriteExternalMetadata() = saveInternal(true)

    fun useExternalMetadata() {
        val conflictAdapter = metadataAdapter as? ConnectionMetadataConflictAdapter ?: return
        mutableState.update { it.copy(metadataConflict = false, isSaving = true) }
        screenModelScope.launchIO {
            if (conflictAdapter.useExternalMetadata(manga.url).isSuccess) {
                events.send(Event.Saved)
            } else {
                mutableState.update { it.copy(isSaving = false) }
                events.send(Event.SaveFailed)
            }
        }
    }

    private fun saveInternal(overwriteExternal: Boolean) {
        val snapshot = state.value
        if (!snapshot.canSave) return
        mutableState.update { it.copy(isSaving = true, metadataConflict = false) }

        screenModelScope.launchIO {
            val existing = storedMetadata
            val metadata = LibraryMetadata(
                title = if (FIELD_TITLE in snapshot.changedFields) {
                    snapshot.title.trim().takeIf(String::isNotEmpty)
                } else {
                    existing?.title
                },
                author = if (FIELD_AUTHOR in snapshot.changedFields) {
                    snapshot.author.trim().takeIf(String::isNotEmpty)
                } else {
                    existing?.author
                },
                artist = if (FIELD_ARTIST in snapshot.changedFields) {
                    snapshot.artist.trim().takeIf(String::isNotEmpty)
                } else {
                    existing?.artist
                },
                description = if (FIELD_DESCRIPTION in snapshot.changedFields) {
                    snapshot.description.trim().takeIf(String::isNotEmpty)
                } else {
                    existing?.description
                },
                genres = if (FIELD_GENRES in snapshot.changedFields) {
                    parseGenres(snapshot.genres)
                } else {
                    existing?.genres.orEmpty()
                },
                status = if (FIELD_STATUS in snapshot.changedFields) snapshot.status else existing?.status,
                lockedFields = existing?.lockedFields.orEmpty() + snapshot.changedFields,
                source = "user",
                editedFields = snapshot.changedFields.mapNotNull(::metadataField).toSet(),
            )
            val result = if (overwriteExternal && metadataAdapter is ConnectionMetadataConflictAdapter) {
                metadataAdapter.overwriteMetadata(manga.url, metadata)
            } else {
                metadataAdapter.updateMetadata(manga.url, metadata)
            }
            if (result.exceptionOrNull() is koharia.source.local.LocalMetadataConflictException) {
                mutableState.update { it.copy(isSaving = false, metadataConflict = true) }
                return@launchIO
            }
            val saved = result.isSuccess
            val updated = saved && updateManga.await(
                MangaUpdate(
                    id = manga.id,
                    title = snapshot.title.trim().takeIf { FIELD_TITLE in snapshot.changedFields },
                    author = snapshot.author.trim().takeIf { FIELD_AUTHOR in snapshot.changedFields },
                    artist = snapshot.artist.trim().takeIf { FIELD_ARTIST in snapshot.changedFields },
                    description = snapshot.description.trim().takeIf {
                        FIELD_DESCRIPTION in snapshot.changedFields
                    },
                    genre = parseGenres(snapshot.genres).takeIf { FIELD_GENRES in snapshot.changedFields },
                    status = snapshot.status.toLong().takeIf { FIELD_STATUS in snapshot.changedFields },
                ),
            )
            if (updated) {
                events.send(Event.Saved)
            } else {
                mutableState.update { it.copy(isSaving = false) }
                events.send(Event.SaveFailed)
            }
        }
    }

    private fun updateField(field: String, transform: State.() -> State) {
        if (field.toMetadataField() !in editableFields) return
        mutableState.update { state ->
            state.transform().copy(changedFields = state.changedFields + field)
        }
    }

    @Immutable
    data class State(
        val originalTitle: String,
        val title: String,
        val author: String,
        val artist: String,
        val description: String,
        val genres: String,
        val status: Int,
        val changedFields: Set<String> = emptySet(),
        val isLoading: Boolean = true,
        val isSaving: Boolean = false,
        val metadataConflict: Boolean = false,
        val supportsMetadataGeneration: Boolean,
        val editableFields: Set<LibraryMetadataField>,
        val showMetadataGeneration: Boolean = false,
        val filenameTemplate: MetadataFilenameTemplate = MetadataFilenameTemplate.AUTO,
        val generatedMetadata: LibraryMetadataSuggestion? = null,
        val legacyMetadata: LibraryMetadataSuggestion? = null,
        val isGeneratingMetadata: Boolean = false,
    ) {
        val canSave: Boolean
            get() = (LibraryMetadataField.TITLE !in editableFields || title.isNotBlank()) &&
                changedFields.isNotEmpty() && !isLoading && !isSaving

        companion object {
            fun from(
                manga: Manga,
                supportsMetadataGeneration: Boolean,
                editableFields: Set<LibraryMetadataField>,
            ) = State(
                originalTitle = manga.title,
                title = manga.title,
                author = manga.author.orEmpty(),
                artist = manga.artist.orEmpty(),
                description = manga.description.orEmpty(),
                genres = manga.genre.orEmpty().joinToString(", "),
                status = manga.status.toInt(),
                supportsMetadataGeneration = supportsMetadataGeneration,
                editableFields = editableFields,
            )
        }
    }

    enum class Event {
        Saved,
        SaveFailed,
        GenerationFailed,
    }

    private companion object {
        const val FIELD_TITLE = "title"
        const val FIELD_AUTHOR = "author"
        const val FIELD_ARTIST = "artist"
        const val FIELD_DESCRIPTION = "description"
        const val FIELD_GENRES = "genres"
        const val FIELD_STATUS = "status"

        fun parseGenres(value: String): List<String> {
            return value.split(',', '，', ';', '；', '\n')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
        }

        fun String.toMetadataField(): LibraryMetadataField? = when (this) {
            FIELD_TITLE -> LibraryMetadataField.TITLE
            FIELD_AUTHOR -> LibraryMetadataField.AUTHOR
            FIELD_ARTIST -> LibraryMetadataField.ARTIST
            FIELD_DESCRIPTION -> LibraryMetadataField.DESCRIPTION
            FIELD_GENRES -> LibraryMetadataField.GENRES
            FIELD_STATUS -> LibraryMetadataField.STATUS
            else -> null
        }

        fun metadataField(field: String): LibraryMetadataField? = field.toMetadataField()

        fun metadataFieldKey(field: LibraryMetadataField): String? = when (field) {
            LibraryMetadataField.TITLE -> FIELD_TITLE
            LibraryMetadataField.AUTHOR -> FIELD_AUTHOR
            LibraryMetadataField.ARTIST -> FIELD_ARTIST
            LibraryMetadataField.DESCRIPTION -> FIELD_DESCRIPTION
            LibraryMetadataField.GENRES -> FIELD_GENRES
            LibraryMetadataField.STATUS -> FIELD_STATUS
        }
    }
}
