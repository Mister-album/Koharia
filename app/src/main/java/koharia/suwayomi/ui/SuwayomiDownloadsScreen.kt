@file:Suppress("ktlint:standard:max-line-length")

package koharia.suwayomi.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiDownloadEvents
import koharia.suwayomi.SuwayomiDownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiDownloadsScreen(private val sourceId: Long) : Screen() {
    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? SuwayomiSource
        if (source == null) {
            EmptyScreen(MR.strings.connection_unavailable)
            return
        }
        val navigator = LocalNavigator.currentOrThrow
        val epoch by source.epoch.collectAsState()
        val model = rememberScreenModel(tag = "${source.instanceKey}:$epoch") { SuwayomiDownloadsScreenModel(source) }
        val state by model.state.collectAsState()
        val connected by model.events.connected.collectAsState()
        var clear by remember { mutableStateOf(false) }
        Scaffold(topBar = {
            AppBar(title = stringResource(MR.strings.server_download_queue), navigateUp = navigator::pop)
        }) { padding ->
            Column(Modifier.padding(padding)) {
                SuwayomiOperationStatus(state.loading, state.error, model::refresh)
                if (!connected) Text(stringResource(MR.strings.suwayomi_events_reconnecting))
                Row {
                    TextButton(onClick = model::start, enabled = !state.loading && connected && state.fresh) {
                        Text(stringResource(MR.strings.action_resume))
                    }
                    TextButton(onClick = model::pause, enabled = !state.loading && connected && state.fresh) {
                        Text(stringResource(MR.strings.action_pause))
                    }
                    TextButton(onClick = {
                        clear = true
                    }, enabled = !state.loading && connected && state.fresh && state.status.queue.isNotEmpty()) {
                        Text(stringResource(MR.strings.action_delete))
                    }
                }
                if (state.loaded && state.status.queue.isEmpty()) EmptyScreen(MR.strings.suwayomi_queue_empty)
                LazyColumn {
                    items(state.status.queue, key = { it.chapterId }) { item ->
                        ListItem(
                            headlineContent = { Text(item.chapter?.name.orEmpty()) },
                            supportingContent = {
                                Text(
                                    "${item.manga?.title.orEmpty()} · ${(
                                        item.progress.coerceIn(
                                            0f,
                                            1f,
                                        ) * 100
                                        ).toInt()}%",
                                )
                            },
                            trailingContent = {
                                Row {
                                    TextButton(onClick = {
                                        navigator.push(MangaScreen(0, true, sourceId, model.mangaUrl(item.mangaId)))
                                    }) { Text(stringResource(MR.strings.browse)) }
                                    TextButton(onClick = {
                                        model.dequeue(item.chapterId)
                                    }, enabled = !state.loading && connected && state.fresh) {
                                        Text(stringResource(MR.strings.action_cancel))
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        if (clear) {
            AlertDialog(
                onDismissRequest = { clear = false },
                title = { Text(stringResource(MR.strings.server_download_queue)) },
                text = { Text(stringResource(MR.strings.suwayomi_clear_queue_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        clear = false
                        model.clear()
                    }) { Text(stringResource(MR.strings.action_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { clear = false }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
    }
}

internal class SuwayomiDownloadsScreenModel(source: SuwayomiSource) : StateScreenModel<SuwayomiDownloadsScreenModel.State>(
    State(),
) {
    private val session = source.session()
    init {
        bindSession(session)
    }
    private var actionJob: Job? = null
    val events = SuwayomiDownloadEvents(session.api, session.api.apiJson, screenModelScope) { status ->
        session.checkActive()
        if (status != null) {
            session.downloadSnapshot.value = status
            mutableState.update { it.copy(status = status, loaded = true, fresh = true, error = null) }
        }
    }
    init {
        session.downloadSnapshot.value?.let {
            mutableState.update { current -> current.copy(status = it, loaded = true) }
        }
        refresh()
        events.start()
        screenModelScope.launch {
            events.connected.collect { connected ->
                if (!connected) mutableState.update { it.copy(fresh = false) }
            }
        }
    }
    fun mangaUrl(id: Int) = session.identity.mangaUrl(id)
    fun refresh() = action { session.api.downloadStatus() }
    fun start() = action { session.api.startDownloader() }
    fun pause() = action { session.api.stopDownloader() }
    fun clear() = action { session.api.clearDownloader() }
    fun dequeue(id: Int) = action { session.api.dequeueDownload(id) }
    private fun action(block: suspend () -> SuwayomiDownloadStatus) {
        if (actionJob?.isActive == true) return
        actionJob = screenModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                session.checkActive()
                val status = block()
                session.checkActive()
                session.downloadSnapshot.value = status
                mutableState.update { it.copy(status = status, loaded = true, fresh = true, loading = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(loading = false, error = error) }
            }
        }
    }
    override fun onDispose() {
        events.stop()
        super.onDispose()
    }
    data class State(
        val status: SuwayomiDownloadStatus = SuwayomiDownloadStatus(),
        val loaded: Boolean = false,
        val fresh: Boolean = false,
        val loading: Boolean = false,
        val error: Throwable? = null,
    )
}
