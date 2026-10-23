package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnCause
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnOrigin
import koharia.epub.EpubTransitionFixtureActivity
import koharia.testing.FixtureActivityLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComicPageFlipQueueDeviceTest {
    @Test
    fun loadingDestinationDoesNotBlockAnimationOrSixQueuedTurns() = withFixture { fixture ->
        fixture.main { assertTrue(fixture.turn(1)) }
        fixture.await { fixture.pager.currentItem == 1 }
        fixture.main { repeat(6) { assertTrue(fixture.turn(2)) } }
        fixture.await { !fixture.controller.isRunning }
        fixture.main {
            assertEquals((1..7).toList(), fixture.selections)
            assertEquals(7, fixture.pager.currentItem)
            assertEquals(0, fixture.rejected)
        }
    }

    @Test
    fun loadingHolderFinishesTurnWithoutAnyReadinessSignal() = withFixture { fixture ->
        fixture.main { assertTrue(fixture.turn(1)) }
        fixture.await { !fixture.controller.isRunning }
        fixture.main {
            assertEquals(listOf(1), fixture.selections)
            assertEquals(1, fixture.pager.currentItem)
        }
    }

    @Test
    fun forwardAndReverseRequestsKeepTheirOrder() = withFixture { fixture ->
        fixture.main { assertTrue(fixture.turn(1)) }
        fixture.await { fixture.pager.currentItem == 1 }
        fixture.main {
            assertTrue(fixture.turn(2))
            assertTrue(fixture.turn(0))
        }
        fixture.await { !fixture.controller.isRunning }
        fixture.main {
            assertEquals(listOf(1, 2, 1), fixture.selections)
            assertEquals(0, fixture.rejected)
        }
    }

    @Test
    fun cancelledTurnNeverReplaysQueuedInput() = withFixture { fixture ->
        fixture.main { assertTrue(fixture.turn(1)) }
        fixture.await { fixture.pager.currentItem == 1 }
        fixture.main {
            assertTrue(fixture.turn(2))
            fixture.controller.cancel()
        }
        SystemClock.sleep(300)
        fixture.main {
            assertFalse(fixture.controller.isRunning)
            assertEquals(listOf(1), fixture.selections)
        }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        FixtureActivityLauncher.launch(EpubTransitionFixtureActivity::class.java).use { scenario ->
            val fixture = Fixture(scenario)
            try {
                fixture.await { fixture.pager.width > 0 }
                test(fixture)
            } finally {
                fixture.main { fixture.controller.cancel() }
            }
        }
    }

    private class Fixture(private val scenario: ActivityScenario<EpubTransitionFixtureActivity>) {
        lateinit var pager: Pager
        lateinit var controller: ComicPageFlipController
        val selections = mutableListOf<Int>()
        var rejected = 0

        init {
            main {
                assertEquals("app.koharia.dev.devicefixture", it.packageName)
                val root = FrameLayout(it)
                pager = Pager(it)
                root.addView(pager, FrameLayout.LayoutParams(-1, -1))
                it.setContentView(root)
                pager.adapter = object : PagerAdapter() {
                    override fun getCount() = 8
                    override fun isViewFromObject(view: View, obj: Any) = view === obj
                    override fun instantiateItem(container: ViewGroup, position: Int): Any =
                        android.widget.TextView(container.context).apply {
                            text = "Loading fixture page $position"
                            gravity = android.view.Gravity.CENTER
                            setBackgroundColor(if (position % 2 == 0) Color.RED else Color.BLUE)
                            container.addView(this, ViewGroup.LayoutParams(-1, -1))
                        }
                    override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
                        container.removeView(obj as View)
                    }
                }
                var pending: Int? = null
                var stable = 0
                pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                    override fun onPageSelected(position: Int) {
                        if (position == stable) return
                        if (!PagerSelectionPolicy.shouldHandle(position, pending, false)) {
                            rejected++
                            pager.setCurrentItem(stable, false)
                        } else {
                            stable = position
                            pending = null
                        }
                    }
                })
                controller = ComicPageFlipController(
                    root,
                    pager,
                    { it in 0..7 },
                    { target ->
                        selections += target
                        pending = target
                        pager.setCurrentItem(target, false)
                    },
                    { null },
                )
            }
        }

        fun turn(target: Int) = controller.start(target, PageTurnOrigin.center(PageTurnCause.KEY))
        fun main(block: (EpubTransitionFixtureActivity) -> Unit) {
            scenario.onActivity(block)
        }
        fun await(done: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8_000
            while (SystemClock.uptimeMillis() < deadline) {
                var complete = false
                main {
                    // The solid-colour fixture needs a draw to submit the popup commit callback.
                    val sessionField = controller.javaClass.getDeclaredField("session").apply { isAccessible = true }
                    sessionField.get(controller)?.let { session ->
                        val blocker = session.javaClass.getDeclaredField("blocker").apply { isAccessible = true }
                        (blocker.get(session) as? PopupWindow)?.contentView?.invalidate()
                    }
                    complete = done()
                }
                if (complete) return
                SystemClock.sleep(20)
            }
            main { assertTrue("Page flip did not reach the expected state", done()) }
        }
    }
}
