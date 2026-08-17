package koharia.connection.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import koharia.connection.ConnectionMetadataGenerationAdapter
import koharia.connection.LibraryMetadata
import koharia.connection.LibraryMetadataField
import koharia.connection.LibraryMetadataSuggestion
import koharia.connection.MetadataFilenameTemplate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SeriesMetadataEditScreen(
    private val manga: Manga,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val sourceManager: SourceManager = Injekt.get()
        val source = remember(manga.source) { sourceManager.get(manga.source) }
        val adapter = source as? ConnectionMetadataAdapter
        if (adapter == null) {
            LaunchedEffect(Unit) { navigator.pop() }
            return
        }

        val screenModel = rememberScreenModel {
            SeriesMetadataEditScreenModel(
                manga = manga,
                metadataAdapter = adapter,
                metadataGenerationAdapter = source as? ConnectionMetadataGenerationAdapter,
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
            onDismissMetadataGeneration = screenModel::dismissMetadataGeneration,
            onFilenameTemplateChange = screenModel::selectFilenameTemplate,
            onGenerateMetadataPreview = screenModel::generateMetadataPreview,
            onApplyGeneratedMetadata = screenModel::applyGeneratedMetadata,
            onSave = screenModel::save,
        )

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
    private val updateManga: UpdateManga = Injekt.get(),
) : StateScreenModel<SeriesMetadataEditScreenModel.State>(
    State.from(manga, supportsMetadataGeneration = metadataGenerationAdapter != null),
) {

    private var storedMetadata: LibraryMetadata? = null
    val events = Channel<Event>(capacity = Channel.BUFFERED)

    init {
        screenModelScope.launchIO {
            storedMetadata = runCatching { metadataAdapter.readMetadata(manga.url) }.getOrNull()
            mutableState.update { it.copy(isLoading = false) }
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
        val fields = suggestion.fieldSources.keys
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

    fun save() {
        val snapshot = state.value
        if (!snapshot.canSave) return
        mutableState.update { it.copy(isSaving = true) }

        screenModelScope.launchIO {
            val existing = storedMetadata
            val metadata = LibraryMetadata(
                title = snapshot.title.trim(),
                author = snapshot.author.trim(),
                artist = snapshot.artist.trim(),
                description = snapshot.description.trim(),
                genres = parseGenres(snapshot.genres),
                status = snapshot.status,
                lockedFields = existing?.lockedFields.orEmpty() + snapshot.changedFields,
                source = "user",
            )
            val saved = metadataAdapter.updateMetadata(manga.url, metadata).isSuccess
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
        val supportsMetadataGeneration: Boolean,
        val showMetadataGeneration: Boolean = false,
        val filenameTemplate: MetadataFilenameTemplate = MetadataFilenameTemplate.AUTO,
        val generatedMetadata: LibraryMetadataSuggestion? = null,
        val isGeneratingMetadata: Boolean = false,
    ) {
        val canSave: Boolean
            get() = title.isNotBlank() && changedFields.isNotEmpty() && !isLoading && !isSaving

        companion object {
            fun from(manga: Manga, supportsMetadataGeneration: Boolean) = State(
                originalTitle = manga.title,
                title = manga.title,
                author = manga.author.orEmpty(),
                artist = manga.artist.orEmpty(),
                description = manga.description.orEmpty(),
                genres = manga.genre.orEmpty().joinToString(", "),
                status = manga.status.toInt(),
                supportsMetadataGeneration = supportsMetadataGeneration,
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
