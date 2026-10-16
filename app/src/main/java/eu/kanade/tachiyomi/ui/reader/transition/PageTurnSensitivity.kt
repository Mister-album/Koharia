package eu.kanade.tachiyomi.ui.reader.transition

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import kotlin.math.abs

enum class PageTurnSensitivity(val titleRes: StringResource, internal val thresholdScale: Float) {
    LOWEST(MR.strings.pref_lowest, 2f),
    LOW(MR.strings.pref_low, 1.5f),
    NORMAL(MR.strings.label_default, 1f),
    HIGH(MR.strings.pref_high, 0.65f),
    HIGHEST(MR.strings.pref_highest, 0.4f),
}

internal class PageTurnSwipeThresholds(
    private val touchSlop: Int,
    private val density: Float,
    private val minimumFlingVelocity: Int,
    private val sensitivity: PageTurnSensitivity,
    private val nativeDrag: Boolean = false,
) {
    fun shouldCommit(displacement: Float, velocity: Float, viewportSize: Int, delta: Int): Boolean {
        if (displacement * delta >= 0f) return false
        val scale = sensitivity.thresholdScale
        val distance = abs(displacement)
        // ViewPager's slow drag normally needs 60% plus its paging slop. Lower levels must
        // remain less sensitive than that default, while higher levels allow short swipes.
        val dragDistance = if (nativeDrag && scale >= 1f) {
            viewportSize * (0.6f + (scale - 1f) * 0.3f) + touchSlop * 2f
        } else {
            viewportSize * 0.18f * scale
        }
        if (distance >= maxOf(touchSlop * 2f, dragDistance)) return true
        return distance >= maxOf(touchSlop * 2f, 25f * density * scale) &&
            velocity * delta < 0f && abs(velocity) >= maxOf(minimumFlingVelocity.toFloat(), 400f * density * scale)
    }
}
