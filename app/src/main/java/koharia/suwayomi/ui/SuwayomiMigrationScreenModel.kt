package koharia.suwayomi.ui

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiLibrarySourceGroup
import koharia.suwayomi.SuwayomiMigrationSort
import koharia.suwayomi.SuwayomiSourceInfo
import koharia.suwayomi.groupSuwayomiLibraryBySource
import koharia.suwayomi.sortSuwayomiLibrarySources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The library grouped by its source for the migration picker. The library comes from the
 * catalogue's persisted shelf, so opening the tab never triggers a full server library fetch.
 */
class SuwayomiMigrationScreenModel(private val source: SuwayomiSource) :
    StateScreenModel<SuwayomiMigrationScreenModel.State>(State()) {
    private val session = source.session()
    init {
        bindSession(session)
    }
    private var loadJob: Job? = null

    fun load(refresh: Boolean = false) {
        if (loadJob?.isActive == true) return
        loadJob = screenModelScope.launch(Dispatchers.IO) {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                session.checkActive()
                var shelf = session.catalog.shelf(refresh)
                session.checkActive()
                // A shelf cached before source ids were stored cannot be grouped; read it once more.
                if (!refresh && shelf.mangas.isNotEmpty() && shelf.mangas.all { it.sourceId == 0L }) {
                    shelf = session.catalog.shelf(refresh = true)
                    session.checkActive()
                }
                // Render cached membership before optional source names and obsolete flags arrive.
                val knownSources = session.catalog.cachedSources()
                mutableState.update {
                    it.copy(
                        groups = groupSuwayomiLibraryBySource(
                            shelf.mangas,
                            knownSources.orEmpty().associate { source -> source.id to source.name },
                            knownSources.orEmpty().filter { source -> source.isObsolete }
                                .mapTo(hashSetOf()) { source -> source.id },
                        ),
                        loading = true,
                        loaded = true,
                        sourcesLoaded = knownSources != null,
                    )
                }
                val sources = session.catalog.sources(refresh)
                session.checkActive()
                mutableState.update {
                    it.copy(
                        sources = sources,
                        sourcesLoaded = true,
                        groups = groupSuwayomiLibraryBySource(
                            mangas = shelf.mangas,
                            sourceNames = sources.associate { info -> info.id to info.name },
                            obsoleteSourceIds = sources.filter { info -> info.isObsolete }
                                .mapTo(hashSetOf()) { info -> info.id },
                        ),
                        loading = false,
                        loaded = true,
                        error = null,
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(loading = false, loaded = true, error = error) }
            }
        }
    }

    fun visibleGroups(): List<SuwayomiLibrarySourceGroup> = with(state.value) {
        sortSuwayomiLibrarySources(groups, query, obsoleteOnly && sourcesLoaded, sort, ascending)
    }

    fun setQuery(query: String) = mutableState.update { it.copy(query = query) }

    fun toggleObsoleteOnly() = mutableState.update { it.copy(obsoleteOnly = !it.obsoleteOnly) }

    fun toggleSortMode() = mutableState.update {
        it.copy(
            sort = if (it.sort == SuwayomiMigrationSort.ALPHABETICAL) {
                SuwayomiMigrationSort.TOTAL
            } else {
                SuwayomiMigrationSort.ALPHABETICAL
            },
        )
    }

    fun toggleSortDirection() = mutableState.update { it.copy(ascending = !it.ascending) }

    data class State(
        val sources: List<SuwayomiSourceInfo> = emptyList(),
        val sourcesLoaded: Boolean = false,
        val groups: List<SuwayomiLibrarySourceGroup> = emptyList(),
        val query: String = "",
        val obsoleteOnly: Boolean = false,
        val sort: SuwayomiMigrationSort = SuwayomiMigrationSort.ALPHABETICAL,
        val ascending: Boolean = true,
        val loaded: Boolean = false,
        val loading: Boolean = false,
        val error: Throwable? = null,
    )
}
