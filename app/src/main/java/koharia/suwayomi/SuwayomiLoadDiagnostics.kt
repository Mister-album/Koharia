package koharia.suwayomi

import eu.kanade.tachiyomi.BuildConfig
import koharia.connection.NetworkDiagnostics
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Ephemeral correlation only; never retain connection, chapter, URL or account identifiers. */
internal class SuwayomiLoadDiagnostics private constructor(val origin: Origin) : AbstractCoroutineContextElement(Key) {
    enum class Origin { PREVIEW, READER, CONTENT_REFRESH }

    val id = sequence.incrementAndGet()
    private val started = System.nanoTime()

    fun record(event: String, fields: String = "") = NetworkDiagnostics.record(
        "SuwayomiLoad load=$id origin=$origin event=$event elapsedMs=${(System.nanoTime() - started) / 1_000_000} $fields",
    )

    companion object Key : CoroutineContext.Key<SuwayomiLoadDiagnostics> {
        private val sequence = AtomicLong()

        suspend fun <T> trace(origin: Origin, refresh: Boolean, block: suspend (SuwayomiLoadDiagnostics?) -> T): T {
            if (!BuildConfig.DEBUG) return block(null)
            val trace = SuwayomiLoadDiagnostics(origin)
            trace.record("start", "refresh=$refresh")
            try {
                // Carry failures as values across the diagnostic context so coroutine stack
                // recovery does not replace exceptions that callers use for retry decisions.
                val result = withContext(trace) { runCatching { block(trace) } }
                return result.getOrThrow().also { trace.record("complete") }
            } catch (error: Exception) {
                val reason = (error as? SuwayomiException)?.reason?.name ?: NetworkDiagnostics.errorType(error)
                trace.record("failed", "reason=$reason")
                throw error
            }
        }
    }
}
