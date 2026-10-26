package koharia.suwayomi

import koharia.connection.NetworkDiagnostics
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

internal enum class SuwayomiNetworkOperation {
    CATEGORIES,
    SHELF,
    MANGA,
    CHAPTERS,
    CHAPTER,
    PAGES,
    DOWNLOADS,
    OTHER,
    ;

    companion object {
        fun from(query: String): SuwayomiNetworkOperation = when (
            query.substringAfter('{').substringBefore('(').substringBefore('{').trim()
        ) {
            "categories" -> CATEGORIES
            "mangas" -> SHELF
            "manga" -> MANGA
            "chapters" -> CHAPTERS
            "chapter" -> CHAPTER
            "fetchChapterPages" -> PAGES
            "downloadStatus" -> DOWNLOADS
            else -> OTHER
        }
    }
}

/** Fixed operation labels and timing only: no URLs, ids, credentials, queries or response bodies. */
internal class SuwayomiNetworkTimingEventListener(call: Call) : EventListener() {
    private val id = NetworkDiagnostics.callId(call)
    private val started = System.nanoTime()
    private val load = call.request().tag(SuwayomiLoadDiagnostics::class.java)
    private val origin = load?.origin ?: call.request().tag(SuwayomiLoadDiagnostics.Origin::class.java)
    private val operation = call.request().tag(SuwayomiNetworkOperation::class.java)?.name ?: when {
        "thumbnail" in call.request().url.pathSegments -> "THUMBNAIL"
        "page" in call.request().url.pathSegments -> "IMAGE"
        else -> "OTHER"
    }

    override fun callStart(call: Call) = record("start")
    override fun dispatcherQueueStart(call: Call, dispatcher: Dispatcher) =
        record("queue-start", "running=${dispatcher.runningCallsCount()} queued=${dispatcher.queuedCallsCount()}")
    override fun dispatcherQueueEnd(call: Call, dispatcher: Dispatcher) = record("queue-end")
    override fun requestHeadersEnd(call: Call, request: Request) = record("request-sent")
    override fun requestBodyEnd(call: Call, byteCount: Long) = record("request-body-sent")
    override fun responseHeadersEnd(call: Call, response: Response) = record("headers", "status=${response.code}")
    override fun responseBodyEnd(call: Call, byteCount: Long) = record("body-end", "bytes=$byteCount")
    override fun callEnd(call: Call) = record("end")
    override fun callFailed(
        call: Call,
        ioe: IOException,
    ) = record("failed", "error=${NetworkDiagnostics.errorType(ioe)}")
    override fun canceled(call: Call) = record("canceled")

    private fun record(event: String, extra: String = "") = NetworkDiagnostics.record(
        "SuwayomiNetwork call=$id operation=$operation event=$event elapsedMs=${(System.nanoTime() - started) / 1_000_000} load=${load?.id ?: 0} origin=${origin ?: "OTHER"} $extra",
    )
}
