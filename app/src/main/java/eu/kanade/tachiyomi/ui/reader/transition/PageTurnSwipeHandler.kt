package eu.kanade.tachiyomi.ui.reader.transition

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import kotlin.math.abs

internal class PageTurnSwipeHandler(
    context: Context,
    private val horizontal: Boolean,
    private val viewportSize: () -> Pair<Int, Int>,
    private val enabled: () -> Boolean,
    private val sensitivity: () -> PageTurnSensitivity,
    private val dragWithContent: () -> Boolean = { false },
    private val canIntercept: (Int) -> Boolean,
    private val cancelChildren: (MotionEvent) -> Unit,
    private val onTurn: (Int, PageTurnOrigin) -> Unit,
) {
    private val configuration = ViewConfiguration.get(context)
    private val touchSlop = configuration.scaledTouchSlop
    private val density = context.resources.displayMetrics.density
    private var thresholds: PageTurnSwipeThresholds? = null
    private var nativeDrag = false
    private var tracker: VelocityTracker? = null
    private var candidate = false
    private var consumed = false
    private var canceled = false
    private var delta = 0
    private var startX = 0f
    private var startY = 0f

    fun handle(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            reset()
            candidate = enabled()
            nativeDrag = dragWithContent()
            if (candidate) {
                tracker = VelocityTracker.obtain()
                // Keep one gesture consistent; setting changes apply to the next swipe.
                thresholds = PageTurnSwipeThresholds(
                    touchSlop,
                    density,
                    configuration.scaledMinimumFlingVelocity,
                    sensitivity(),
                    nativeDrag = nativeDrag,
                )
            }
            startX = event.x
            startY = event.y
        }
        tracker?.addMovement(event)
        if (!enabled()) {
            candidate = false
            canceled = true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (nativeDrag) reset()
                candidate = false
                canceled = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (consumed) return !nativeDrag
                if (!candidate || event.pointerCount != 1) return false
                val distance = if (horizontal) event.x - startX else event.y - startY
                val crossDistance = abs(if (horizontal) event.y - startY else event.x - startX)
                if ((crossDistance > touchSlop && crossDistance >= abs(distance)) ||
                    event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()
                ) {
                    candidate = false
                    return false
                }
                if (abs(distance) > touchSlop && abs(distance) > crossDistance * 1.25f) {
                    candidate = false
                    delta = if (distance < 0f) 1 else -1
                    if (canIntercept(delta)) {
                        consumed = true
                        if (!nativeDrag) cancelChildren(event)
                        return !nativeDrag
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val handled = consumed
                // Keep native drag feedback, but replace its fixed release threshold.
                if (handled && nativeDrag) cancelChildren(event)
                if (handled && !canceled && shouldCommit(event)) {
                    val (width, height) = viewportSize()
                    val origin = PageTurnOrigin(
                        startX / width.coerceAtLeast(1),
                        startY / height.coerceAtLeast(1),
                        PageTurnCause.GESTURE,
                    ).normalized()
                    val turnDelta = delta
                    reset()
                    onTurn(turnDelta, origin)
                } else {
                    reset()
                }
                return handled
            }
            MotionEvent.ACTION_CANCEL -> {
                val handled = consumed && !nativeDrag
                reset()
                return handled
            }
        }
        return consumed && !nativeDrag
    }

    private fun shouldCommit(event: MotionEvent): Boolean {
        val displacement = if (horizontal) event.x - startX else event.y - startY
        val (width, height) = viewportSize()
        val size = if (horizontal) width else height
        tracker?.computeCurrentVelocity(1_000)
        val velocity = if (horizontal) tracker?.xVelocity else tracker?.yVelocity
        return thresholds?.shouldCommit(displacement, velocity ?: 0f, size, delta) == true
    }

    fun reset() {
        tracker?.recycle()
        tracker = null
        thresholds = null
        nativeDrag = false
        candidate = false
        consumed = false
        canceled = false
        delta = 0
    }
}
