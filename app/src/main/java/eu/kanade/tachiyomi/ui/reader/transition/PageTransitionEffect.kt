package eu.kanade.tachiyomi.ui.reader.transition

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

enum class PageTransitionEffect(
    val value: Int,
    val titleRes: StringResource,
) {
    SLIDE(0, MR.strings.page_transition_slide),
    COVER(1, MR.strings.page_transition_cover),
    CURL(2, MR.strings.page_transition_curl),
    VERTICAL(3, MR.strings.page_transition_vertical),
    FADE(4, MR.strings.page_transition_fade),
    DEPTH(5, MR.strings.page_transition_depth),
    NONE(6, MR.strings.page_transition_none),
    ;

    companion object {
        fun fromPreference(value: Int): PageTransitionEffect =
            entries.firstOrNull { it.value == value } ?: SLIDE
    }
}

enum class PageTurnDirection {
    FORWARD,
    BACKWARD,
}

enum class PageTurnCause {
    TAP,
    GESTURE,
    KEY,
    PROGRAMMATIC,
    RESTORE,
    LAYOUT_REBUILD,
}

enum class PageTransitionAxis {
    HORIZONTAL,
    VERTICAL,
}

data class PageTransitionSpec(
    val effect: PageTransitionEffect,
    val direction: PageTurnDirection,
    val axis: PageTransitionAxis,
    val isRightToLeft: Boolean,
    val cause: PageTurnCause,
    val durationMillis: Long,
    val allowAnimation: Boolean,
)

data class PageTurnOrigin(
    val xFraction: Float,
    val yFraction: Float,
    val cause: PageTurnCause,
) {
    fun normalized(): PageTurnOrigin = copy(
        xFraction = xFraction.coerceIn(0f, 1f),
        yFraction = yFraction.coerceIn(0f, 1f),
    )

    companion object {
        fun center(cause: PageTurnCause): PageTurnOrigin = PageTurnOrigin(0.5f, 0.5f, cause)
    }
}
