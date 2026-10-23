package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.viewpager.widget.PagerAdapter
import koharia.epub.EpubTransitionFixtureActivity
import koharia.testing.FixtureActivityLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PagerGestureOwnershipDeviceTest {
    @Test
    fun zoomDisabledDispatchesTapOnUpWithoutWaitingForConfirmation() = withPager { f ->
        f.pager.doubleTapZoomEnabled = { false }
        f.send(MotionEvent.ACTION_DOWN, 0, 0.5f)
        f.send(MotionEvent.ACTION_UP, 50, 0.5f)
        assertEquals(1, f.taps)
        f.send(MotionEvent.ACTION_DOWN, 0, 0.5f)
        f.send(MotionEvent.ACTION_UP, 50, 0.5f)
        assertEquals(2, f.taps)
    }

    @Test
    fun zoomEnabledStillWaitsForDoubleTapConfirmation() = withPager { f ->
        f.send(MotionEvent.ACTION_DOWN, 0, 0.5f)
        f.send(MotionEvent.ACTION_UP, 50, 0.5f)
        assertEquals(0, f.taps)
    }

    @Test
    fun unlockMidSwipeKeepsWholeGestureQueuedAndNextGestureWorksNormally() = withPager { f ->
        f.pager.setTouchNavigationEnabled(false)
        f.send(MotionEvent.ACTION_DOWN, 0, 0.8f)
        f.pager.setTouchNavigationEnabled(true)
        f.send(MotionEvent.ACTION_MOVE, 60, 0.4f)
        f.send(MotionEvent.ACTION_UP, 120, 0.2f)
        assertEquals(listOf(1), f.turns)
        assertTrue(f.pager.nativeActions.isEmpty())
        f.swipe()
        assertEquals(listOf(1, 1), f.turns)
        assertTrue(MotionEvent.ACTION_DOWN in f.pager.nativeActions)
        assertEquals(0, f.taps)
    }

    @Test
    fun swipesAndTapsDuringAnimationAreDeliveredExactlyOnce() = withPager { f ->
        f.pager.setTouchNavigationEnabled(false)
        f.swipe()
        f.send(MotionEvent.ACTION_DOWN, 0, 0.2f)
        f.send(MotionEvent.ACTION_MOVE, 60, 0.6f)
        f.send(MotionEvent.ACTION_UP, 120, 0.8f)
        f.send(MotionEvent.ACTION_DOWN, 0, 0.5f)
        f.send(MotionEvent.ACTION_UP, 50, 0.5f)
        assertEquals(listOf(1, -1), f.turns)
        assertEquals(1, f.taps)
        assertTrue(f.pager.nativeActions.isEmpty())
    }

    @Test
    fun lockingDuringNormalGestureCancelsChildrenAndDiscardsItsTail() = withPager { f ->
        f.send(MotionEvent.ACTION_DOWN, 0, 0.8f)
        f.pager.setTouchNavigationEnabled(false)
        f.pager.setTouchNavigationEnabled(true)
        f.send(MotionEvent.ACTION_MOVE, 60, 0.4f)
        f.send(MotionEvent.ACTION_UP, 120, 0.2f)
        assertTrue(MotionEvent.ACTION_CANCEL in f.pager.nativeActions)
        assertTrue(MotionEvent.ACTION_MOVE !in f.pager.nativeActions)
        assertTrue(f.turns.isEmpty())
        assertEquals(0, f.taps)
        f.swipe()
        assertEquals(listOf(1), f.turns)
    }

    @Test
    fun cancellationCrossAxisAndLongHoldNeverBecomeQueuedTurnsOrTaps() = withPager { f ->
        f.pager.setTouchNavigationEnabled(false)
        f.send(MotionEvent.ACTION_DOWN, 0, 0.8f)
        f.send(MotionEvent.ACTION_MOVE, 60, 0.4f)
        f.send(MotionEvent.ACTION_CANCEL, 120, 0.2f)
        f.send(MotionEvent.ACTION_DOWN, 0, 0.5f)
        f.send(MotionEvent.ACTION_MOVE, 60, 0.5f, 0.8f)
        f.send(MotionEvent.ACTION_UP, 120, 0.5f, 0.9f)
        f.send(MotionEvent.ACTION_DOWN, 0, 0.5f)
        f.send(MotionEvent.ACTION_UP, 1500, 0.5f)
        assertTrue(f.turns.isEmpty())
        assertEquals(0, f.taps)
        assertTrue(f.pager.nativeActions.isEmpty())
    }

    @Test
    fun swipePreferenceDisablesQueuedSwipesButStillAllowsTaps() = withPager { f ->
        f.pager.swipePageTurnsEnabled = { false }
        f.pager.setTouchNavigationEnabled(false)
        f.swipe()
        f.send(MotionEvent.ACTION_DOWN, 0, 0.5f)
        f.send(MotionEvent.ACTION_UP, 50, 0.5f)
        assertTrue(f.turns.isEmpty())
        assertEquals(1, f.taps)
    }

    private fun withPager(test: (Fixture) -> Unit) {
        FixtureActivityLauncher.launch(EpubTransitionFixtureActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { activity ->
                assertEquals("app.koharia.dev.devicefixture", activity.packageName)
                fixture = Fixture(ObservedPager(activity))
                activity.setContentView(fixture.pager)
            }
            SystemClock.sleep(200)
            scenario.onActivity {
                assertTrue(fixture.pager.width > 0)
                test(fixture)
            }
        }
    }

    private class Fixture(val pager: ObservedPager) {
        val turns = mutableListOf<Int>()
        var taps = 0
        private var down = 0L

        init {
            pager.adapter = object : PagerAdapter() {
                override fun getCount() = 4
                override fun isViewFromObject(view: View, obj: Any) = view === obj
                override fun instantiateItem(container: ViewGroup, position: Int): Any =
                    View(container.context).apply { container.addView(this) }
                override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
                    container.removeView(obj as View)
                }
            }
            pager.canInterceptPageTurnSwipe = { true }
            pager.pageTurnSwipeListener = { delta, _, _ -> turns += delta }
            pager.tapListener = { taps++ }
        }

        fun swipe() {
            send(MotionEvent.ACTION_DOWN, 0, 0.8f)
            send(MotionEvent.ACTION_MOVE, 60, 0.4f)
            send(MotionEvent.ACTION_UP, 120, 0.2f)
        }

        fun send(action: Int, elapsed: Long, x: Float, y: Float = 0.5f) {
            if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(down, down + elapsed, action, pager.width * x, pager.height * y, 0)
            try {
                pager.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private class ObservedPager(context: Context) : Pager(context) {
        val nativeActions = mutableListOf<Int>()
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            nativeActions += ev.actionMasked
            return super.onInterceptTouchEvent(ev)
        }
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            nativeActions += ev.actionMasked
            return super.onTouchEvent(ev)
        }
    }
}
