package koharia.reader.resampling

import java.util.concurrent.atomic.AtomicLongArray

/** Opt-in diagnostics for controlled benchmarks. Production decoders leave this unset. */
internal class ResamplingTimings {
    enum class Stage {
        COPY_INPUT,
        OPEN_CODEC,
        DECODE_PIXELS,
        PREMULTIPLY,
        RESIZE,
        RECYCLE_CODEC,
        WAIT_PERMIT,
        WAIT_EXECUTOR,
    }

    private val nanos = AtomicLongArray(Stage.entries.size)
    private val counts = AtomicLongArray(Stage.entries.size)

    fun record(stage: Stage, elapsed: Long) {
        nanos.addAndGet(stage.ordinal, elapsed)
        counts.incrementAndGet(stage.ordinal)
    }

    fun csv(): String = Stage.entries.joinToString(",") { "${nanos[it.ordinal] / 1_000_000.0},${counts[it.ordinal]}" }

    companion object {
        val csvHeader: String = Stage.entries.joinToString(",") {
            "${it.name.lowercase()}_ms,${it.name.lowercase()}_count"
        }
    }
}

internal inline fun <T> ResamplingTimings?.measure(stage: ResamplingTimings.Stage, block: () -> T): T {
    if (this == null) return block()
    val started = System.nanoTime()
    try {
        return block()
    } finally {
        record(stage, System.nanoTime() - started)
    }
}
