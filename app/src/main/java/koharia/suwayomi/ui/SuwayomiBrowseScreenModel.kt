package koharia.suwayomi.ui

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiExtension
import koharia.suwayomi.SuwayomiExtensionAction
import koharia.suwayomi.SuwayomiResourceState
import koharia.suwayomi.SuwayomiSourceInfo
import koharia.suwayomi.SuwayomiSourceMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The session catalogue survives tab models; each inventory loads and fails independently. */
class SuwayomiBrowseScreenModel(private val source: SuwayomiSource) :
    StateScreenModel<SuwayomiBrowseScreenModel.State>(
        State(
            source.session().catalog.sourceInventory.state.value,
            source.session().catalog.extensionInventory.state.value,
        ),
    ) {
    private val session = source.session()
    init {
        bindSession(session)
    }
    private var sourceJob: Job? = null
    private var extensionJob: Job? = null
    private var actionJob: Job? = null
    private var pinJob: Job? = null

    init {
        screenModelScope.launch {
            session.catalog.sourceInventory.state.collect { value ->
                session.checkActive()
                mutableState.update { it.copy(sourceState = value) }
            }
        }
        screenModelScope.launch {
            session.catalog.extensionInventory.state.collect { value ->
                session.checkActive()
                mutableState.update { it.copy(extensionState = value) }
            }
        }
        loadSources(false)
        loadExtensions(false)
    }

    fun refresh() {
        loadSources(true)
        loadExtensions(true)
    }

    private fun loadSources(refresh: Boolean) {
        if (sourceJob?.isActive == true) return
        sourceJob = screenModelScope.launch(Dispatchers.IO) {
            try {
                session.catalog.sources(refresh)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // The resource owns the error and retains its previous successful snapshot.
            }
        }
    }

    private fun loadExtensions(refresh: Boolean) {
        if (extensionJob?.isActive == true) return
        extensionJob = screenModelScope.launch(Dispatchers.IO) {
            try {
                session.catalog.extensions(refresh)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }
    }

    fun refreshExtensions() = extensionAction(null, SuwayomiExtensionAction.UPDATE, fetchStores = true)
    fun install(extension: SuwayomiExtension) = extensionAction(extension, SuwayomiExtensionAction.INSTALL)
    fun update(extension: SuwayomiExtension) = extensionAction(extension, SuwayomiExtensionAction.UPDATE)
    fun uninstall(extension: SuwayomiExtension) = extensionAction(extension, SuwayomiExtensionAction.UNINSTALL)

    fun togglePin(info: SuwayomiSourceInfo) {
        if (pinJob?.isActive == true) return
        val pinned = !info.isPinned
        mutableState.update { current ->
            current.copy(
                sourceState = current.sourceState.copy(
                    value = current.sources.map { entry ->
                        if (entry.id == info.id) {
                            entry.copy(
                                meta = entry.meta.filterNot { it.key == SuwayomiSourceInfo.PINNED_META_KEY } +
                                    SuwayomiSourceMeta(SuwayomiSourceInfo.PINNED_META_KEY, pinned.toString()),
                            )
                        } else {
                            entry
                        }
                    },
                ),
            )
        }
        pinJob = screenModelScope.launch(Dispatchers.IO) {
            try {
                session.catalog.setSourcePinned(info.id, pinned)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                session.checkActive()
                mutableState.update {
                    it.copy(sourceState = session.catalog.sourceInventory.state.value, actionError = error)
                }
            }
        }
    }

    private fun extensionAction(
        extension: SuwayomiExtension?,
        action: SuwayomiExtensionAction,
        fetchStores: Boolean = false,
    ) {
        if (actionJob?.isActive == true) return
        actionJob = screenModelScope.launch(Dispatchers.IO) {
            mutableState.update {
                it.copy(actionLoading = true, actionError = null, pendingExtension = extension?.pkgName)
            }
            try {
                session.checkActive()
                if (fetchStores) {
                    session.catalog.extensions(fetchStores = true)
                } else if (extension != null) {
                    source.serverExtensionAction(extension, action, session)
                }
                session.checkActive()
                sourceJob?.join()
                extensionJob?.join()
                loadSources(false)
                loadExtensions(false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                session.checkActive()
                mutableState.update { it.copy(actionError = error) }
            } finally {
                mutableState.update { it.copy(actionLoading = false, pendingExtension = null) }
            }
        }
    }

    data class State(
        val sourceState: SuwayomiResourceState<List<SuwayomiSourceInfo>> = SuwayomiResourceState(),
        val extensionState: SuwayomiResourceState<List<SuwayomiExtension>> = SuwayomiResourceState(),
        val actionLoading: Boolean = false,
        val pendingExtension: String? = null,
        val actionError: Throwable? = null,
    ) {
        val sources get() = sourceState.value.orEmpty()
        val extensions get() = extensionState.value.orEmpty()
        val error get() = actionError ?: sourceState.error ?: extensionState.error
    }
}
