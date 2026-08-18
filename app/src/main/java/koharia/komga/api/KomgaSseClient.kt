package koharia.komga.api

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import eu.kanade.tachiyomi.data.track.komga.KomgaProgressSyncService
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.networkStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat

class KomgaSseClient(
    private val context: Context,
    private val networkHelper: NetworkHelper,
    private val komgaProgressSyncService: Lazy<KomgaProgressSyncService>,
    private val baseUrlProvider: () -> String,
    private val sourceIdProvider: () -> Long?,
    private val headersProvider: () -> Headers,
    private val cachedOnlyProvider: () -> Boolean,
) : DefaultLifecycleObserver {

    private var appScope: CoroutineScope? = null
    private var eventSource: EventSource? = null
    private var isForeground = false
    private var isNetworkConnected = false
    private var isConnecting = false
    private var isStarted = false
    private var lastSkippedReason: String? = null
    private val progressSyncLock = Any()
    private val pendingBookIds = linkedSetOf<String>()
    private var pendingFullSync = false
    private var progressSyncJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (isStarted) return
        isStarted = true
        appScope = scope
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        context.networkStateFlow()
            .onEach { state ->
                // Komga is commonly hosted on a LAN without Android's validated
                // internet capability. A connected network is sufficient here;
                // the SSE request itself remains the reachability check.
                isNetworkConnected = state.isConnected || state.isWifi || state.isValidated
                checkAndReconnect()
            }
            .launchIn(scope)
    }

    override fun onStart(owner: LifecycleOwner) {
        isForeground = true
        checkAndReconnect()
    }

    override fun onStop(owner: LifecycleOwner) {
        isForeground = false
        disconnect(reason = "app moved to background")
    }

    fun reconnect() {
        disconnect(reason = "reconnect")
        checkAndReconnect()
    }

    private fun checkAndReconnect() {
        val cachedOnly = cachedOnlyProvider()
        if (isForeground && isNetworkConnected && !cachedOnly) {
            lastSkippedReason = null
            connect()
        } else {
            disconnect()
            val reason = when {
                !isForeground -> "app not in foreground"
                !isNetworkConnected -> "no connected network"
                cachedOnly -> "cached-only mode enabled"
                else -> "connection requirements not met"
            }
            if (lastSkippedReason != reason) {
                logcat(LogPriority.DEBUG) { "Komga SSE not connecting: $reason" }
                lastSkippedReason = reason
            }
        }
    }

    private fun connect() {
        if (isConnecting || eventSource != null) return
        isConnecting = true

        val baseUrl = baseUrlProvider()
        if (baseUrl.isBlank()) {
            isConnecting = false
            return
        }

        val httpUrl = "$baseUrl/api/v1/sse/v1/events".toHttpUrlOrNull()
        if (httpUrl == null) {
            isConnecting = false
            logcat(LogPriority.WARN) { "Komga SSE: invalid base URL: $baseUrl" }
            return
        }

        val request = Request.Builder()
            .url(httpUrl)
            .headers(headersProvider())
            .build()

        logcat(LogPriority.INFO) { "Komga SSE connecting to $baseUrl/api/v1/sse/v1/events" }

        val factory = EventSources.createFactory(networkHelper.client)
        eventSource = factory.newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    logcat(LogPriority.INFO) { "Komga SSE connected" }
                    isConnecting = false
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    logcat(LogPriority.DEBUG) { "Komga SSE event: type=$type, data=$data" }
                    handleEvent(type, data)
                }

                override fun onClosed(eventSource: EventSource) {
                    logcat(LogPriority.INFO) { "Komga SSE closed" }
                    this@KomgaSseClient.eventSource = null
                    isConnecting = false
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    logcat(LogPriority.ERROR, t) { "Komga SSE failure: ${response?.code}" }
                    this@KomgaSseClient.eventSource = null
                    isConnecting = false
                }
            },
        )
    }

    private fun disconnect(reason: String? = null) {
        val hadActiveConnection = eventSource != null || isConnecting
        eventSource?.cancel()
        eventSource = null
        isConnecting = false
        if (hadActiveConnection) {
            logcat(LogPriority.INFO) {
                buildString {
                    append("Komga SSE disconnected")
                    reason?.let {
                        append(": ")
                        append(it)
                    }
                }
            }
        }
    }

    private fun handleEvent(type: String?, data: String) {
        if (type == null) return
        when (type) {
            "ReadProgressChanged", "ReadProgressDeleted" -> {
                val bookId = runCatching { JSONObject(data).optString("bookId") }
                    .getOrNull()
                    ?.takeIf(String::isNotBlank)
                logcat(LogPriority.DEBUG) {
                    "Komga SSE: received $type bookId=${bookId ?: "unknown"}, scheduling progress sync"
                }
                scheduleProgressSync(bookId)
            }
        }
    }

    private fun scheduleProgressSync(bookId: String?) {
        val scope = appScope ?: return
        synchronized(progressSyncLock) {
            if (bookId == null) {
                pendingFullSync = true
            } else {
                pendingBookIds += bookId
            }
            if (progressSyncJob?.isActive == true) return
            progressSyncJob = scope.launch(Dispatchers.IO) {
                drainProgressSyncQueue()
            }
        }
    }

    private suspend fun drainProgressSyncQueue() {
        while (true) {
            delay(PROGRESS_SYNC_DEBOUNCE_MS)
            val (bookIds, fullSync) = synchronized(progressSyncLock) {
                val ids = pendingBookIds.toList()
                pendingBookIds.clear()
                val shouldFullSync = pendingFullSync
                pendingFullSync = false
                ids to shouldFullSync
            }

            if (fullSync) {
                runCatching {
                    komgaProgressSyncService.value.syncHistoryFromServer(includeCompleted = true)
                }.onFailure { error ->
                    logcat(LogPriority.WARN, error) { "Komga SSE full progress sync failed" }
                }
            }

            val sourceId = sourceIdProvider()
            if (sourceId != null) {
                val baseUrl = baseUrlProvider().trimEnd('/')
                bookIds.forEach { bookId ->
                    runCatching {
                        komgaProgressSyncService.value.syncBookProgress(
                            sourceId = sourceId,
                            chapterUrl = "$baseUrl/api/v1/books/$bookId",
                        )
                    }.onFailure { error ->
                        logcat(LogPriority.WARN, error) {
                            "Komga SSE book progress sync failed bookId=$bookId"
                        }
                    }
                }
            }

            synchronized(progressSyncLock) {
                if (pendingBookIds.isEmpty() && !pendingFullSync) {
                    progressSyncJob = null
                    return
                }
            }
        }
    }

    private companion object {
        const val PROGRESS_SYNC_DEBOUNCE_MS = 300L
    }
}
