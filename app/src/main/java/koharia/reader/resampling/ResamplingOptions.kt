package koharia.reader.resampling

enum class ResamplingKernel(val nativeId: Int, val radius: Double) {
    MITCHELL(0, 2.0),
    CATMULL_ROM(1, 2.0),
    BILINEAR(2, 1.0),
    LANCZOS3(3, 3.0),
}

enum class ResamplingQuality(val inputDensity: Int) {
    SPEED(1),
    BALANCED(2),
    DETAIL(4),
}

/** Gaussian sigma in final output pixels, convolved with the chosen reconstruction kernel. */
internal data class ResamplingOptions(
    val kernel: ResamplingKernel = ResamplingKernel.MITCHELL,
    val softening: Double = 0.0,
    val quality: ResamplingQuality = ResamplingQuality.BALANCED,
) {
    fun shouldFilter(displayScale: Float): Boolean =
        displayScale.isFinite() && displayScale > 0f && (displayScale != 1f || softening != 0.0)

    init {
        require(softening.isFinite() && softening in 0.0..1.0)
    }
}
