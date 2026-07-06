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
import tachiyomi.core.common.util.system.logcat

class KomgaSseClient(
    private val context: Context,
    private val networkHelper: NetworkHelper,
    private val komgaProgressSyncService: Lazy<KomgaProgressSyncService>,
    private val baseUrlProvider: () -> String,
    private val headersProvider: () -> Headers,
) : DefaultLifecycleObserver {

    private var appScope: CoroutineScope? = null
    private var eventSource: EventSource? = null
    private var isForeground = false
    private var isWifi = false
    private var isConnecting = false
    private var isStarted = false

    fun start(scope: CoroutineScope) {
        if (isStarted) return
        isStarted = true
        appScope = scope
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        context.networkStateFlow()
            .onEach { state ->
                isWifi = state.isWifi && state.isOnline
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
        disconnect()
    }

    private fun checkAndReconnect() {
        if (isForeground && isWifi) {
            connect()
        } else {
            disconnect()
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

    private fun disconnect() {
        eventSource?.cancel()
        eventSource = null
        isConnecting = false
        logcat(LogPriority.INFO) { "Komga SSE disconnected" }
    }

    private fun handleEvent(type: String?, data: String) {
        if (type == null) return
        when (type) {
            "ReadProgressChanged", "ReadProgressDeleted" -> {
                logcat(LogPriority.DEBUG) { "Komga SSE: received $type, scheduling history sync" }
                appScope?.launch(Dispatchers.IO) {
                    komgaProgressSyncService.value.syncHistoryFromServer()
                }
            }
        }
    }
}
