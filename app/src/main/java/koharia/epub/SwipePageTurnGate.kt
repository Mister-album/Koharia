package koharia.epub

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnOrigin
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnSwipeHandler
import kotlin.math.abs

internal class SwipePageTurnGate(
    context: Context,
    private val onVerticalSwipe: (Boolean) -> Boolean = { false },
    onPageTurnSwipe: (Int, PageTurnOrigin) -> Unit,
    interceptPageTurns: () -> Boolean,
    private val blockPageTurns: () -> Boolean,
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var blocked = false
    private var multiTouch = false
    private var selectingText = false
    private var childOwnsGesture = false
    private val pageTurnSwipe = PageTurnSwipeHandler(
        context = context,
        horizontal = true,
        viewportSize = { width to height },
        enabled = interceptPageTurns,
        canIntercept = { !selectingText && !childOwnsGesture },
        cancelChildren = ::cancelChildren,
        onTurn = onPageTurnSwipe,
    )

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            startX = event.x
            startY = event.y
            blocked = false
            multiTouch = false
            childOwnsGesture = false
        }
        if (event.pointerCount > 1) multiTouch = true
        if (!blocked && !multiTouch && blockPageTurns() && event.actionMasked == MotionEvent.ACTION_MOVE &&
            abs(event.x - startX) > slop
        ) {
            cancelChildren(event)
            blocked = true
        }
        if (blocked) return true
        if (pageTurnSwipe.handle(event)) return true
        val handled = super.dispatchTouchEvent(event)
        if (!multiTouch && event.actionMasked == MotionEvent.ACTION_UP &&
            abs(event.y - startY) > slop * 3 && abs(event.y - startY) > abs(event.x - startX) * 2
        ) {
            if (onVerticalSwipe(event.y < startY)) return true
        }
        return handled
    }

    private fun cancelChildren(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        try {
            super.dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        childOwnsGesture = disallowIntercept
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun startActionModeForChild(child: View, callback: ActionMode.Callback, type: Int): ActionMode? {
        return super.startActionModeForChild(
            child,
            object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean =
                    callback.onCreateActionMode(mode, menu).also { selectingText = it }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
                    callback.onPrepareActionMode(mode, menu)

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
                    callback.onActionItemClicked(mode, item)

                override fun onDestroyActionMode(mode: ActionMode) {
                    selectingText = false
                    callback.onDestroyActionMode(mode)
                }

                override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                    (callback as? ActionMode.Callback2)?.onGetContentRect(mode, view, outRect)
                        ?: super.onGetContentRect(mode, view, outRect)
                }
            },
            type,
        )
    }

    override fun onDetachedFromWindow() {
        pageTurnSwipe.reset()
        super.onDetachedFromWindow()
    }
}
