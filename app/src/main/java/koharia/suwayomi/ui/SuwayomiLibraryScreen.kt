package koharia.suwayomi.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.paging.compose.collectAsLazyPagingItems
import cafe.adriel.voyager.core.model.rememberScreenModel
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.presentation.util.Screen
import koharia.connection.ConnectionBrowseScreen
import koharia.connection.ui.ConnectionFilterOptionGroup
import koharia.connection.ui.ConnectionLibraryFilterRow
import koharia.connection.ui.ConnectionPagedShelf
import koharia.connection.ui.ConnectionPagedShelfState
import koharia.connection.ui.ConnectionSearchSortOption
import koharia.connection.ui.ConnectionShelfError
import koharia.connection.ui.ConnectionShelfTab
import koharia.connection.ui.ConnectionTriState
import koharia.source.suwayomi.SuwayomiSettingsScreen
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiLibraryFilter
import koharia.suwayomi.SuwayomiLibraryFilters
import koharia.suwayomi.SuwayomiTriState
import koharia.suwayomi.triStateOf
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.jvm.Transient

class SuwayomiLibraryScreen(
    override val sourceId: Long,
    private val initialQuery: String?,
    private val showNavigationUp: Boolean,
) : Screen(), ConnectionBrowseScreen {
    override val refreshOnReselect = false

    @Transient private var runtimeModel: SuwayomiLibraryScreenModel? = null
    override suspend fun search(query: String) {
        runtimeModel?.search(query)
    }
    override suspend fun searchGenre(name: String) {
        runtimeModel?.search(name)
    }
    override suspend fun refresh() {
        runtimeModel?.refresh()
    }

    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? SuwayomiSource
        if (source == null || !source.hasValidConnection()) {
            EmptyScreen(stringRes = MR.strings.connection_unavailable)
            return
        }
        val epoch by source.epoch.collectAsState()
        val model = rememberScreenModel(tag = "${source.instanceKey}:$epoch") {
            SuwayomiLibraryScreenModel(source, initialQuery)
        }
        runtimeModel = model
        val state by model.state.collectAsState()
        val entries = remember(state.shelf, model) {
            state.shelf?.mangas.orEmpty().associateBy { model.mangaUrl(it.id) }
        }
        val pages = model.pages.collectAsLazyPagingItems()
        val context = LocalContext.current
        val sorts = listOf(
            "title" to MR.strings.suwayomi_sort_name,
            "author" to MR.strings.suwayomi_sort_author,
            "added" to MR.strings.suwayomi_sort_added,
        ).map { (value, label) ->
            ConnectionSearchSortOption(value, stringResource(label), defaultAscending = value == "title")
        }
        val labels = SuwayomiLibraryFilter.entries.associateWith { stringResource(it.labelRes) }
        val rows = SuwayomiLibraryFilter.entries.map { key ->
            ConnectionLibraryFilterRow(key.name, labels.getValue(key), state.filters.triStateOf(key).toConnection())
        }
        // The shared sheet owns the tri-state conditions; text, chapter-count and genre narrowing are
        // provider extras that the model commits in the same submission.
        var narrowing by remember(state.filters) { mutableStateOf(state.filters) }
        val genres = state.availableGenres
        key(model) {
            ConnectionPagedShelf(
                source = source,
                state = ConnectionPagedShelfState(
                    state.query, state.toolbarQuery, state.order, state.displayMode, state.downloadedOnly,
                    state.refreshing, state.mediaLoaded, state.error,
                    state.media.map { ConnectionShelfTab(it.id.toLong(), it.name) }, state.selectedMedia.toLong(), -1,
                    filterRows = rows, hasActiveFilters = state.hasActiveFilters,
                    persistentFilters = state.persistentFilters,
                ),
                pages = pages, sortOptions = sorts, showNavigationUp = showNavigationUp,
                settings = { SuwayomiSettingsScreen(sourceId) }, errorMessage = context::suwayomiError,
                materialize = source::materialize, onQueryChange = model::setToolbarQuery,
                onSearch = model::search, onCloseSearch = model::exitSearch,
                onDisplayModeChange = model::setDisplayMode, onSelectTab = { model.selectMedia(it.toInt()) },
                onSelectSort = model::selectSearchSort,
                onFilterStates = { conditions, order, downloaded, persistent ->
                    model.applyLibraryFilters(
                        conditions = conditions.mapNotNull { (key, value) ->
                            SuwayomiLibraryFilter.entries.firstOrNull { it.name == key }?.let { it to value }
                        }.toMap(),
                        narrowing = narrowing,
                        order = order,
                        downloadedOnly = downloaded,
                        persistentFilters = persistent,
                    )
                },
                filterExtras = { scope ->
                    // Reset clears the drafts visibly; the committed narrowing is replaced on apply.
                    SuwayomiLibraryFilterExtras(
                        filters = narrowing,
                        genres = genres,
                        resetKey = scope.resetKey,
                        onChange = { narrowing = it },
                    )
                },
                onRefresh = model::refresh,
                readingUnitCount = { entries[it.url]?.chapters?.totalCount?.toLong() },
                readProgress = { manga ->
                    entries[manga.url]?.let { entry ->
                        entry.chapters.totalCount?.let { count ->
                            MangaReadProgress((count - entry.unreadCount).coerceAtLeast(0).toLong(), count.toLong())
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun SuwayomiShelfError(error: Throwable, onRetry: () -> Unit, onSettings: () -> Unit) {
    ConnectionShelfError(LocalContext.current.suwayomiError(error), onRetry, onSettings)
}

/** The chapter-count, text and genre narrowing the shared sheet cannot model generically. */
@Composable
private fun SuwayomiLibraryFilterExtras(
    filters: SuwayomiLibraryFilters,
    genres: List<String>,
    resetKey: Int,
    onChange: (SuwayomiLibraryFilters) -> Unit,
) {
    // The sheet's Reset bumps resetKey, which drops every draft in this group.
    var draft by remember(resetKey) { mutableStateOf(filters) }
    // Open by default when a narrowing is already applied, so it is never silently in effect.
    var showMore by remember(resetKey) { mutableStateOf(filters.hasNarrowing) }
    CheckboxItem(stringResource(MR.strings.shelf_filter_more), showMore) { showMore = !showMore }
    if (!showMore) return

    val chapterOptions = listOf(null, 10, 50, 100)
    SelectItem(
        label = stringResource(MR.strings.shelf_filter_minimum_chapters),
        options = chapterOptions.map { minimum ->
            if (minimum == null) {
                stringResource(MR.strings.shelf_filter_chapters_any)
            } else {
                stringResource(MR.strings.shelf_filter_chapters_at_least, minimum)
            }
        }.toTypedArray(),
        selectedIndex = chapterOptions.indexOf(draft.minimumChapters).coerceAtLeast(0),
    ) { index ->
        draft = draft.copy(minimumChapters = chapterOptions.getOrNull(index))
        onChange(draft)
    }
    SuwayomiFilterTextField(MR.strings.shelf_filter_title, draft.title) {
        draft = draft.copy(title = it)
        onChange(draft)
    }
    SuwayomiFilterTextField(MR.strings.shelf_filter_author, draft.author) {
        draft = draft.copy(author = it)
        onChange(draft)
    }
    SuwayomiFilterTextField(MR.strings.shelf_filter_artist, draft.artist) {
        draft = draft.copy(artist = it)
        onChange(draft)
    }
    ConnectionFilterOptionGroup(
        heading = stringResource(MR.strings.shelf_filter_genres),
        options = genres,
        selected = draft.genres,
        emptyLabel = stringResource(MR.strings.shelf_filter_genres_empty),
        onToggle = { genre ->
            draft = draft.copy(
                genres = if (genre in draft.genres) draft.genres - genre else draft.genres + genre,
            )
            onChange(draft)
        },
    )
}

@Composable
private fun SuwayomiFilterTextField(label: StringResource, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(stringResource(label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = MaterialTheme.padding.medium,
            vertical = MaterialTheme.padding.small,
        ),
    )
}

/** True when a narrowing the shared tri-state rows do not cover is in effect. */
internal val SuwayomiLibraryFilters.hasNarrowing: Boolean
    get() = minimumChapters != null || title.isNotBlank() || author.isNotBlank() ||
        artist.isNotBlank() || genres.isNotEmpty()

internal fun SuwayomiTriState.toConnection(): ConnectionTriState = when (this) {
    SuwayomiTriState.IGNORE -> ConnectionTriState.IGNORE
    SuwayomiTriState.INCLUDE -> ConnectionTriState.INCLUDE
    SuwayomiTriState.EXCLUDE -> ConnectionTriState.EXCLUDE
}

internal fun ConnectionTriState.toTriState(): SuwayomiTriState = when (this) {
    ConnectionTriState.IGNORE -> SuwayomiTriState.IGNORE
    ConnectionTriState.INCLUDE -> SuwayomiTriState.INCLUDE
    ConnectionTriState.EXCLUDE -> SuwayomiTriState.EXCLUDE
}
