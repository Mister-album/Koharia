@file:Suppress("ktlint:standard:max-line-length")

package koharia.suwayomi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Serially applies graphql-transport-ws events. Each reconnect starts with a fresh snapshot. */
class SuwayomiDownloadEvents(
    private val api: SuwayomiApi,
    private val json: Json,
    private val scope: CoroutineScope,
    private val onStatus: suspend (SuwayomiDownloadStatus?) -> Unit,
) {
    val connected = MutableStateFlow(false)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                var socket: WebSocket? = null
                val messages = Channel<String>(Channel.UNLIMITED)
                try {
                    val authorization = api.websocketAuthorization()
                    socket = api.client.newWebSocket(
                        api.authenticatedWebSocketRequest(),
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send(
                                    buildJsonObject {
                                        put("type", "connection_init")
                                        put(
                                            "payload",
                                            buildJsonObject {
                                                authorization?.let { put("Authorization", it) }
                                            },
                                        )
                                    }.toString(),
                                )
                            }
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                messages.trySend(text)
                            }
                            override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: Response?,
                            ) {
                                messages.close(t)
                            }
                            override fun onClosing(
                                webSocket: WebSocket,
                                code: Int,
                                reason: String,
                            ) {
                                webSocket.close(code, null)
                            }
                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                messages.close()
                            }
                        },
                    )
                    val queue = linkedMapOf<Int, SuwayomiDownloadItem>()
                    var acknowledged = false
                    while (isActive) {
                        val message = if (acknowledged) {
                            messages.receive()
                        } else {
                            withTimeout(15_000) {
                                messages.receive()
                            }
                        }
                        val root = json.parseToJsonElement(message).jsonObject
                        when (root["type"]?.jsonPrimitive?.contentOrNull) {
                            "connection_ack" -> {
                                acknowledged = true
                                connected.value = true
                                backoff = 1_000L
                                socket.send(SUBSCRIPTION)
                            }
                            "ping" -> socket.send(
                                buildJsonObject {
                                    put("type", "pong")
                                    root["payload"]?.let { put("payload", it) }
                                }.toString(),
                            )
                            "next" -> {
                                val payload = root["payload"]?.jsonObject ?: continue
                                if (payload["errors"] !=
                                    null
                                ) {
                                    throw SuwayomiException(SuwayomiException.Reason.PROTOCOL)
                                }
                                val changed =
                                    payload["data"]?.jsonObject?.get("downloadStatusChanged")?.jsonObject ?: continue
                                val status = if (changed["omittedUpdates"]?.jsonPrimitive?.contentOrNull == "true") {
                                    api.downloadStatus().also { snapshot ->
                                        queue.clear()
                                        snapshot.queue.forEach { queue[it.chapterId] = it }
                                    }
                                } else {
                                    applyDownloadEvent(json, queue, changed)
                                }
                                onStatus(status)
                            }
                            "error", "complete" -> break
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException &&
                        error !is kotlinx.coroutines.TimeoutCancellationException
                    ) {
                        throw error
                    }
                } finally {
                    connected.value = false
                    socket?.cancel()
                    messages.cancel()
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val SUBSCRIPTION = """{"id":"koharia-download","type":"subscribe","payload":{"query":"subscription{downloadStatusChanged(input:{maxUpdates:50}){state omittedUpdates initial{chapter{id mangaId name} manga{id title} state progress tries position} updates{type download{chapter{id mangaId name} manga{id title} state progress tries position}}}}"}}"""
    }
}

internal fun applyDownloadEvent(json: Json, queue: MutableMap<Int, SuwayomiDownloadItem>, changed: JsonObject): SuwayomiDownloadStatus {
    val initial = changed["initial"]?.takeUnless { it == JsonNull }?.jsonArray
    if (initial != null) {
        queue.clear()
        initial.forEach { node ->
            val item = json.decodeFromJsonElement<SuwayomiDownloadItem>(node)
            queue[item.chapterId] = item
        }
    }
    changed["updates"]?.takeUnless { it == JsonNull }?.jsonArray.orEmpty().forEach { update ->
        val item = json.decodeFromJsonElement<SuwayomiDownloadItem>(update.jsonObject.getValue("download"))
        if (update.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "DEQUEUED") {
            queue.remove(item.chapterId)
        } else {
            queue[item.chapterId] = item
        }
    }
    return SuwayomiDownloadStatus(
        changed["state"]?.jsonPrimitive?.contentOrNull ?: "STOPPED",
        queue.values.sortedBy {
            it.position
        },
    )
}
