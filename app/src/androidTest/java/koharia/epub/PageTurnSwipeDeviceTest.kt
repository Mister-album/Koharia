package koharia.epub

import android.os.SystemClock
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnCause
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnOrigin
import eu.kanade.tachiyomi.ui.reader.viewer.pager.Pager
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageTurnPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageTurnSwipeDeviceTest {
    @Test
    fun bookSwipeCancelsNativeTouchAndTurnsOnceWithGestureOrigin() = withGate { gate, actions, turns, _ ->
        drag(gate)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), actions)
        assertEquals(1, turns.size)
        assertEquals(1, turns.single().first)
        assertEquals(PageTurnCause.GESTURE, turns.single().second.cause)
        assertEquals(0.8f, turns.single().second.xFraction, 0.01f)
    }

    @Test
    fun tapsVerticalMotionAndLongPressRemainWithContent() = withGate { gate, actions, turns, _ ->
        drag(gate, distance = 0f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), actions)
        actions.clear()
        drag(gate, horizontal = false)
        assertFalse(actions.contains(MotionEvent.ACTION_CANCEL))
        actions.clear()
        drag(gate, duration = 1_000)
        assertFalse(actions.contains(MotionEvent.ACTION_CANCEL))
        assertTrue(turns.isEmpty())
    }

    @Test
    fun shortCanceledReversedAndMultiTouchDragsDoNotTurnPages() = withGate { gate, _, turns, _ ->
        drag(gate, distance = 0.04f, duration = 400)
        drag(gate, finish = MotionEvent.ACTION_CANCEL)
        drag(gate, reverse = true)
        drag(gate, secondPointer = true)
        assertTrue(turns.isEmpty())
        drag(gate)
        assertEquals(1, turns.size)
    }

    @Test
    fun childGestureOwnershipAndTextSelectionPreventPageTurns() = withGate { gate, actions, turns, child ->
        child.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        drag(gate)
        assertTrue(turns.isEmpty())
        assertFalse(actions.contains(MotionEvent.ACTION_CANCEL))
        child.setOnTouchListener(null)
        val mode = child.startActionMode(
            object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    menu.add("Fixture selection")
                    return true
                }
                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
                override fun onDestroyActionMode(mode: ActionMode) = Unit
            },
            ActionMode.TYPE_FLOATING,
        )
        assertNotNull(mode)
        drag(gate)
        assertTrue(turns.isEmpty())
        mode?.finish()
        drag(gate)
        assertEquals(1, turns.size)
    }

    @Test
    fun horizontalComicNoAnimationDoesNotDragOrSettle() = verifyComic(horizontal = true)

    @Test
    fun verticalComicNoAnimationDoesNotDragOrSettle() = verifyComic(horizontal = false)

    private fun verifyComic(horizontal: Boolean) {
        ActivityScenario.launch(EpubTransitionFixtureActivity::class.java).use { scenario ->
            lateinit var pager: Pager
            val states = mutableListOf<Int>()
            var turns = 0
            scenario.onActivity { activity ->
                assertEquals("app.koharia.dev.devicefixture", activity.packageName)
                pager = Pager(activity, horizontal)
                pager.adapter = object : PagerAdapter() {
                    override fun getCount() = 3
                    override fun isViewFromObject(view: View, obj: Any): Boolean = view === obj
                    override fun instantiateItem(container: ViewGroup, position: Int): Any =
                        View(activity).also { container.addView(it) }
                    override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
                        container.removeView(obj as View)
                    }
                }
                pager.canInterceptPageTurnSwipe = {
                    PagerPageTurnPolicy.shouldInterceptSwipe(PageTransitionEffect.NONE, horizontal, true)
                }
                pager.pageTurnSwipeListener = { delta, _, _ ->
                    turns++
                    pager.setCurrentItem(pager.currentItem + delta, false)
                }
                pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                    override fun onPageScrollStateChanged(state: Int) {
                        states += state
                    }
                })
                activity.setContentView(pager)
                pager.setCurrentItem(1, false)
            }
            SystemClock.sleep(300)
            scenario.onActivity {
                val originalX = pager.scrollX
                val originalY = pager.scrollY
                drag(pager, horizontal = horizontal, beforeRelease = {
                    assertEquals(1, pager.currentItem)
                    assertEquals(originalX, pager.scrollX)
                    assertEquals(originalY, pager.scrollY)
                })
                assertEquals(2, pager.currentItem)
                assertEquals(1, turns)
                assertFalse(states.contains(ViewPager.SCROLL_STATE_DRAGGING))
                assertFalse(states.contains(ViewPager.SCROLL_STATE_SETTLING))
            }
        }
    }

    private fun withGate(
        test: (SwipePageTurnGate, MutableList<Int>, MutableList<Pair<Int, PageTurnOrigin>>, View) -> Unit,
    ) {
        ActivityScenario.launch(EpubTransitionFixtureActivity::class.java).use { scenario ->
            lateinit var gate: SwipePageTurnGate
            lateinit var child: View
            val actions = mutableListOf<Int>()
            val turns = mutableListOf<Pair<Int, PageTurnOrigin>>()
            scenario.onActivity { activity ->
                assertEquals("app.koharia.dev.devicefixture", activity.packageName)
                gate = SwipePageTurnGate(
                    activity,
                    onPageTurnSwipe = { delta, origin -> turns += delta to origin },
                    interceptPageTurns = { true },
                ) { false }
                child = object : View(activity) {
                    override fun onTouchEvent(event: MotionEvent): Boolean {
                        actions += event.actionMasked
                        return true
                    }
                }
                gate.addView(child, ViewGroup.LayoutParams(-1, -1))
                activity.setContentView(gate)
            }
            SystemClock.sleep(300)
            scenario.onActivity { test(gate, actions, turns, child) }
        }
    }

    private fun drag(
        view: View,
        horizontal: Boolean = true,
        distance: Float = 0.6f,
        duration: Long = 240,
        finish: Int = MotionEvent.ACTION_UP,
        reverse: Boolean = false,
        secondPointer: Boolean = false,
        beforeRelease: () -> Unit = {},
    ) {
        val now = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float, time: Long) {
            val x = view.width * if (horizontal) fraction else 0.5f
            val y = view.height * if (horizontal) 0.5f else fraction
            val event = MotionEvent.obtain(now, now + time, action, x, y, 0)
            try {
                view.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 0.8f, 0)
        send(MotionEvent.ACTION_MOVE, 0.8f - distance, duration / 2)
        if (secondPointer) send(MotionEvent.ACTION_POINTER_DOWN, 0.8f - distance, duration / 2 + 1)
        beforeRelease()
        send(finish, if (reverse) 0.85f else 0.8f - distance, duration)
    }
}
