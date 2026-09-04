package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.viewpager.widget.PagerAdapter
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PagerAccessibilityDeviceTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(EInkMotionFixtureActivity::class.java)

    @Test
    fun accessibilityScrollMarksTargetAsTrustedNavigation() {
        activityRule.scenario.onActivity { activity ->
            val pager = Pager(activity).apply {
                adapter = object : PagerAdapter() {
                    override fun getCount(): Int = 2

                    override fun isViewFromObject(view: View, objectValue: Any): Boolean = view === objectValue

                    override fun instantiateItem(container: ViewGroup, position: Int): Any {
                        return View(container.context).also(container::addView)
                    }

                    override fun destroyItem(container: ViewGroup, position: Int, objectValue: Any) {
                        container.removeView(objectValue as View)
                    }
                }
            }
            var trustedTarget: Int? = null
            pager.accessibilityPageChangeListener = { trustedTarget = it }
            activity.setContentView(pager)

            pager.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)

            assertEquals(1, trustedTarget)
        }
    }
}
