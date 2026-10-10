package koharia.diagnostics

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonRecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContinuousSwipeReproTest {
    @Test
    fun comicPagerSwipeSwitchKeepsProgrammaticNavigation() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var pager: eu.kanade.tachiyomi.ui.reader.viewer.pager.Pager
            scenario.onActivity { activity ->
                pager = eu.kanade.tachiyomi.ui.reader.viewer.pager.Pager(activity)
                pager.swipePageTurnsEnabled = { false }
                pager.adapter = object : androidx.viewpager.widget.PagerAdapter() {
                    override fun getCount() = 3
                    override fun isViewFromObject(view: View, item: Any) = view === item
                    override fun instantiateItem(container: ViewGroup, position: Int): Any =
                        View(activity).also { container.addView(it) }
                    override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
                        container.removeView(item as View)
                    }
                }
                activity.setContentView(pager)
                pager.setCurrentItem(1, false)
            }
            SystemClock.sleep(200)
            scenario.onActivity {
                val now = SystemClock.uptimeMillis()
                for (step in 0..20) {
                    val event = MotionEvent.obtain(
                        now,
                        now + step * 20L,
                        when (step) {
                            0 -> MotionEvent.ACTION_DOWN
                            20 -> MotionEvent.ACTION_UP
                            else -> MotionEvent.ACTION_MOVE
                        },
                        pager.width * (0.8f - step * 0.03f),
                        pager.height / 2f,
                        0,
                    )
                    pager.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            SystemClock.sleep(300)
            scenario.onActivity {
                assertEquals(1, pager.currentItem)
                pager.setCurrentItem(2, false)
                assertEquals(2, pager.currentItem)
            }
        }
    }

    @Test
    fun disabledPageSwipeCancelsDragButKeepsTaps() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var gate: koharia.epub.SwipePageTurnGate
            val actions = mutableListOf<Int>()
            scenario.onActivity { activity ->
                gate = koharia.epub.SwipePageTurnGate(
                    activity,
                    onPageTurnSwipe = { _, _ -> error("Disabled swipes must not turn pages") },
                    interceptPageTurns = { false },
                ) { true }
                gate.addView(
                    object : View(activity) {
                        override fun onTouchEvent(event: MotionEvent): Boolean {
                            actions += event.actionMasked
                            return true
                        }
                    },
                    ViewGroup.LayoutParams(-1, -1),
                )
                activity.setContentView(gate)
            }
            SystemClock.sleep(200)
            scenario.onActivity {
                val now = SystemClock.uptimeMillis()
                fun send(action: Int, x: Float, step: Int) {
                    val event = MotionEvent.obtain(now, now + step * 20L, action, x, 100f, 0)
                    gate.dispatchTouchEvent(event)
                    event.recycle()
                }
                send(MotionEvent.ACTION_DOWN, 100f, 0)
                send(MotionEvent.ACTION_MOVE, 500f, 1)
                send(MotionEvent.ACTION_UP, 600f, 2)
                assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), actions)
                actions.clear()
                send(MotionEvent.ACTION_DOWN, 100f, 3)
                send(MotionEvent.ACTION_UP, 100f, 4)
                assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), actions)
            }
        }
    }

    @Test
    fun comicWebtoonDoesNotTurnChaptersOnHorizontalDrag() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var recycler: WebtoonRecyclerView
            lateinit var layout: LinearLayoutManager
            scenario.onActivity { activity ->
                recycler = WebtoonRecyclerView(activity)
                layout = LinearLayoutManager(activity)
                recycler.layoutManager = layout
                recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount() = 30
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                        object : RecyclerView.ViewHolder(
                            View(activity).apply {
                                layoutParams = RecyclerView.LayoutParams(-1, 900)
                            },
                        ) {}
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
                }
                activity.setContentView(recycler)
                layout.scrollToPositionWithOffset(10, 0)
            }
            SystemClock.sleep(400)
            scenario.onActivity {
                val now = SystemClock.uptimeMillis()
                for (step in 0..20) {
                    val event = MotionEvent.obtain(
                        now,
                        now + step * 20L,
                        when (step) {
                            0 -> MotionEvent.ACTION_DOWN
                            20 -> MotionEvent.ACTION_UP
                            else -> MotionEvent.ACTION_MOVE
                        },
                        recycler.width * (0.85f - step * 0.035f),
                        recycler.height / 2f,
                        0,
                    )
                    recycler.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            SystemClock.sleep(400)
            scenario.onActivity { assertEquals(10, layout.findFirstVisibleItemPosition()) }
        }
    }

    @Test
    fun readiumScrollModeAcceptsAccidentalDiagonalSwipeUnlessExplicitlyDisabled() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var webView: WebView
            lateinit var baseType: Class<*>
            val jumps = java.util.concurrent.atomic.AtomicInteger()
            scenario.onActivity { activity ->
                val type = Class.forName("org.readium.r2.navigator.R2WebView")
                baseType = Class.forName("org.readium.r2.navigator.R2BasicWebView")
                activity.resources.getLayout(android.R.layout.simple_list_item_1).use { parser ->
                    while (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) parser.next()
                    webView = type.getDeclaredConstructor(Context::class.java, android.util.AttributeSet::class.java)
                        .newInstance(activity, android.util.Xml.asAttributeSet(parser)) as WebView
                }
                val listenerType = Class.forName("org.readium.r2.navigator.R2BasicWebView${'$'}Listener")
                val listener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerType.classLoader,
                    arrayOf(listenerType),
                ) {
                        _,
                        method,
                        _,
                    ->
                    when (method.name) {
                        "getReadingProgression" -> method.returnType.enumConstants.first {
                            (it as Enum<*>).name == "LTR"
                        }
                        "goToNextResource", "goToPreviousResource" -> {
                            jumps.incrementAndGet()
                            true
                        }
                        else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
                    }
                }
                baseType.getMethod("setListener", listenerType).invoke(webView, listener)
                baseType.getMethod(
                    "setScrollMode",
                    Boolean::class.javaPrimitiveType,
                ).invoke(webView, koharia.epub.epubNavigatorConfiguration().disablePageTurnsWhileScrolling)
                activity.setContentView(webView)
                webView.loadDataWithBaseURL(
                    "https://example.invalid/",
                    "<meta name='viewport' content='width=device-width'><body style='margin:0;height:16000px'>Long chapter</body>",
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            SystemClock.sleep(800)
            fun drag() {
                scenario.onActivity {
                    val now = SystemClock.uptimeMillis()
                    for (step in 0..20) {
                        val event = MotionEvent.obtain(
                            now,
                            now + step * 20L,
                            when (step) {
                                0 -> MotionEvent.ACTION_DOWN
                                20 -> MotionEvent.ACTION_UP
                                else -> MotionEvent.ACTION_MOVE
                            },
                            webView.width * (0.6f - step * 0.006f),
                            webView.height / 2f + step * 7f,
                            0,
                        )
                        webView.onTouchEvent(event)
                        event.recycle()
                    }
                }
                SystemClock.sleep(500)
            }
            drag()
            assertTrue("Dependency default permits the accidental jump", jumps.get() > 0)
            val before = jumps.get()
            scenario.onActivity {
                baseType.getMethod("setDisablePageTurnsWhileScrolling", Boolean::class.javaPrimitiveType)
                    .invoke(webView, koharia.epub.epubNavigatorConfiguration().disablePageTurnsWhileScrolling)
            }
            drag()
            assertEquals("Readium's existing switch prevents the same accidental jump", before, jumps.get())
            scenario.onActivity { webView.destroy() }
        }
    }
}
