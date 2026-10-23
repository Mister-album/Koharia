package koharia.lanraragi

import koharia.connection.ConnectionAddressRouter
import koharia.connection.NetworkDiagnostics
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

internal class LanraragiNetworkTimingEventListener(
    call: Call,
    private val connectionId: Long?,
    private val primaryBase: HttpUrl,
    private val addressRouter: ConnectionAddressRouter?,
) : EventListener() {
    private val callId = NetworkDiagnostics.callId(call)
    private val endpoint = NetworkDiagnostics.endpoint(call.request())
    private val callStartedAt = System.nanoTime()
    private var queueStartedAt = 0L
    private var dnsStartedAt = 0L
    private var connectStartedAt = 0L
    private var connectionAcquiredAt = 0L
    private var requestHeadersEndedAt = 0L
    private var responseHeadersStartedAt = 0L
    private var responseBodyStartedAt = 0L
    private var connectAttempts = 0
    private var requestAttempts = 0

    override fun callStart(call: Call) = log("call-start", "callTimeoutMs=${call.timeout().timeoutNanos() / 1_000_000}")

    override fun secureConnectStart(call: Call) = log("tls-start")

    override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) = log("tls-end")

    override fun dispatcherQueueStart(call: Call, dispatcher: Dispatcher) {
        queueStartedAt = System.nanoTime()
        log("queue-start", "running=${dispatcher.runningCallsCount()} queued=${dispatcher.queuedCallsCount()}")
    }

    override fun dispatcherQueueEnd(call: Call, dispatcher: Dispatcher) {
        log("queue-end", "stageMs=${stageMillis(queueStartedAt)}")
    }

    override fun dnsStart(call: Call, domainName: String) {
        dnsStartedAt = System.nanoTime()
        log("dns-start")
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        log("dns-end", "stageMs=${stageMillis(dnsStartedAt)} addresses=${inetAddressList.size}")
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectAttempts++
        connectStartedAt = System.nanoTime()
        log("connect-start", "attempt=$connectAttempts proxy=${proxy.type()}")
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: okhttp3.Protocol?,
    ) {
        log("connect-end", "attempt=$connectAttempts stageMs=${stageMillis(connectStartedAt)} protocol=$protocol")
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: okhttp3.Protocol?,
        ioe: IOException,
    ) {
        log(
            "connect-failed",
            "attempt=$connectAttempts stageMs=${stageMillis(connectStartedAt)} error=${ioe.javaClass.simpleName}",
        )
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        connectionAcquiredAt = System.nanoTime()
        log("connection-acquired", "pooled=${connectAttempts == 0} protocol=${connection.protocol()}")
    }

    override fun requestHeadersStart(call: Call) {
        requestAttempts++
        log("request-headers-start", "attempt=$requestAttempts")
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        requestHeadersEndedAt = System.nanoTime()
        val target = when {
            ConnectionAddressRouter.owns(primaryBase, request.url) -> "primary"
            addressRouter?.ownsInternal(request.url) == true -> "internal"
            else -> "other"
        }
        log(
            "request-headers-end",
            "attempt=$requestAttempts sinceAcquireMs=${stageMillis(connectionAcquiredAt)} " +
                "target=$target endpoint=${NetworkDiagnostics.endpoint(request)} scheme=${request.url.scheme}",
        )
    }

    override fun requestFailed(call: Call, ioe: IOException) {
        log("request-failed", "attempt=$requestAttempts error=${ioe.javaClass.simpleName}")
    }

    override fun responseHeadersStart(call: Call) {
        responseHeadersStartedAt = System.nanoTime()
        log("response-headers-start", "ttfbMs=${stageMillis(requestHeadersEndedAt)}")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        log("response-headers-end", "status=${response.code} stageMs=${stageMillis(responseHeadersStartedAt)}")
    }

    override fun responseBodyStart(call: Call) {
        responseBodyStartedAt = System.nanoTime()
        log("response-body-start", "sinceHeadersMs=${stageMillis(responseHeadersStartedAt)}")
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        log("response-body-end", "stageMs=${stageMillis(responseBodyStartedAt)} bytes=$byteCount")
    }

    override fun responseFailed(call: Call, ioe: IOException) {
        log("response-failed", "error=${ioe.javaClass.simpleName}")
    }

    override fun retryDecision(call: Call, exception: IOException, retry: Boolean) {
        log("retry-decision", "retry=$retry error=${exception.javaClass.simpleName}")
    }

    override fun callEnd(call: Call) = log("call-end")

    override fun callFailed(call: Call, ioe: IOException) {
        log("call-failed", "error=${ioe.javaClass.simpleName}")
    }

    override fun canceled(call: Call) = log("canceled")

    private fun log(event: String, details: String = "") {
        NetworkDiagnostics.record(
            buildString {
                append("LanraragiNetwork: connectionId=")
                append(connectionId ?: "settings")
                append(" call=")
                append(callId)
                append(" endpoint=")
                append(endpoint)
                append(" event=")
                append(event)
                append(" elapsedMs=")
                append(stageMillis(callStartedAt))
                if (details.isNotEmpty()) {
                    append(' ')
                    append(details)
                }
            },
        )
    }

    private fun stageMillis(startedAt: Long): Long =
        if (startedAt == 0L) -1L else (System.nanoTime() - startedAt) / 1_000_000
}
