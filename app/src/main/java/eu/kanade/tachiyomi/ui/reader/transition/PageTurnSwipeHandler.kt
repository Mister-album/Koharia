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
    private val canIntercept: (Int) -> Boolean,
    private val cancelChildren: (MotionEvent) -> Unit,
    private val onTurn: (Int, PageTurnOrigin) -> Unit,
) {
    private val configuration = ViewConfiguration.get(context)
    private val touchSlop = configuration.scaledTouchSlop
    private val minimumVelocity = maxOf(
        configuration.scaledMinimumFlingVelocity.toFloat(),
        400f * context.resources.displayMetrics.density,
    )
    private val minimumDistance = maxOf(touchSlop * 2f, 25f * context.resources.displayMetrics.density)
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
            if (candidate) tracker = VelocityTracker.obtain()
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
                candidate = false
                canceled = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (consumed) return true
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
                        cancelChildren(event)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val handled = consumed
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
                val handled = consumed
                reset()
                return handled
            }
        }
        return consumed
    }

    private fun shouldCommit(event: MotionEvent): Boolean {
        val displacement = if (horizontal) event.x - startX else event.y - startY
        if (displacement * delta >= 0f) return false
        val (width, height) = viewportSize()
        val size = if (horizontal) width else height
        if (abs(displacement) >= maxOf(touchSlop * 2f, size * 0.18f)) return true
        tracker?.computeCurrentVelocity(1_000)
        val velocity = if (horizontal) tracker?.xVelocity else tracker?.yVelocity
        return abs(displacement) >= minimumDistance && velocity != null &&
            velocity * delta < 0f && abs(velocity) >= minimumVelocity
    }

    fun reset() {
        tracker?.recycle()
        tracker = null
        candidate = false
        consumed = false
        canceled = false
        delta = 0
    }
}
