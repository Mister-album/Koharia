package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect

/**
 * Decides when a page turn has to wait for its destination page instead of moving the pager
 * immediately, and keeps the turns requested while waiting.
 *
 * A destination page that is still loading renders as an empty holder with a progress indicator,
 * so moving onto it shows a blank screen. Volume keys can be pressed faster than a page is
 * delivered, which is why the requested turns are banked and replayed as pages become renderable.
 */
internal object PagerPageTurnPolicy {

    /**
     * Upper bound on the turns banked while the destination page renders. A reader that cannot
     * keep up must not accumulate an unbounded backlog of turns behind one slow page.
     */
    const val MAX_PENDING_TURNS = 24

    fun shouldInterceptSwipe(effect: PageTransitionEffect, horizontal: Boolean, animationsEnabled: Boolean): Boolean =
        !animationsEnabled || effect == PageTransitionEffect.NONE ||
            (horizontal && effect == PageTransitionEffect.CURL)

    /**
     * Whether the turn must be deferred until its destination page is rendered.
     *
     * Turns that do not go from one page to another (chapter transitions) keep the pager's own
     * handling, because there is no page image to wait for.
     */
    fun shouldWaitForTarget(
        turnInFlight: Boolean,
        pagerIdle: Boolean,
        sourceIsPage: Boolean,
        targetIsPage: Boolean,
        targetHolderAttached: Boolean,
        targetRendered: Boolean,
    ): Boolean {
        if (!sourceIsPage || !targetIsPage) return false
        if (turnInFlight || !pagerIdle) return true
        // A holder that is not attached yet cannot report readiness, so waiting would only stall
        // the reader until the fallback timeout.
        return targetHolderAttached && !targetRendered
    }

    /** Banks another requested turn, keeping the net direction the user asked for. */
    fun accumulate(pending: Int, delta: Int): Int = (pending + delta).coerceIn(-MAX_PENDING_TURNS, MAX_PENDING_TURNS)

    /** Next single page step to apply from the banked turns; 0 when no turn is banked. */
    fun nextStep(pending: Int): Int = pending.coerceIn(-1, 1)
}
