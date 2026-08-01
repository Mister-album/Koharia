package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.viewpager.widget.DirectionalViewPager
import eu.kanade.tachiyomi.ui.reader.viewer.GestureDetectorWithLongTap
import kotlin.math.abs

/**
 * Pager implementation that listens for tap and long tap and allows temporarily disabling touch
 * events in order to work with child views that need to disable touch events on this parent. The
 * pager can also be declared to be vertical by creating it with [isHorizontal] to false.
 */
open class Pager(
    context: Context,
    val horizontalPaging: Boolean = true,
) : DirectionalViewPager(context, horizontalPaging) {

    /**
     * Tap listener function to execute when a tap is detected.
     */
    var tapListener: ((MotionEvent) -> Unit)? = null

    /**
     * Long tap listener function to execute when a long tap is detected.
     */
    var longTapListener: ((MotionEvent) -> Boolean)? = null

    /** Whether a horizontal swipe should be handled as a discrete page turn. */
    var canInterceptPageTurnSwipe: ((Int) -> Boolean)? = null

    /** Called after an intercepted horizontal swipe is released. */
    var pageTurnSwipeListener: ((Int, Float, Float) -> Unit)? = null

    /**
     * Gesture listener that implements tap and long tap events.
     */
    private val gestureListener = object : GestureDetectorWithLongTap.Listener() {
        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
            tapListener?.invoke(ev)
            return true
        }

        override fun onLongTapConfirmed(ev: MotionEvent) {
            if (!isTouchNavigationEnabled) return
            val listener = longTapListener
            if (listener != null && listener.invoke(ev)) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    /**
     * Gesture detector which handles motion events.
     */
    private val gestureDetector = GestureDetectorWithLongTap(context, gestureListener)

    /**
     * Whether the gesture detector is currently enabled.
     */
    private var isGestureDetectorEnabled = true

    /**
     * Whether touch events should reach ViewPager and the current page. Page-flip animation keeps
     * the tap detector active while disabling dragging, zooming, and long-press actions below it.
     */
    private var isTouchNavigationEnabled = true

    private val viewConfiguration = ViewConfiguration.get(context)
    private val touchSlop = viewConfiguration.scaledTouchSlop
    private val minimumFlingVelocity = maxOf(
        viewConfiguration.scaledMinimumFlingVelocity,
        (MINIMUM_FLING_VELOCITY_DP * resources.displayMetrics.density).toInt(),
    )
    private val minimumFlingDistance = maxOf(
        touchSlop * 2f,
        MINIMUM_FLING_DISTANCE_DP * resources.displayMetrics.density,
    )
    private var queuedTapEligible = false
    private var queuedTapDownX = 0f
    private var queuedTapDownY = 0f

    private var pageTurnSwipeCandidate = false
    private var pageTurnSwipeConsumed = false
    private var pageTurnSwipeCanceled = false
    private var pageTurnSwipeDelta = 0
    private var pageTurnSwipeDownX = 0f
    private var pageTurnSwipeDownY = 0f
    private var pageTurnSwipeVelocityTracker: VelocityTracker? = null

    /**
     * Dispatches a touch event.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isTouchNavigationEnabled) {
            handleQueuedPageFlipTap(ev)
            return true
        }
        if (handlePageTurnSwipe(ev)) return true
        val handled = super.dispatchTouchEvent(ev)
        if (isGestureDetectorEnabled) {
            gestureDetector.onTouchEvent(ev)
        }
        return handled
    }

    private fun handlePageTurnSwipe(ev: MotionEvent): Boolean {
        if (pageTurnSwipeListener == null || canInterceptPageTurnSwipe == null) {
            resetPageTurnSwipe()
            return false
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            resetPageTurnSwipe()
            pageTurnSwipeVelocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
        } else {
            pageTurnSwipeVelocityTracker?.addMovement(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pageTurnSwipeCandidate = true
                pageTurnSwipeConsumed = false
                pageTurnSwipeCanceled = false
                pageTurnSwipeDelta = 0
                pageTurnSwipeDownX = ev.x
                pageTurnSwipeDownY = ev.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pageTurnSwipeCandidate = false
                if (pageTurnSwipeConsumed) {
                    pageTurnSwipeCanceled = true
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pageTurnSwipeConsumed) return true
                if (!pageTurnSwipeCandidate || ev.pointerCount != 1) return false
                val deltaX = ev.x - pageTurnSwipeDownX
                val deltaY = ev.y - pageTurnSwipeDownY
                val horizontalDistance = abs(deltaX)
                val verticalDistance = abs(deltaY)
                if (verticalDistance > touchSlop && verticalDistance >= horizontalDistance) {
                    pageTurnSwipeCandidate = false
                    return false
                }
                if (horizontalDistance > touchSlop && horizontalDistance > verticalDistance * SWIPE_AXIS_RATIO) {
                    val itemDelta = if (deltaX < 0f) 1 else -1
                    if (canInterceptPageTurnSwipe?.invoke(itemDelta) == true) {
                        cancelTouchForChildren(ev)
                        pageTurnSwipeCandidate = false
                        pageTurnSwipeConsumed = true
                        pageTurnSwipeDelta = itemDelta
                        return true
                    }
                    pageTurnSwipeCandidate = false
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pageTurnSwipeConsumed) {
                    if (!pageTurnSwipeCanceled && shouldCommitPageTurnSwipe(ev)) {
                        val originX = (pageTurnSwipeDownX / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val originY = (pageTurnSwipeDownY / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        pageTurnSwipeListener?.invoke(pageTurnSwipeDelta, originX, originY)
                    }
                    resetPageTurnSwipe()
                    return true
                }
                resetPageTurnSwipe()
            }
            MotionEvent.ACTION_CANCEL -> {
                val consumed = pageTurnSwipeConsumed
                resetPageTurnSwipe()
                return consumed
            }
        }
        return false
    }

    private fun shouldCommitPageTurnSwipe(ev: MotionEvent): Boolean {
        val displacement = ev.x - pageTurnSwipeDownX
        val directionMatches = if (pageTurnSwipeDelta > 0) displacement < 0f else displacement > 0f
        if (!directionMatches) return false
        val distance = abs(displacement)
        val distanceThreshold = maxOf(touchSlop * 2f, width * SWIPE_COMMIT_FRACTION)
        if (distance >= distanceThreshold) return true

        pageTurnSwipeVelocityTracker?.computeCurrentVelocity(1_000)
        val velocity = pageTurnSwipeVelocityTracker?.xVelocity ?: 0f
        val velocityMatches = if (pageTurnSwipeDelta > 0) velocity < 0f else velocity > 0f
        return distance >= minimumFlingDistance && velocityMatches && abs(velocity) >= minimumFlingVelocity
    }

    private fun cancelTouchForChildren(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        try {
            super.dispatchTouchEvent(cancel)
        } catch (_: NullPointerException) {
        } catch (_: IndexOutOfBoundsException) {
        } catch (_: IllegalArgumentException) {
        }
        if (isGestureDetectorEnabled) {
            gestureDetector.onTouchEvent(cancel)
        }
        cancel.recycle()
    }

    private fun resetPageTurnSwipe() {
        pageTurnSwipeCandidate = false
        pageTurnSwipeConsumed = false
        pageTurnSwipeCanceled = false
        pageTurnSwipeDelta = 0
        pageTurnSwipeVelocityTracker?.recycle()
        pageTurnSwipeVelocityTracker = null
    }

    private fun handleQueuedPageFlipTap(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                queuedTapEligible = true
                queuedTapDownX = ev.x
                queuedTapDownY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - queuedTapDownX) > touchSlop || abs(ev.y - queuedTapDownY) > touchSlop) {
                    queuedTapEligible = false
                }
            }
            MotionEvent.ACTION_UP -> {
                if (queuedTapEligible) {
                    tapListener?.invoke(ev)
                }
                queuedTapEligible = false
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> queuedTapEligible = false
        }
    }

    /**
     * Whether the given [ev] should be intercepted. Only used to prevent crashes when child
     * views manipulate [requestDisallowInterceptTouchEvent].
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onInterceptTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Handles a touch event. Only used to prevent crashes when child views manipulate
     * [requestDisallowInterceptTouchEvent].
     */
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onTouchEvent(ev)
        } catch (e: NullPointerException) {
            false
        } catch (e: IndexOutOfBoundsException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Executes the given key event when this pager has focus. Just do nothing because the reader
     * already dispatches key events to the viewer and has more control than this method.
     */
    override fun executeKeyEvent(event: KeyEvent): Boolean {
        // Disable viewpager's default key event handling
        return false
    }

    /**
     * Enables or disables the gesture detector.
     */
    fun setGestureDetectorEnabled(enabled: Boolean) {
        isGestureDetectorEnabled = enabled
    }

    fun setTouchNavigationEnabled(enabled: Boolean) {
        isTouchNavigationEnabled = enabled
        if (enabled) {
            queuedTapEligible = false
            resetPageTurnSwipe()
        }
    }

    private companion object {
        const val SWIPE_AXIS_RATIO = 1.25f
        const val SWIPE_COMMIT_FRACTION = 0.18f
        const val MINIMUM_FLING_DISTANCE_DP = 25f
        const val MINIMUM_FLING_VELOCITY_DP = 400f
    }
}
