package eu.kanade.tachiyomi.ui.history

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.tachiyomi.util.lang.toLocalDate
import koharia.connection.ConnectionHistorySyncAdapter
import koharia.connection.ConnectionPreferences
import koharia.connection.NO_ACTIVE_CONNECTION
import koharia.connection.isConnectionLibraryEntry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicLong

class HistoryScreenModel(
    private val addTracks: AddTracks = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getHistory: GetHistory = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val removeHistory: RemoveHistory = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val connectionPreferences: ConnectionPreferences = Injekt.get(),
) : StateScreenModel<HistoryScreenModel.State>(State()) {

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val historyScopeGeneration = AtomicLong()
    private val historyScopes = combine(
        connectionPreferences.activeConnectionId.changes().distinctUntilChanged(),
        sourceManager.catalogueSources,
    ) { sourceId, _ ->
        sourceId to (sourceManager.get(sourceId) as? ConnectionHistorySyncAdapter)
    }.distinctUntilChanged().flatMapLatest { (sourceId, adapter) ->
        (adapter?.historyScopeChanges ?: flowOf(Unit)).map {
            HistoryScope(sourceId, adapter, historyScopeGeneration.incrementAndGet())
        }
    }.flowOn(Dispatchers.IO)
        .shareIn(screenModelScope, SharingStarted.Eagerly, replay = 1)

    init {
        screenModelScope.launchIO {
            historyScopes
                .collectLatest(::syncConnectionHistory)
        }

        screenModelScope.launch {
            var displayedScopeGeneration: Long? = null
            combine(
                state.map { it.searchQuery }.distinctUntilChanged(),
                historyScopes,
            ) { query, scope -> query to scope }
                .flatMapLatest { (query, scope) ->
                    val history = if (scope.sourceId == NO_ACTIVE_CONNECTION) {
                        flowOf(emptyList())
                    } else {
                        getHistory.subscribe(query ?: "", scope.sourceId)
                    }
                    history
                        .distinctUntilChanged()
                        .map { rows ->
                            val allowed = scope.adapter?.historyMangaIds()
                            if (!scope.isCurrent() || sourceManager.get(scope.sourceId) == null) {
                                emptyList()
                            } else {
                                rows.withinScope(allowed)
                            }
                        }
                        .onStart { emit(emptyList()) }
                        .catch { error ->
                            logcat(LogPriority.ERROR, error)
                            emit(emptyList())
                            _events.send(Event.InternalError)
                        }
                        .map { scope to it.toHistoryUiModels() }
                        .flowOn(Dispatchers.IO)
                }
                .collect { (scope, newList) ->
                    val changedScope = displayedScopeGeneration != scope.generation
                    displayedScopeGeneration = scope.generation
                    mutableState.update {
                        it.copy(
                            list = if (scope.isCurrent()) newList else emptyList(),
                            dialog = if (changedScope) null else it.dialog,
                        )
                    }
                }
        }
    }

    private suspend fun syncConnectionHistory(scope: HistoryScope) {
        if (scope.sourceId == NO_ACTIVE_CONNECTION) return
        val progressAdapter = scope.adapter ?: return
        runCatching { progressAdapter.syncConnectionHistory() }
            .onFailure { error ->
                logcat(LogPriority.WARN, error) { "Failed to sync connection history from provider" }
            }
    }

    private fun List<HistoryWithRelations>.toHistoryUiModels(): List<HistoryUiModel> {
        return map { HistoryUiModel.Item(it) }
            .insertSeparators { before, after ->
                val beforeDate = before?.item?.readAt?.time?.toLocalDate()
                val afterDate = after?.item?.readAt?.time?.toLocalDate()
                when {
                    beforeDate != afterDate && afterDate != null -> HistoryUiModel.Header(afterDate)
                    // Return null to avoid adding a separator between two items.
                    else -> null
                }
            }
    }

    suspend fun getNextChapter(): Chapter? {
        return withIOContext {
            val scope = currentHistoryScope() ?: return@withIOContext null
            val allowed = scope.allowedMangaIds
                ?: return@withIOContext getNextChapters.await(onlyUnread = false).firstOrNull()
            val latest = getHistory.subscribe("", scope.sourceId).first()
                .withinScope(allowed)
                .maxByOrNull { it.readAt?.time ?: 0L }
                ?: return@withIOContext null
            if (!scope.isCurrent()) return@withIOContext null
            val chapter = getNextChapters.await(latest.mangaId, latest.chapterId, onlyUnread = false).firstOrNull()
            chapter?.takeIf { scope.isCurrent() && it.mangaId in allowed }
        }
    }

    fun getNextChapterForManga(mangaId: Long, chapterId: Long) {
        screenModelScope.launchIO {
            val scope = currentHistoryScope() ?: return@launchIO
            if (!scope.allows(mangaId)) return@launchIO
            val chapters = getNextChapters.await(mangaId, chapterId, onlyUnread = false)
            if (scope.isCurrent()) sendNextChapterEvent(chapters)
        }
    }

    private suspend fun sendNextChapterEvent(chapters: List<Chapter>) {
        val chapter = chapters.firstOrNull()
        _events.send(Event.OpenChapter(chapter))
    }

    fun removeFromHistory(history: HistoryWithRelations) {
        screenModelScope.launchIO {
            val scope = currentHistoryScope() ?: return@launchIO
            if (scope.allows(history.mangaId)) removeHistory.await(history)
        }
    }

    fun removeAllFromHistory(mangaId: Long) {
        screenModelScope.launchIO {
            val scope = currentHistoryScope() ?: return@launchIO
            if (scope.allows(mangaId)) removeHistory.await(mangaId)
        }
    }

    fun removeAllHistory() {
        screenModelScope.launchIO {
            val scope = currentHistoryScope() ?: return@launchIO
            if (scope.sourceId == NO_ACTIVE_CONNECTION) return@launchIO
            val allowed = scope.allowedMangaIds
            if (allowed == null) {
                if (!removeHistory.awaitAll(scope.sourceId)) return@launchIO
            } else {
                for (mangaId in allowed) {
                    if (!scope.isCurrent()) return@launchIO
                    removeHistory.await(mangaId)
                }
            }
            if (!scope.isCurrent()) return@launchIO
            _events.send(Event.HistoryCleared)
        }
    }

    private suspend fun currentHistoryScope(): HistoryScope? {
        val sourceId = connectionPreferences.activeConnectionId.get()
        val source = sourceManager.get(sourceId)
        if (sourceId != NO_ACTIVE_CONNECTION && source == null) return null
        val adapter = source as? ConnectionHistorySyncAdapter
        val scope = HistoryScope(sourceId, adapter, historyScopeGeneration.get())
        val allowed = adapter?.historyMangaIds()
        return scope.copy(allowedMangaIds = allowed).takeIf { it.isCurrent() }
    }

    private fun HistoryScope.isCurrent(): Boolean =
        sourceId == connectionPreferences.activeConnectionId.get() &&
            adapter === (sourceManager.get(sourceId) as? ConnectionHistorySyncAdapter) &&
            generation == historyScopeGeneration.get()

    private fun HistoryScope.allows(mangaId: Long): Boolean = isCurrent() &&
        (allowedMangaIds == null || mangaId in allowedMangaIds)

    private fun List<HistoryWithRelations>.withinScope(allowed: Set<Long>?): List<HistoryWithRelations> =
        if (allowed == null) this else filter { it.mangaId in allowed }

    private data class HistoryScope(
        val sourceId: Long,
        val adapter: ConnectionHistorySyncAdapter?,
        val generation: Long,
        val allowedMangaIds: Set<Long>? = null,
    )

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    private fun moveMangaToCategory(mangaId: Long, categories: Category?) {
        val categoryIds = listOfNotNull(categories).map { it.id }
        moveMangaToCategory(mangaId, categoryIds)
    }

    private fun moveMangaToCategory(mangaId: Long, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setMangaCategories.await(mangaId, categoryIds)
        }
    }

    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        moveMangaToCategory(manga.id, categories)
        if (manga.isConnectionLibraryEntry(sourceManager)) return

        screenModelScope.launchIO {
            updateManga.awaitUpdateFavorite(manga.id, true)
        }
    }

    private suspend fun getMangaCategoryIds(manga: Manga): List<Long> {
        return getCategories.await(manga.id)
            .map { it.id }
    }

    fun addFavorite(mangaId: Long) {
        screenModelScope.launchIO {
            val manga = getManga.await(mangaId) ?: return@launchIO

            val duplicates = getDuplicateLibraryManga(manga)
            if (duplicates.isNotEmpty()) {
                mutableState.update { it.copy(dialog = Dialog.DuplicateManga(manga, duplicates)) }
                return@launchIO
            }

            addFavorite(manga)
        }
    }

    fun addFavorite(manga: Manga) {
        screenModelScope.launchIO {
            // Move to default category if applicable
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
            val defaultCategory = categories.find { it.id == defaultCategoryId }

            when {
                // Default category set
                defaultCategory != null -> {
                    val result = updateManga.awaitUpdateFavorite(manga.id, true)
                    if (!result) return@launchIO
                    moveMangaToCategory(manga.id, defaultCategory)
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0L || categories.isEmpty() -> {
                    val result = updateManga.awaitUpdateFavorite(manga.id, true)
                    if (!result) return@launchIO
                    moveMangaToCategory(manga.id, null)
                }

                // Choose a category
                else -> showChangeCategoryDialog(manga)
            }

            // Sync with tracking services if applicable
            addTracks.bindEnhancedTrackers(manga, sourceManager.getOrStub(manga.source))
        }
    }

    fun showMigrateDialog(target: Manga, current: Manga) {
        mutableState.update { currentState ->
            currentState.copy(dialog = Dialog.Migrate(target = target, current = current))
        }
    }

    fun showChangeCategoryDialog(manga: Manga) {
        screenModelScope.launch {
            val categories = getCategories()
            val selection = getMangaCategoryIds(manga)
            mutableState.update { currentState ->
                currentState.copy(
                    dialog = Dialog.ChangeCategory(
                        manga = manga,
                        initialSelection = categories.mapAsCheckboxState { it.id in selection }.toImmutableList(),
                    ),
                )
            }
        }
    }

    @Immutable
    data class State(
        val searchQuery: String? = null,
        val list: List<HistoryUiModel>? = null,
        val dialog: Dialog? = null,
    )

    sealed interface Dialog {
        data object DeleteAll : Dialog
        data class Delete(val history: HistoryWithRelations) : Dialog
        data class DuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: ImmutableList<CheckboxState<Category>>,
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }

    sealed interface Event {
        data class OpenChapter(val chapter: Chapter?) : Event
        data object InternalError : Event
        data object HistoryCleared : Event
    }
}
