package koharia.reader.resampling

import java.util.concurrent.Semaphore

/** Execution limits are independent of the selected reconstruction kernel and quality. */
internal data class ResamplingBudget(
    val inputPixels: Int = 2 * 1024 * 1024,
    val outputPixels: Int = 1024 * 1024,
    val blockEdge: Int = 512,
    val cacheBytes: Long = minOf(Runtime.getRuntime().maxMemory() / 16, 32L * 1024 * 1024),
    val reuseJpegBands: Boolean = false,
    val pngBandRows: Int = 256,
) {
    init {
        require(inputPixels in 64..2 * 1024 * 1024)
        require(outputPixels in 1..1024 * 1024)
        require(blockEdge in 1..512)
        require(cacheBytes in 32..32L * 1024 * 1024)
        require(pngBandRows in 1..1024)
    }
}

/** Inject one shared scheduler into a group of decoders when comparing execution budgets. */
internal class ResamplingScheduler(concurrency: Int = 2) {
    init {
        require(concurrency in 1..2)
    }

    val permits = Semaphore(concurrency, true)

    companion object {
        val shared = ResamplingScheduler()
    }
}
