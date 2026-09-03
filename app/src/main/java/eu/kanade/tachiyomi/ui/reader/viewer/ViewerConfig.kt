package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.domain.ui.EInkPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import tachiyomi.core.common.preference.Preference
import tachiyomi.presentation.core.motion.EInkDisplayPolicy
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Common configuration for all viewers.
 */
abstract class ViewerConfig(
    readerPreferences: ReaderPreferences,
    private val scope: CoroutineScope,
    eInkPreferences: EInkPreferences = Injekt.get(),
) {

    var imagePropertyChangedListener: (() -> Unit)? = null

    var navigationModeChangedListener: (() -> Unit)? = null

    var pageTransitionEffectChangedListener: (() -> Unit)? = null

    var tappingInverted = ReaderPreferences.TappingInvertMode.NONE
    var longTapEnabled = true
    var pageTransitionEffect = PageTransitionEffect.SLIDE
    var webtoonSmoothScroll = true
    var doubleTapAnimDuration = 500
    var volumeKeysEnabled = false
    var volumeKeysInverted = false
    var alwaysShowChapterTransition = true
    var navigationMode = 0
        protected set

    var forceNavigationOverlay = false

    var navigationOverlayOnStart = false

    var dualPageSplit = false
        protected set

    var dualPageInvert = false
        protected set

    var dualPageRotateToFit = false
        protected set

    var dualPageRotateToFitInvert = false
        protected set

    abstract var navigator: ViewerNavigation
        protected set

    init {
        readerPreferences.readWithLongTap
            .register({ longTapEnabled = it })

        combine(readerPreferences.pagerPageTransitionEffect.changes(), eInkPreferences.enabled.changes()) {
                preferred,
                eInkEnabled,
            ->
            EInkDisplayPolicy(eInkEnabled).effectivePageTransition(preferred, PageTransitionEffect.NONE.value)
        }
            .mapDistinct { PageTransitionEffect.fromPreference(it) }
            .onEach {
                pageTransitionEffect = it
                pageTransitionEffectChangedListener?.invoke()
            }
            .launchIn(scope)

        combine(readerPreferences.webtoonSmoothScroll.changes(), eInkPreferences.enabled.changes()) {
                preferred,
                eInkEnabled,
            ->
            EInkDisplayPolicy(eInkEnabled).effectiveSmoothScroll(preferred)
        }
            .mapDistinct { it }
            .onEach { webtoonSmoothScroll = it }
            .launchIn(scope)

        combine(readerPreferences.doubleTapAnimSpeed.changes(), eInkPreferences.enabled.changes()) {
                preferred,
                eInkEnabled,
            ->
            EInkDisplayPolicy(eInkEnabled).effectiveAnimationDuration(preferred)
        }
            .mapDistinct { it }
            .onEach { doubleTapAnimDuration = it }
            .launchIn(scope)

        readerPreferences.readWithVolumeKeys
            .register({ volumeKeysEnabled = it })

        readerPreferences.readWithVolumeKeysInverted
            .register({ volumeKeysInverted = it })

        readerPreferences.alwaysShowChapterTransition
            .register({ alwaysShowChapterTransition = it })

        forceNavigationOverlay = readerPreferences.showNavigationOverlayNewUser.get()
        if (forceNavigationOverlay) {
            readerPreferences.showNavigationOverlayNewUser.set(false)
        }

        readerPreferences.showNavigationOverlayOnStart
            .register({ navigationOverlayOnStart = it })
    }

    protected abstract fun defaultNavigation(): ViewerNavigation

    abstract fun updateNavigation(navigationMode: Int)

    fun <T> Preference<T>.register(
        valueAssignment: (T) -> Unit,
        onChanged: (T) -> Unit = {},
    ) {
        changes()
            .onEach { valueAssignment(it) }
            .distinctUntilChanged()
            .onEach { onChanged(it) }
            .launchIn(scope)
    }

    private fun <T, R> Flow<T>.mapDistinct(transform: (T) -> R) = map(transform).distinctUntilChanged()
}
