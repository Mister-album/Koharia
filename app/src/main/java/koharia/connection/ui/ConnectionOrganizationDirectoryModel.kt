package koharia.connection.ui

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import koharia.connection.ConnectionOrganizationDirectory
import koharia.connection.ConnectionOrganizationEntry
import koharia.connection.ConnectionOrganizationPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO

data class ConnectionOrganizationDirectoryState(
    val entries: List<ConnectionOrganizationEntry> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = true,
    val error: Throwable? = null,
)

class ConnectionOrganizationDirectoryModel(
    private val directory: ConnectionOrganizationDirectory,
    private val pages: List<ConnectionOrganizationPage>,
) : StateScreenModel<ConnectionOrganizationDirectoryState>(ConnectionOrganizationDirectoryState()) {
    private var request: Job? = null
    private var generation = 0

    init {
        load()
        screenModelScope.launch { directory.changes.collectLatest { load(refresh = true) } }
    }

    fun load(refresh: Boolean = false) {
        request?.cancel()
        val revision = ++generation
        request = screenModelScope.launchIO {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                directory.checkActive()
                val entries = directory.entries(pages, refresh)
                directory.checkActive()
                if (revision == generation) {
                    mutableState.update {
                        it.copy(entries = entries, loaded = true, loading = false)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (revision == generation) mutableState.update { it.copy(loading = false, error = error) }
            }
        }
    }
}
