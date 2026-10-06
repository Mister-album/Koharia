package koharia.suwayomi.ui

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiPreferenceChange
import koharia.suwayomi.SuwayomiSourcePreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Reads and writes the settings a source extension exposes. The server owns the storage; every write
 * returns the refreshed preference list, so the screen always renders the server's state.
 */
class SuwayomiSourcePreferencesScreenModel(
    private val source: SuwayomiSource,
    private val sourceId: Long,
) : ScreenModel {
    private val session = source.session()
    init {
        bindSession(session)
    }
    private var loadJob: kotlinx.coroutines.Job? = null
    private val cached = session.catalog.sourcePreferencesState(sourceId).value
    private val _state =
        MutableStateFlow(State(preferences = cached.value.orEmpty(), loaded = cached.loaded, loading = !cached.loaded))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (loadJob?.isActive == true) return
        _state.update { it.copy(loading = !it.loaded, error = null) }
        loadJob = screenModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { session.catalog.sourcePreferences(sourceId) }
            }
            result.fold(
                onSuccess = { preferences ->
                    session.checkActive()
                    _state.update {
                        it.copy(
                            loading = false,
                            preferences = preferences,
                            loaded = true,
                            error = null,
                        )
                    }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    session.checkActive()
                    logcat(LogPriority.WARN, error) { "Suwayomi source preferences failed for $sourceId" }
                    _state.update { it.copy(loading = false, error = error) }
                },
            )
        }
    }

    /**
     * Applies one edit. The server confirms with the full list, which replaces local state so a
     * rejected or clamped value cannot linger in the UI.
     */
    fun apply(preference: SuwayomiSourcePreference, value: Any) {
        if (!preference.enabled || _state.value.saving) return
        _state.update { it.copy(saving = true, error = null) }
        screenModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    session.catalog.updateSourcePreference(
                        id = sourceId,
                        position = preference.position,
                        change = SuwayomiPreferenceChange.of(preference, value),
                    )
                    session.catalog.sourcePreferences(sourceId)
                }
            }
            result.fold(
                onSuccess = { preferences ->
                    session.checkActive()
                    _state.update { it.copy(saving = false, preferences = preferences, loaded = true, error = null) }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    session.checkActive()
                    logcat(LogPriority.WARN, error) {
                        "Suwayomi source preference ${preference.position} write failed"
                    }
                    _state.update { it.copy(saving = false, error = error) }
                },
            )
        }
    }

    data class State(
        val preferences: List<SuwayomiSourcePreference> = emptyList(),
        val loaded: Boolean = false,
        val loading: Boolean = true,
        val saving: Boolean = false,
        val error: Throwable? = null,
    ) {
        val visiblePreferences: List<SuwayomiSourcePreference> get() = preferences.filter { it.visible }
    }
}
