@file:Suppress("ktlint:standard:max-line-length")

package koharia.connection.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import koharia.connection.ConnectionServerDownloadState
import koharia.connection.ConnectionServerDownloadsAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** One state subscription per details screen, shared by list, grid and bulk actions. */
@Composable
fun rememberConnectionServerDownloadActions(
    adapter: ConnectionServerDownloadsAdapter?,
    mangaUrl: String,
    snackbar: SnackbarHostState,
): ConnectionServerDownloadActions? {
    if (adapter == null) return null
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var states by remember(adapter, mangaUrl) { mutableStateOf<Map<String, ConnectionServerDownloadState>>(emptyMap()) }
    var busy by remember(adapter, mangaUrl) { mutableStateOf(false) }
    var retry by remember(adapter, mangaUrl) { mutableStateOf(0) }
    LaunchedEffect(adapter, mangaUrl, retry) {
        try {
            adapter.serverChapterDownloads(mangaUrl).collect { states = it }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (snackbar.showSnackbar(
                    context.stringResource(MR.strings.server_download_error),
                    context.stringResource(MR.strings.action_retry),
                ) ==
                androidx.compose.material3.SnackbarResult.ActionPerformed
            ) {
                retry++
            }
        }
    }
    return ConnectionServerDownloadActions(states, busy) { urls ->
        if (!busy) {
            scope.launch {
                busy = true
                try {
                    adapter.downloadChaptersOnServer(urls)
                    retry++
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    snackbar.showSnackbar(context.stringResource(MR.strings.server_download_error))
                } finally {
                    busy = false
                }
            }
        }
    }
}

class ConnectionServerDownloadActions(
    val states: Map<String, ConnectionServerDownloadState>,
    val busy: Boolean,
    val enqueue: (List<String>) -> Unit,
) {
    @Composable
    fun ChapterButton(url: String, enabled: Boolean = true) {
        val state = states[url]
        val downloadable =
            state == ConnectionServerDownloadState.AVAILABLE || state == ConnectionServerDownloadState.ERROR
        val label = when (state) {
            ConnectionServerDownloadState.DOWNLOADED -> MR.strings.server_download_complete
            ConnectionServerDownloadState.DOWNLOADING, ConnectionServerDownloadState.QUEUED -> MR.strings.server_download_pending
            else -> MR.strings.server_download_action
        }
        IconButton(onClick = { enqueue(listOf(url)) }, enabled = enabled && !busy && downloadable) {
            Icon(
                when (state) {
                    ConnectionServerDownloadState.DOWNLOADED -> Icons.Outlined.CloudDone
                    ConnectionServerDownloadState.QUEUED, ConnectionServerDownloadState.DOWNLOADING -> Icons.Outlined.HourglassEmpty
                    ConnectionServerDownloadState.ERROR -> Icons.Outlined.ErrorOutline
                    else -> Icons.Outlined.CloudDownload
                },
                stringResource(label),
            )
        }
    }
}
