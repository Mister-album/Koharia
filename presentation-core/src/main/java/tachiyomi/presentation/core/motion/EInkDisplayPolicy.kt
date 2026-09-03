package tachiyomi.presentation.core.motion

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

@Immutable
data class EInkDisplayPolicy(
    val enabled: Boolean,
) {
    val animationsEnabled: Boolean
        get() = !enabled

    val useAnimatedProgress: Boolean
        get() = !enabled

    fun effectivePageTransition(preferred: Int, none: Int): Int = if (enabled) none else preferred

    fun effectiveSmoothScroll(preferred: Boolean): Boolean = preferred && !enabled

    fun effectiveAnimationDuration(preferredMillis: Int): Int = if (enabled) 0 else preferredMillis

    companion object {
        val Default = EInkDisplayPolicy(enabled = false)
    }
}

val LocalEInkDisplayPolicy = staticCompositionLocalOf { EInkDisplayPolicy.Default }
