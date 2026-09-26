package koharia.reader.resampling

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Coordinates use one image-wide grid, including when tile boundaries are not sample-aligned. */
internal data class RegionResamplingPlan(
    val scale: Double,
    val evenCrop: Boolean = false,
    val minimumSourceDimension: Int = Int.MAX_VALUE,
) {
    init {
        require(scale > 0 && scale <= 1 && scale.isFinite())
    }

    val inputSample: Int = run {
        var sample = 1
        while (
            sample < (1 shl 28) && sample * 2.0 <= 1.0 / (2.0 * scale) &&
            sample * 2 <= minimumSourceDimension
        ) {
            sample *= 2
        }
        sample
    }

    private val alignment get() = if (evenCrop) maxOf(2, inputSample) else inputSample

    fun outputBoundary(source: Int): Int = (source * scale).roundToInt()

    fun inputStart(output: Int): Int =
        (floor((output / scale - 3.0 / scale) / alignment).toInt() * alignment).coerceAtLeast(0)

    fun inputEnd(output: Int, limit: Int): Int =
        (ceil((output / scale + 3.0 / scale) / alignment).toLong() * alignment)
            .coerceAtMost((if (evenCrop) limit / inputSample * inputSample else limit).toLong()).toInt()
}
