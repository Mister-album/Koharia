package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect

/** Gesture routing and bounded input queued while scroll animations finish. */
internal object PagerPageTurnPolicy {

    /**
     * Upper bound on the turns banked while a page animation finishes. A reader that cannot
     * keep up must not accumulate an unbounded backlog of turns behind one slow page.
     */
    const val MAX_PENDING_TURNS = 24

    fun shouldInterceptSwipe(
        effect: PageTransitionEffect,
        horizontal: Boolean,
        animationsEnabled: Boolean,
    ): Boolean =
        !animationsEnabled || effect == PageTransitionEffect.NONE ||
            (horizontal && effect == PageTransitionEffect.CURL)

    /** Banks another requested turn, keeping the net direction the user asked for. */
    fun accumulate(pending: Int, delta: Int): Int = (pending + delta).coerceIn(-MAX_PENDING_TURNS, MAX_PENDING_TURNS)

    /** Next single page step to apply from the banked turns; 0 when no turn is banked. */
    fun nextStep(pending: Int): Int = pending.coerceIn(-1, 1)
}
