package koharia.komga.api

import koharia.connection.NetworkDiagnostics
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/** Dev-only stage timings. Never records addresses, resource identities, headers or payloads. */
internal class KomgaNetworkTimingEventListener(call: Call) : EventListener() {
    private val correlation = koharia.connection.DetailsLoadTrace.requestCorrelation()
    private val callId = NetworkDiagnostics.callId(call)
    private val endpoint = endpoint(call.request())
    private val started = System.nanoTime()

    override fun callStart(call: Call) = record("start")

    override fun dispatcherQueueStart(call: Call, dispatcher: Dispatcher) =
        record("queue-start", "running=${dispatcher.runningCallsCount()} queued=${dispatcher.queuedCallsCount()}")

    override fun dispatcherQueueEnd(call: Call, dispatcher: Dispatcher) = record("queue-end")

    override fun dnsStart(call: Call, domainName: String) = record("dns-start")

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) =
        record("dns-end", "addresses=${inetAddressList.size}")

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = record("connect-start")

    override fun secureConnectStart(call: Call) = record("tls-start")

    override fun secureConnectEnd(call: Call, handshake: Handshake?) = record("tls-end")

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
        record("connect-end", "protocol=$protocol")

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) = record("connect-failed", "error=${NetworkDiagnostics.errorType(ioe)}")

    override fun connectionAcquired(call: Call, connection: Connection) = record("connection-acquired")

    override fun requestHeadersEnd(call: Call, request: Request) = record("request-sent", "route=${endpoint(request)}")

    override fun responseHeadersStart(call: Call) = record("response-start")

    override fun responseHeadersEnd(call: Call, response: Response) = record(
        "response-headers",
        "status=${response.code}",
    )

    override fun responseBodyEnd(call: Call, byteCount: Long) = record("body-end", "bytes=$byteCount")

    override fun retryDecision(call: Call, exception: IOException, retry: Boolean) =
        record("retry", "retry=$retry error=${NetworkDiagnostics.errorType(exception)}")

    override fun callEnd(call: Call) = record("end")

    override fun callFailed(call: Call, ioe: IOException) = record(
        "failed",
        "error=${NetworkDiagnostics.errorType(ioe)}",
    )

    override fun canceled(call: Call) = record("canceled")

    private fun record(event: String, details: String = "") = NetworkDiagnostics.record(
        "KomgaNetwork call=$callId $correlation endpoint=$endpoint event=$event elapsedMs=${(System.nanoTime() - started) / 1_000_000} $details",
    )

    private fun endpoint(request: Request): String {
        val path = request.url.pathSegments
        return when {
            "thumbnail" in path -> "thumbnail"
            "client-settings" in path -> "client-settings"
            "series" in path && path.lastOrNull() == "books" -> "series-books"
            "series" in path -> "series"
            "books" in path -> "book"
            "users" in path -> "user"
            "libraries" in path -> "libraries"
            else -> "other"
        }
    }
}
