package koharia.komga.api

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import eu.kanade.tachiyomi.data.track.komga.KomgaProgressSyncService
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.networkStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
import java.util.concurrent.TimeUnit

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
    private val progressSyncQueue = KomgaSseProgressSyncQueue()
    private var progressSyncJob: Job? = null
    private var connectionGeneration = 0L
    private var activeTarget: KomgaSseConnectionTarget? = null
    private val eventSourceClient by lazy {
        // Keep the shared connection pool/interceptors, but do not apply the ordinary request
        // timeouts to a server-sent event stream that is expected to stay open indefinitely.
        networkHelper.client.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

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

        val sourceId = sourceIdProvider()
        if (sourceId == null) {
            isConnecting = false
            return
        }
        val baseUrl = baseUrlProvider().trimEnd('/')
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
        if (sourceIdProvider() != sourceId) {
            isConnecting = false
            return
        }
        val target = synchronized(progressSyncLock) {
            KomgaSseConnectionTarget(
                sourceId = sourceId,
                baseUrl = baseUrl,
                generation = ++connectionGeneration,
            ).also { activeTarget = it }
        }

        logcat(LogPriority.INFO) { "Komga SSE connecting to $baseUrl/api/v1/sse/v1/events" }

        val factory = EventSources.createFactory(eventSourceClient)
        eventSource = factory.newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    if (!isCurrentConnection(target)) return
                    logcat(LogPriority.INFO) { "Komga SSE connected" }
                    isConnecting = false
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (!isCurrentConnection(target)) return
                    logcat(LogPriority.DEBUG) { "Komga SSE event: type=$type, data=$data" }
                    handleEvent(target, type, data)
                }

                override fun onClosed(eventSource: EventSource) {
                    if (!finishConnection(target, eventSource)) return
                    logcat(LogPriority.INFO) { "Komga SSE closed" }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    if (!finishConnection(target, eventSource)) return
                    logcat(LogPriority.ERROR, t) { "Komga SSE failure: ${response?.code}" }
                }
            },
        )
    }

    private fun disconnect(reason: String? = null) {
        val hadActiveConnection = eventSource != null || isConnecting
        val sourceToCancel = eventSource
        eventSource = null
        isConnecting = false
        val syncJob = synchronized(progressSyncLock) {
            connectionGeneration++
            activeTarget = null
            progressSyncQueue.clear()
            progressSyncJob.also { progressSyncJob = null }
        }
        syncJob?.cancel()
        sourceToCancel?.cancel()
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

    private fun isCurrentConnection(target: KomgaSseConnectionTarget): Boolean =
        synchronized(progressSyncLock) { activeTarget == target }

    private fun finishConnection(target: KomgaSseConnectionTarget, source: EventSource): Boolean {
        val syncJob = synchronized(progressSyncLock) {
            if (activeTarget != target || eventSource !== source) return false
            activeTarget = null
            eventSource = null
            isConnecting = false
            progressSyncQueue.clear()
            progressSyncJob.also { progressSyncJob = null }
        }
        syncJob?.cancel()
        return true
    }

    private fun handleEvent(target: KomgaSseConnectionTarget, type: String?, data: String) {
        if (type == null) return
        when (type) {
            "ReadProgressChanged", "ReadProgressDeleted" -> {
                val bookId = runCatching { JSONObject(data).optString("bookId") }
                    .getOrNull()
                    ?.takeIf(String::isNotBlank)
                logcat(LogPriority.DEBUG) {
                    "Komga SSE: received $type bookId=${bookId ?: "unknown"}, scheduling progress sync"
                }
                scheduleProgressSync(target, bookId)
            }
        }
    }

    private fun scheduleProgressSync(target: KomgaSseConnectionTarget, bookId: String?) {
        val scope = appScope ?: return
        synchronized(progressSyncLock) {
            if (activeTarget != target) return
            progressSyncQueue.add(PendingKomgaProgressSync(target, bookId))
            if (progressSyncJob?.isActive == true) return
            val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                drainProgressSyncQueue()
            }
            progressSyncJob = job
            job.start()
        }
    }

    private suspend fun drainProgressSyncQueue() {
        val currentJob = currentCoroutineContext()[Job]
        while (true) {
            delay(PROGRESS_SYNC_DEBOUNCE_MS)
            val pending = synchronized(progressSyncLock) { progressSyncQueue.drain() }

            pending.forEach { request ->
                if (!isCurrentConnection(request.target)) return@forEach
                try {
                    if (request.bookId == null) {
                        komgaProgressSyncService.value.syncHistoryFromServer(
                            sourceId = request.target.sourceId,
                            includeCompleted = true,
                        )
                        komgaProgressSyncService.value.syncEpubProgressFromServer(request.target.sourceId)
                    } else {
                        komgaProgressSyncService.value.syncBookProgress(
                            sourceId = request.target.sourceId,
                            chapterUrl = "${request.target.baseUrl}/api/v1/books/${request.bookId}",
                        )
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logcat(LogPriority.WARN, error) {
                        "Komga SSE progress sync failed sourceId=${request.target.sourceId} " +
                            "bookId=${request.bookId ?: "unknown"}"
                    }
                }
            }

            synchronized(progressSyncLock) {
                if (progressSyncQueue.isEmpty()) {
                    if (progressSyncJob === currentJob) progressSyncJob = null
                    return
                }
            }
        }
    }

    private companion object {
        const val PROGRESS_SYNC_DEBOUNCE_MS = 300L
    }
}

internal data class KomgaSseConnectionTarget(
    val sourceId: Long,
    val baseUrl: String,
    val generation: Long,
)

internal data class PendingKomgaProgressSync(
    val target: KomgaSseConnectionTarget,
    val bookId: String?,
)

internal class KomgaSseProgressSyncQueue {
    private val pending = linkedSetOf<PendingKomgaProgressSync>()

    fun add(request: PendingKomgaProgressSync) {
        pending += request
    }

    fun drain(): List<PendingKomgaProgressSync> = pending.toList().also { pending.clear() }

    fun clear() = pending.clear()

    fun isEmpty(): Boolean = pending.isEmpty()
}
