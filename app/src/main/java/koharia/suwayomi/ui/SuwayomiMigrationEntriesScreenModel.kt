package koharia.suwayomi.ui

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The entries one library source holds, read from the catalogue's persisted shelf. */
class SuwayomiMigrationEntriesScreenModel(
    private val source: SuwayomiSource,
    private val librarySourceId: Long,
) : StateScreenModel<SuwayomiMigrationEntriesScreenModel.State>(
    State(
        entries = source.session().catalog.memoryShelf?.mangas.orEmpty().filter { it.sourceId == librarySourceId },
        loaded = source.session().catalog.memoryShelf != null,
    ),
) {
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
                val shelf = session.catalog.shelf(refresh)
                session.checkActive()
                mutableState.update {
                    it.copy(
                        entries = shelf.mangas
                            .filter { manga -> manga.sourceId == librarySourceId }
                            .sortedBy { manga -> manga.title.lowercase() },
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

    data class State(
        val entries: List<SuwayomiManga> = emptyList(),
        val loaded: Boolean = false,
        val loading: Boolean = false,
        val error: Throwable? = null,
    )
}
