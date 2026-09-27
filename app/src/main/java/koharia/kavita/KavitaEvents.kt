package koharia.kavita

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** SignalR JSON protocol over the existing OkHttp transport; reconnects do not invalidate shelves. */
class KavitaEvents(
    private val api: KavitaApiClient,
    private val scope: CoroutineScope,
    private val onEvents: suspend (List<KavitaEvent>) -> Unit,
) {
    private val events = Channel<KavitaEvent>(256)
    val connected = kotlinx.coroutines.flow.MutableStateFlow(false)

    suspend fun run() {
        val collector = scope.launch {
            for (first in events) {
                delay(300)
                val batch = mutableListOf(first)
                while (true) batch += events.tryReceive().getOrNull() ?: break
                var wait = 1_000L
                while (currentCoroutineContext().isActive) {
                    try {
                        onEvents(batch.distinct())
                        break
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        delay(wait)
                        wait = (wait * 2).coerceAtMost(60_000)
                    }
                }
            }
        }
        try {
            var backoff = 1_000L
            while (currentCoroutineContext().isActive) {
                try {
                    connect()
                    backoff = 1_000
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (error is KavitaException && error.status in setOf(404, 405)) return
                    if (error is KavitaException && error.status == 401) api.invalidateAuthentication()
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000)
            }
        } finally {
            collector.cancel()
        }
    }

    private suspend fun connect() {
        val negotiate = api.eventRequest("hubs/messages/negotiate?negotiateVersion=1").newBuilder()
            .post(ByteArray(0).toRequestBody()).build()
        val token = api.eventClient.newCall(negotiate).await().use {
            KavitaApiClient.checkResponse(it)
            val reply = api.json.parseToJsonElement(it.body.string()).jsonObject
            // Never follow a negotiation redirect with the user's credentials.
            if ("url" in reply) throw KavitaException(KavitaException.Reason.PROTOCOL)
            reply["connectionToken"]?.jsonPrimitive?.contentOrNull
                ?: reply["connectionId"]?.jsonPrimitive?.contentOrNull
                ?: throw KavitaException(KavitaException.Reason.PROTOCOL)
        }
        val request = api.eventRequest("hubs/messages").let {
            it.newBuilder().url(it.url.newBuilder().addQueryParameter("id", token).build()).build()
        }
        val finished = CompletableDeferred<Unit>()
        val handshake = CompletableDeferred<Unit>()
        val lastReceived = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
        val socket = api.eventClient.newWebSocket(
            request,
            object : WebSocketListener() {
                private var pending = ""
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"protocol":"json","version":1}""" + SEPARATOR)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    lastReceived.set(System.nanoTime())
                    pending += text
                    if (pending.length > 1_048_576) {
                        webSocket.cancel()
                        finished.complete(Unit)
                        return
                    }
                    while (SEPARATOR in pending) {
                        val record = pending.substringBefore(SEPARATOR)
                        pending = pending.substringAfter(SEPARATOR)
                        val json = runCatching {
                            api.json.parseToJsonElement(record).jsonObject
                        }.getOrNull() ?: continue
                        if (!handshake.isCompleted) {
                            if ("error" in json) {
                                webSocket.cancel()
                                handshake.completeExceptionally(KavitaException(KavitaException.Reason.PROTOCOL))
                                finished.complete(Unit)
                            } else {
                                handshake.complete(Unit)
                            }
                            continue
                        }
                        when (json["type"]?.jsonPrimitive?.intOrNull) {
                            1 -> {
                                val target = json["target"]?.jsonPrimitive?.contentOrNull ?: continue
                                val argument = json["arguments"]?.jsonArray?.firstOrNull() as? JsonObject
                                val body = (argument?.get("body") as? JsonObject) ?: argument ?: JsonObject(emptyMap())
                                if (events.trySend(KavitaEvent(target, body)).isFailure) {
                                    webSocket.cancel()
                                    finished.complete(Unit)
                                }
                            }
                            7 -> {
                                webSocket.close(1000, null)
                                finished.complete(Unit)
                            }
                        }
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    finished.complete(Unit)
                    handshake.completeExceptionally(KavitaException(KavitaException.Reason.NETWORK, response?.code))
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    finished.complete(Unit)
                }
            },
        )
        try {
            awaitKavitaHandshake(handshake)
            connected.value = true
            while (!finished.isCompleted) {
                delay(10_000)
                if (System.nanoTime() - lastReceived.get() > 45_000_000_000L) break
                socket.send("""{"type":6}""" + SEPARATOR)
            }
        } finally {
            connected.value = false
            socket.cancel()
        }
    }
    private companion object {
        const val SEPARATOR = '\u001e'
    }
}

data class KavitaEvent(val name: String, val body: JsonObject)

internal suspend fun awaitKavitaHandshake(handshake: CompletableDeferred<Unit>) {
    withTimeoutOrNull(15_000) { handshake.await() }
        ?: throw KavitaException(KavitaException.Reason.NETWORK)
}
