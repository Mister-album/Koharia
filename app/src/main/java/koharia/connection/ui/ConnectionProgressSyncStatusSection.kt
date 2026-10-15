package koharia.connection.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import koharia.connection.ConnectionProgressSyncStatus
import koharia.connection.ConnectionProgressSyncStatusAdapter
import koharia.connection.ConnectionReadStatusReview
import koharia.connection.ConnectionReadStatusReviewAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** One batched local subscription for the series, shared by its list and grid presentations. */
@Composable
fun ConnectionProgressSyncStatusSection(sourceId: Long, chapters: Map<String, String>) {
    val manager = remember { Injekt.get<SourceManager>() }
    val sources by remember(manager) { manager.catalogueSources }.collectAsState(manager.getCatalogueSources())
    val source = sources.firstOrNull { it.id == sourceId } ?: return
    val adapter = source as? ConnectionProgressSyncStatusAdapter ?: return
    val provider = (source as? koharia.connection.ConnectionSource)?.providerId?.let {
        Injekt.get<koharia.connection.ConnectionRegistry>().provider(it)?.displayName
    } ?: source.name
    ConnectionProgressSyncStatusSection(adapter, provider, chapters)
}

@Composable
internal fun ConnectionProgressSyncStatusSection(
    adapter: ConnectionProgressSyncStatusAdapter,
    providerName: String,
    chapters: Map<String, String>,
) {
    val urls = remember(chapters) { chapters.keys.toList() }
    var states by remember(adapter, urls) {
        mutableStateOf<Map<String, Set<ConnectionProgressSyncStatus>>>(emptyMap())
    }
    var showDetails by remember(adapter, urls) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(adapter, urls, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                adapter.observeProgressSyncStatus(urls).collect {
                    states = it
                    if (it.isEmpty()) showDetails = false
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                states = urls.associateWith { setOf(ConnectionProgressSyncStatus.UNAVAILABLE) }
            }
        }
    }
    if (states.isEmpty()) return
    TextButton(onClick = { showDetails = true }) {
        Text(stringResource(MR.strings.connection_sync_status_count, providerName, states.size))
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(stringResource(MR.strings.connection_sync_status_title, providerName)) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(states.entries.toList(), key = { it.key }) { (url, status) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(2.dp)) {
                            Text(chapters[url]?.takeIf { it.isNotBlank() } ?: stringResource(MR.strings.unknown_title))
                            status.sortedBy { it.ordinal }.forEach { Text(stringResource(it.description())) }
                            if (adapter is ConnectionReadStatusReviewAdapter && status.any {
                                    it != ConnectionProgressSyncStatus.UNVERIFIED_OWNER &&
                                        it != ConnectionProgressSyncStatus.UNAVAILABLE &&
                                        it != ConnectionProgressSyncStatus.LOCAL_ONLY
                                }
                            ) {
                                key(adapter, url) { ReadStatusReviewButton(adapter, url) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) { Text(stringResource(MR.strings.action_close)) }
            },
        )
    }
}

@Composable
private fun ReadStatusReviewButton(adapter: ConnectionReadStatusReviewAdapter, url: String) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<ConnectionReadStatusReview?>(null) }
    var failed by remember { mutableStateOf(false) }
    var noExplicitAction by remember { mutableStateOf(false) }
    TextButton(
        enabled = !busy,
        onClick = {
            busy = true
            failed = false
            noExplicitAction = false
            scope.launch {
                try {
                    review = withContext(Dispatchers.IO) { adapter.reviewReadStatus(url) }
                    noExplicitAction = review == null
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failed = true
                } finally {
                    busy = false
                }
            }
        },
    ) { Text(stringResource(MR.strings.connection_sync_review)) }
    if (failed) Text(stringResource(MR.strings.connection_sync_review_failed))
    if (noExplicitAction) Text(stringResource(MR.strings.connection_sync_review_no_mark))
    review?.let { captured ->
        AlertDialog(
            onDismissRequest = { if (!busy) review = null },
            title = { Text(stringResource(MR.strings.connection_sync_review)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(
                            if (captured.localRead) {
                                MR.strings.connection_sync_review_local_read
                            } else {
                                MR.strings.connection_sync_review_local_unread
                            },
                        ),
                    )
                    Text(
                        stringResource(
                            when {
                                captured.remoteRead -> MR.strings.connection_sync_review_remote_read
                                captured.remoteStarted -> MR.strings.connection_sync_review_remote_started
                                else -> MR.strings.connection_sync_review_remote_unread
                            },
                        ),
                    )
                    Text(stringResource(MR.strings.connection_sync_review_help))
                    if (captured.uncertainRequest) Text(stringResource(MR.strings.connection_sync_review_uncertain))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { captured.reissue() }
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                failed = true
                            } finally {
                                busy = false
                                review = null
                            }
                        }
                    },
                ) { Text(stringResource(MR.strings.connection_sync_review_reissue)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { review = null }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

private fun ConnectionProgressSyncStatus.description() = when (this) {
    ConnectionProgressSyncStatus.PENDING -> MR.strings.connection_sync_status_pending
    ConnectionProgressSyncStatus.HELD -> MR.strings.connection_sync_status_held
    ConnectionProgressSyncStatus.CONFLICT -> MR.strings.connection_sync_status_conflict
    ConnectionProgressSyncStatus.DENIED -> MR.strings.connection_sync_status_denied
    ConnectionProgressSyncStatus.VERIFYING -> MR.strings.connection_sync_status_verifying
    ConnectionProgressSyncStatus.UNVERIFIED_OWNER -> MR.strings.connection_sync_status_unverified
    ConnectionProgressSyncStatus.LOCAL_ONLY -> MR.strings.connection_sync_status_local_only
    ConnectionProgressSyncStatus.UNAVAILABLE -> MR.strings.connection_sync_status_unavailable
}
