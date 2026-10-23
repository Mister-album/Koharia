package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.viewpager.widget.PagerAdapter
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnCause
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnOrigin
import koharia.epub.EpubTransitionFixtureActivity
import koharia.testing.FixtureActivityLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComicPageFlipGeometryDeviceTest {
    @Test
    fun differentWidthFitPageHeightsKeepWindowScaleInBothDirections() {
        FixtureActivityLauncher.launch(EpubTransitionFixtureActivity::class.java).use { scenario ->
            lateinit var pager: Pager
            lateinit var controller: ComicPageFlipController
            scenario.onActivity { activity ->
                assertEquals("app.koharia.dev.devicefixture", activity.packageName)
                val root = FrameLayout(activity)
                pager = Pager(activity)
                root.addView(pager, FrameLayout.LayoutParams(-1, -1))
                activity.setContentView(root)
                pager.adapter = object : PagerAdapter() {
                    override fun getCount() = 2
                    override fun isViewFromObject(view: View, obj: Any) = view === obj
                    override fun instantiateItem(container: ViewGroup, position: Int): Any =
                        object : View(activity) {
                            override fun onDraw(canvas: Canvas) {
                                canvas.drawColor(Color.BLACK)
                                val bounds = pageBounds(position, width, height)
                                canvas.drawRect(bounds, Paint().apply { color = Color.WHITE })
                                // A fixed-size stripe makes a stretched destination detectable.
                                canvas.drawRect(
                                    0f,
                                    bounds.top + 20f,
                                    width.toFloat(),
                                    bounds.top + 40f,
                                    Paint().apply { color = Color.RED },
                                )
                            }
                        }.also { container.addView(it, ViewGroup.LayoutParams(-1, -1)) }
                    override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
                        container.removeView(obj as View)
                    }
                }
                controller = ComicPageFlipController(
                    root,
                    pager,
                    { true },
                    { pager.setCurrentItem(it, false) },
                ) { item ->
                    val location = IntArray(2)
                    pager.getLocationInWindow(location)
                    pageBounds(item, pager.width, pager.height).apply { offset(location[0], location[1]) }
                }
            }
            SystemClock.sleep(600)
            for (target in listOf(1, 0)) {
                var checked = false
                scenario.onActivity { assertTrue(controller.start(target, PageTurnOrigin.center(PageTurnCause.TAP))) }
                val deadline = SystemClock.uptimeMillis() + 6000
                while (SystemClock.uptimeMillis() < deadline) {
                    var running = false
                    scenario.onActivity {
                        running = controller.isRunning
                        val session = field(controller, "session") ?: return@onActivity
                        // The static fixture has no tile updates to drive another compositor frame.
                        (field(session, "blocker") as? PopupWindow)?.contentView?.invalidate()
                        val destination = field(session, "destination") as? Bitmap ?: return@onActivity
                        if (checked) return@onActivity
                        val source = field(session, "sourcePage") as Bitmap
                        assertEquals("Texture widths differ", source.width, destination.width)
                        assertEquals("Destination would stretch to source height", source.height, destination.height)
                        val crop = field(session, "pageBounds") as Rect
                        val location = IntArray(2)
                        pager.getLocationInWindow(location)
                        val targetBounds = pageBounds(target, pager.width, pager.height)
                        for (fraction in listOf(0.1f, 0.5f, 0.9f)) {
                            val y = (fraction * destination.height).toInt()
                            val windowY = crop.top + y * crop.height() / destination.height
                            val pageY = windowY - location[1]
                            val expected = when {
                                pageY !in targetBounds.top until targetBounds.bottom -> Color.BLACK
                                pageY in targetBounds.top + 20 until targetBounds.top + 40 -> Color.RED
                                else -> Color.WHITE
                            }
                            assertEquals(
                                "Destination pixels changed window position",
                                expected,
                                destination.getPixel(destination.width / 2, y),
                            )
                        }
                        checked = true
                    }
                    if (!running) break
                    SystemClock.sleep(10)
                }
                assertTrue("No animated destination was captured", checked)
                scenario.onActivity {
                    assertTrue("Animation did not finish", !controller.isRunning)
                    assertEquals(target, pager.currentItem)
                }
            }
            scenario.onActivity { controller.cancel() }
        }
    }

    private fun pageBounds(item: Int, width: Int, height: Int): Rect =
        if (item == 0) Rect(0, 0, width, height) else Rect(0, height / 4, width, height * 3 / 4)

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
}
