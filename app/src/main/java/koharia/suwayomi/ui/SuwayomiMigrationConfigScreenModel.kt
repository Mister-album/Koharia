package koharia.suwayomi.ui

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiMigrationDrafts
import koharia.suwayomi.SuwayomiSourceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Target-source selection for one migration draft. The chosen order is searched in sequence, and
 * pinned sources are offered as a one-tap starting point.
 */
class SuwayomiMigrationConfigScreenModel(
    private val source: SuwayomiSource,
    private val draftId: Long,
) : StateScreenModel<SuwayomiMigrationConfigScreenModel.State>(State()) {
    private val session = source.session()
    init {
        bindSession(session)
    }
    private val sourceManager: SourceManager = Injekt.get()
    private var loadJob: Job? = null

    fun load() {
        if (loadJob?.isActive == true) return
        loadJob = screenModelScope.launch(Dispatchers.IO) {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                session.checkActive()
                val sources = session.catalog.sources()
                session.checkActive()
                val draft = checkNotNull(SuwayomiMigrationDrafts.get(draftId, session.identity))
                val excluded = draft?.sourceId
                val ordered = draft?.targetSourceIds.orEmpty().filter { id -> sources.any { it.id == id } }
                val selection = ordered.ifEmpty { sources.filter { it.id != excluded && it.isPinned }.map { it.id } }
                draft?.targetSourceIds = selection
                mutableState.update {
                    it.copy(
                        sources = sources.filterNot { info -> info.id == excluded },
                        targetSourceIds = selection,
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

    fun add(sourceId: Long) = updateSelection { it + sourceId }

    fun remove(sourceId: Long) = updateSelection { it - sourceId }

    fun selectNone() = updateSelection { emptyList() }

    fun selectPinned() = updateSelection { current ->
        val pinned = state.value.sources.filter { it.isPinned }.map { it.id }
        (current + pinned).distinct()
    }

    /** Reorders a selected target one position, so its priority in the fallback chain changes. */
    fun move(sourceId: Long, up: Boolean) = updateSelection { current ->
        val index = current.indexOf(sourceId)
        if (index < 0) return@updateSelection current
        val target = if (up) index - 1 else index + 1
        if (target !in current.indices) return@updateSelection current
        current.toMutableList().also { it.add(target, it.removeAt(index)) }
    }

    private fun updateSelection(transform: (List<Long>) -> List<Long>) {
        val next = transform(state.value.targetSourceIds)
        SuwayomiMigrationDrafts.get(draftId, session.identity)?.targetSourceIds = next
        mutableState.update { it.copy(targetSourceIds = next) }
    }

    data class State(
        val sources: List<SuwayomiSourceInfo> = emptyList(),
        val targetSourceIds: List<Long> = emptyList(),
        val loaded: Boolean = false,
        val loading: Boolean = false,
        val error: Throwable? = null,
    )
}
