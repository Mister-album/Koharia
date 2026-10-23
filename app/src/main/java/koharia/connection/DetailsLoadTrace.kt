package koharia.connection

import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** Fixed stage labels and numeric/boolean fields only; never pass content, URLs or error messages. */
internal class DetailsLoadTrace(
    private val enabled: Boolean = BuildConfig.DEBUG,
    private val clock: () -> Long = System::nanoTime,
    private val sink: (String) -> Unit = NetworkDiagnostics::record,
) {
    private val id = if (enabled) sequence.incrementAndGet() else 0L
    private val started = if (enabled) clock() else 0L
    private val spans = AtomicLong()

    fun mark(event: String, fields: String = "") {
        if (!enabled) return
        runCatching { sink("DetailsLoad details=$id event=$event elapsedMs=${elapsed(started)} $fields") }
    }

    suspend fun <T> measure(stage: String, block: suspend () -> T): T {
        if (!enabled) return block()
        val span = spans.incrementAndGet()
        val begin = clock()
        val correlation = "details=$id span=$span stage=$stage"
        mark("stage-start", "span=$span stage=$stage")
        return try {
            withContext(requestContext.asContextElement(correlation)) { block() }.also {
                mark("stage-end", "span=$span stage=$stage stageMs=${elapsed(begin)} outcome=ok")
            }
        } catch (error: Throwable) {
            val outcome = if (error is CancellationException) "canceled" else "error"
            mark(
                "stage-end",
                "span=$span stage=$stage stageMs=${elapsed(begin)} outcome=$outcome " +
                    "error=${error.javaClass.simpleName} cause=${error.cause?.javaClass?.simpleName ?: "none"}",
            )
            throw error
        }
    }

    private fun elapsed(begin: Long) = (clock() - begin) / 1_000_000

    companion object {
        private val sequence = AtomicLong()
        private val requestContext = ThreadLocal<String?>()
        fun requestCorrelation(): String = requestContext.get() ?: "details=none"
    }
}
