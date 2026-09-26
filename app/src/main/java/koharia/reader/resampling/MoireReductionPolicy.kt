package koharia.reader.resampling

internal object MoireReductionPolicy {
    const val DEFAULT_THRESHOLD = 50
    val thresholds = listOf(25, 33, 50, 75, 100)

    fun normalize(percent: Int): Int = percent.takeIf { it in thresholds } ?: DEFAULT_THRESHOLD

    fun shouldFilter(displayScale: Float, thresholdPercent: Int): Boolean =
        displayScale > 0f && displayScale < 1f &&
            displayScale <= normalize(thresholdPercent) / 100f + 0.000001f
}
