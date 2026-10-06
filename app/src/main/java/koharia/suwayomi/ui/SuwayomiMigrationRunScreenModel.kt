package koharia.suwayomi.ui

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiMigrationDrafts
import koharia.suwayomi.SuwayomiMigrationMatch
import koharia.suwayomi.SuwayomiMigrationOptions
import koharia.suwayomi.SuwayomiMigrationOutcome
import koharia.suwayomi.SuwayomiMigrationPhase
import koharia.suwayomi.SuwayomiMigrationRunFilters
import koharia.suwayomi.SuwayomiMigrationRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Step 4: resolve a target for every selected entry, then commit the batch. Matches are found by
 * searching the configured target sources in order and keeping the closest title, exactly like the
 * server client's migration list, and each row can be re-pointed, skipped or committed on its own.
 */
class SuwayomiMigrationRunScreenModel(
    private val source: SuwayomiSource,
    private val draftId: Long,
) : StateScreenModel<SuwayomiMigrationRunScreenModel.State>(State()) {
    private val session = source.session()
    init {
        bindSession(session)
    }
    private val runner = SuwayomiMigrationRunner(source)
    private val errorChannel = Channel<Throwable>(Channel.BUFFERED)
    val errors = errorChannel.receiveAsFlow()
    private var searchJob: Job? = null
    private var commitJob: Job? = null

    private val draft get() = SuwayomiMigrationDrafts.get(draftId, session.identity)

    fun start() {
        if (searchJob?.isActive == true) return
        val draft = draft ?: run {
            mutableState.update { it.copy(loaded = true, error = IllegalStateException("migration draft is gone")) }
            return
        }
        val targets = draft.targetSourceIds
        if (targets.isEmpty()) {
            mutableState.update { it.copy(loaded = true, missingTargets = true) }
            return
        }
        mutableState.update {
            it.copy(
                candidates = draft.candidates,
                options = draft.options,
                filters = draft.filters,
                loaded = true,
                searching = true,
                phases = draft.candidates.associate { candidate ->
                    candidate.from.id to SuwayomiMigrationPhase.QUEUED
                },
            )
        }
        searchJob = screenModelScope.launch {
            // One catalogue read serves every candidate, so a large batch does not refetch it.
            val knownSources = withContext(Dispatchers.IO) {
                runCatching { session.catalog.sources().associateBy { it.id } }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    emptyMap()
                }
            }
            for (candidate in draft.candidates) {
                setPhase(candidate.from.id, SuwayomiMigrationPhase.SEARCHING)
                val match = withContext(Dispatchers.IO) {
                    runCatching {
                        runner.findTarget(candidate.from.title, targets, draft.extraSearchQuery, knownSources)
                    }.getOrElse { failure ->
                        if (failure is CancellationException) throw failure
                        errorChannel.send(failure)
                        SuwayomiMigrationMatch(null, null, 0.0)
                    }
                }
                matches = matches + (candidate.from.id to match)
                setPhase(
                    candidate.from.id,
                    if (match.target == null) SuwayomiMigrationPhase.NO_MATCH else SuwayomiMigrationPhase.READY,
                )
                mutableState.update { it.copy(matches = matches) }
            }
            mutableState.update { it.copy(searching = false) }
        }
    }

    private var matches: Map<Int, SuwayomiMigrationMatch> = emptyMap()

    private fun setPhase(mangaId: Int, phase: SuwayomiMigrationPhase) {
        mutableState.update { it.copy(phases = it.phases + (mangaId to phase)) }
    }

    fun setOptions(options: SuwayomiMigrationOptions) {
        draft?.options = options
        mutableState.update { it.copy(options = options) }
    }

    fun setFilters(filters: SuwayomiMigrationRunFilters) {
        draft?.filters = filters
        mutableState.update { it.copy(filters = filters) }
    }

    fun setExtraSearchQuery(query: String?) {
        draft?.extraSearchQuery = query?.takeIf(String::isNotBlank)
    }

    fun overrideTarget(mangaId: Int, target: koharia.suwayomi.SuwayomiManga, targetSourceName: String) {
        matches = matches + (mangaId to SuwayomiMigrationMatch(target, targetSourceName, confidence = 1.0))
        setPhase(mangaId, SuwayomiMigrationPhase.READY)
        mutableState.update { it.copy(matches = matches) }
    }

    fun skip(mangaId: Int) {
        matches = matches - mangaId
        setPhase(mangaId, SuwayomiMigrationPhase.NO_MATCH)
        mutableState.update { it.copy(matches = matches) }
    }

    fun retry() {
        matches = emptyMap()
        mutableState.update { it.copy(matches = emptyMap(), phases = emptyMap(), results = emptyList()) }
        start()
    }

    /** Commits the whole batch — or a single entry when [only] is set. */
    fun commit(deleteSource: Boolean, only: Int? = null) {
        if (commitJob?.isActive == true) return
        val draft = draft ?: return
        val options = state.value.options.copy(removeSource = deleteSource)
        val pending = draft.candidates.filter { candidate ->
            (only == null || candidate.from.id == only) &&
                state.value.phases[candidate.from.id] == SuwayomiMigrationPhase.READY &&
                state.value.matches[candidate.from.id]?.target != null
        }
        if (pending.isEmpty()) return
        val outcomes = mutableListOf<SuwayomiMigrationOutcome>()
        commitJob = screenModelScope.launch {
            mutableState.update { it.copy(committing = true, committed = 0, commitTotal = pending.size) }
            for (candidate in pending) {
                val target = state.value.matches[candidate.from.id]?.target ?: continue
                setPhase(candidate.from.id, SuwayomiMigrationPhase.COPYING)
                val outcome = withContext(Dispatchers.IO) {
                    runner.migrate(candidate.from, target, options) { phase ->
                        setPhase(candidate.from.id, phase)
                    }
                }
                outcomes += outcome
                setPhase(
                    candidate.from.id,
                    if (outcome.succeeded) SuwayomiMigrationPhase.DONE else SuwayomiMigrationPhase.FAILED,
                )
                mutableState.update { it.copy(committed = it.committed + 1) }
            }
            mutableState.update { it.copy(committing = false, results = outcomes) }
        }
    }

    fun clearResults() = mutableState.update { it.copy(results = emptyList()) }

    fun release() {
        searchJob?.cancel()
        SuwayomiMigrationDrafts.release(draftId)
    }

    data class State(
        val candidates: List<koharia.suwayomi.SuwayomiMigrationCandidate> = emptyList(),
        val matches: Map<Int, SuwayomiMigrationMatch> = emptyMap(),
        val phases: Map<Int, SuwayomiMigrationPhase> = emptyMap(),
        val options: SuwayomiMigrationOptions = SuwayomiMigrationOptions(),
        val filters: SuwayomiMigrationRunFilters = SuwayomiMigrationRunFilters(),
        val loaded: Boolean = false,
        val searching: Boolean = false,
        val missingTargets: Boolean = false,
        val committing: Boolean = false,
        val committed: Int = 0,
        val commitTotal: Int = 0,
        val results: List<SuwayomiMigrationOutcome> = emptyList(),
        val error: Throwable? = null,
    ) {
        val readyCount: Int get() = phases.count { it.value == SuwayomiMigrationPhase.READY }
    }
}
