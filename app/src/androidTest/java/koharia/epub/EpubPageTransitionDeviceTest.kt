package koharia.epub

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EpubPageTransitionDeviceTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(EpubTransitionFixtureActivity::class.java)

    class PageFragment : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
            FrameLayout(requireContext()).apply { setBackgroundColor(Color.WHITE) }
    }

    @Test
    fun aPreloadedResourceCompletesWithoutWaitingForAnotherLoadEvent() {
        val measured = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val fragment = PageFragment()
        lateinit var root: FrameLayout
        lateinit var content: TextView
        lateinit var overlay: EpubPageTransitionOverlayView
        lateinit var controller: EpubPageTransitionController
        var submittedAt = 0L
        var finishedAt = 0L
        var navigations = 0
        activityRule.scenario.onActivity { activity ->
            val container = FrameLayout(activity).apply { id = View.generateViewId() }
            activity.setContentView(container)
            activity.supportFragmentManager.beginTransaction().add(container.id, fragment).commitNow()
            root = fragment.requireView() as FrameLayout
            content = TextView(activity).apply {
                text = "Before"
                setBackgroundColor(Color.WHITE)
            }
            overlay = EpubPageTransitionOverlayView(activity).apply { visibility = View.GONE }
            root.addView(content, FrameLayout.LayoutParams(-1, -1))
            root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            controller = EpubPageTransitionController(
                fragment,
                root,
                content,
                overlay,
                { PageTransitionEffect.FADE },
                { false },
                { "before.xhtml" to 0 },
            ) { _, animated ->
                assertTrue("Only the overlay animates a programmatic turn", !animated)
                navigations++
                submittedAt = SystemClock.uptimeMillis()
                content.text = "After"
                root.post { controller.onPageChanged(0, "already-loaded.xhtml") }
                true
            }
            root.viewTreeObserver.addOnPreDrawListener {
                if (root.width > 0) measured.countDown()
                if (submittedAt > 0 && overlay.visibility == View.GONE && finishedAt == 0L) {
                    finishedAt = SystemClock.uptimeMillis()
                    finished.countDown()
                }
                true
            }
        }
        assertTrue(measured.await(5, TimeUnit.SECONDS))
        activityRule.scenario.onActivity { controller.turnPage(true, "before.xhtml", 0) }
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertEquals(1, navigations)
        assertTrue("Preloaded page must not wait for the 1200 ms load timeout", finishedAt - submittedAt < 1100)
        activityRule.scenario.onActivity { controller.cancel() }
    }
}
