package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.viewpager.widget.DirectionalViewPager
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnSensitivity
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnSwipeHandler
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

    var swipePageTurnsEnabled: () -> Boolean = { true }

    var pageTurnSensitivity: () -> PageTurnSensitivity = { PageTurnSensitivity.NORMAL }

    var pageTurnSwipeUsesNativeDrag: () -> Boolean = { false }

    var doubleTapZoomEnabled: () -> Boolean = { true }

    private var waitForDoubleTap = true

    /** Tap listener function to execute when a tap is detected. */
    var tapListener: ((MotionEvent) -> Unit)? = null

    /**
     * Long tap listener function to execute when a long tap is detected.
     */
    var longTapListener: ((MotionEvent) -> Boolean)? = null

    /** Whether a swipe along the paging axis should be handled as a discrete page turn. */
    var canInterceptPageTurnSwipe: ((Int) -> Boolean)? = null

    /** Called after an intercepted page swipe is released. */
    var pageTurnSwipeListener: ((Int, Float, Float) -> Unit)? = null

    /** Called before the inherited accessibility delegate changes the current page. */
    var accessibilityPageChangeListener: ((Int) -> Unit)? = null

    /**
     * Gesture listener that implements tap and long tap events.
     */
    private val gestureListener = object : GestureDetectorWithLongTap.Listener() {
        override fun onSingleTapUp(ev: MotionEvent): Boolean {
            if (waitForDoubleTap) return false
            tapListener?.invoke(ev)
            return true
        }

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
    private var queuedTapEligible = false
    private var queuedTapDownX = 0f
    private var queuedTapDownY = 0f
    private enum class TouchOwner { PAGE, QUEUE, DISCARD }
    private var touchOwner = TouchOwner.DISCARD
    private var lastTouch: MotionEvent? = null

    // A gesture begun above the animation stays here through UP, even if the animation finishes.
    private val queuedPageTurnSwipe = PageTurnSwipeHandler(
        context = context,
        horizontal = horizontalPaging,
        viewportSize = { width to height },
        enabled = { swipePageTurnsEnabled() && pageTurnSwipeListener != null },
        sensitivity = { pageTurnSensitivity() },
        canIntercept = { true },
        cancelChildren = {},
        onTurn = { delta, origin -> pageTurnSwipeListener?.invoke(delta, origin.xFraction, origin.yFraction) },
    )

    private val pageTurnSwipe = PageTurnSwipeHandler(
        context = context,
        horizontal = horizontalPaging,
        viewportSize = { width to height },
        enabled = { swipePageTurnsEnabled() && pageTurnSwipeListener != null && canInterceptPageTurnSwipe != null },
        sensitivity = { pageTurnSensitivity() },
        dragWithContent = { pageTurnSwipeUsesNativeDrag() },
        canIntercept = { canInterceptPageTurnSwipe?.invoke(it) == true },
        cancelChildren = ::cancelTouchForChildren,
        onTurn = { delta, origin -> pageTurnSwipeListener?.invoke(delta, origin.xFraction, origin.yFraction) },
    )

    /**
     * Dispatches a touch event.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            touchOwner = if (isTouchNavigationEnabled) TouchOwner.PAGE else TouchOwner.QUEUE
            // No double-tap zoom means there is no reason to delay single-tap navigation.
            waitForDoubleTap = doubleTapZoomEnabled()
            gestureDetector.setOnDoubleTapListener(if (waitForDoubleTap) gestureListener else null)
        }
        lastTouch?.recycle()
        val terminal = ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL
        lastTouch = if (terminal) null else MotionEvent.obtain(ev)
        if (eu.kanade.tachiyomi.BuildConfig.DEBUG &&
            android.util.Log.isLoggable("PagerInputTrace", android.util.Log.DEBUG)
        ) {
            android.util.Log.d(
                "PagerInputTrace",
                "event action=${ev.actionMasked} down=${ev.downTime} time=${ev.eventTime} enabled=$isTouchNavigationEnabled item=$currentItem",
            )
        }
        if (touchOwner == TouchOwner.DISCARD) return true
        if (touchOwner == TouchOwner.QUEUE) {
            if (queuedPageTurnSwipe.handle(ev)) queuedTapEligible = false
            handleQueuedPageFlipTap(ev)
            return true
        }
        if (pageTurnSwipe.handle(ev)) return true
        val handled = super.dispatchTouchEvent(ev)
        if (isGestureDetectorEnabled) {
            gestureDetector.onTouchEvent(ev)
        }
        return handled
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
                if (queuedTapEligible && ev.eventTime - ev.downTime < ViewConfiguration.getLongPressTimeout()) {
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
        // PhotoView can release interception before ScaleGestureDetector crosses its span slop.
        // Let the image keep the complete multi-pointer gesture instead of starting a page drag.
        if (!swipePageTurnsEnabled() || ev.pointerCount > 1) return false
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
        if (!swipePageTurnsEnabled()) return false
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

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val target = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> currentItem + 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> currentItem - 1
            else -> null
        }
        if (target != null && target in 0 until (adapter?.count ?: 0)) {
            accessibilityPageChangeListener?.invoke(target)
        }
        return super.performAccessibilityAction(action, arguments)
    }

    /**
     * Enables or disables the gesture detector.
     */
    fun setGestureDetectorEnabled(enabled: Boolean) {
        isGestureDetectorEnabled = enabled
    }

    fun setTouchNavigationEnabled(enabled: Boolean) {
        if (eu.kanade.tachiyomi.BuildConfig.DEBUG &&
            android.util.Log.isLoggable("PagerInputTrace", android.util.Log.DEBUG)
        ) {
            android.util.Log.d("PagerInputTrace", "navigation enabled=$enabled item=$currentItem")
        }
        if (!enabled && isTouchNavigationEnabled && touchOwner == TouchOwner.PAGE && lastTouch != null) {
            // A key/tap can start a flip while a child still owns a different active gesture.
            // Cancel that stream once; never replay its tail as a new gesture when unlocking.
            cancelTouchForChildren(lastTouch!!)
            touchOwner = TouchOwner.DISCARD
            queuedTapEligible = false
            pageTurnSwipe.reset()
        }
        isTouchNavigationEnabled = enabled
    }

    override fun onDetachedFromWindow() {
        pageTurnSwipe.reset()
        queuedPageTurnSwipe.reset()
        lastTouch?.recycle()
        lastTouch = null
        touchOwner = TouchOwner.DISCARD
        queuedTapEligible = false
        super.onDetachedFromWindow()
    }
}
