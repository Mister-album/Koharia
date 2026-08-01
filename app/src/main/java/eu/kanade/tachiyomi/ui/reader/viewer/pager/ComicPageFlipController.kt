package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupWindow
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnOrigin
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.math.sqrt

/** Coordinates stable pager snapshots with the OpenGL page-flip surface. */
internal class ComicPageFlipController(
    private val container: FrameLayout,
    private val pager: Pager,
    private val canAnimateTarget: (Int) -> Boolean,
    private val isTargetReady: (Int) -> Boolean,
    private val contentBoundsInWindow: (Int) -> Rect?,
) {

    private data class Request(
        val delta: Int,
        val origin: PageTurnOrigin,
    )

    private data class Session(
        val generation: Long,
        val sourceItem: Int,
        val targetItem: Int,
        val startedAt: Long,
        var source: Bitmap? = null,
        var sourcePage: Bitmap? = null,
        var pagerBounds: Rect? = null,
        var pageBounds: Rect? = null,
        var blocker: PopupWindow? = null,
        var blockerReady: Boolean = false,
        var destination: Bitmap? = null,
        var flipView: ComicPageFlipView? = null,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var session: Session? = null
    private val pendingRequests = ArrayDeque<Request>(MAX_PENDING_REQUESTS)
    private var pendingDrainScheduled = false

    val isRunning: Boolean
        get() = session != null || pendingDrainScheduled

    fun start(target: Int, origin: PageTurnOrigin): Boolean {
        if (!pager.horizontalPaging) {
            return false
        }
        val delta = target.compareTo(pager.currentItem)
        if (session != null || pendingDrainScheduled) {
            if (delta != 0) {
                enqueue(Request(delta, origin))
            }
            return true
        }
        if (delta == 0 || target !in 0 until (pager.adapter?.count ?: 0) || !canAnimateTarget(target)) {
            return false
        }
        val sourceItem = pager.currentItem
        pager.setTouchNavigationEnabled(false)
        val currentGeneration = ++generation
        val currentSession = Session(
            generation = currentGeneration,
            sourceItem = sourceItem,
            targetItem = target,
            startedAt = SystemClock.elapsedRealtime(),
        )
        session = currentSession
        logcat(LogPriority.DEBUG) {
            "Comic page flip session started generation=$currentGeneration from=$sourceItem to=$target"
        }

        capturePager { source ->
            if (!isActive(currentSession)) {
                source?.recycle()
                return@capturePager
            }
            if (source == null) {
                failSession(currentSession, "copy visible source from window")
                return@capturePager
            }
            val pagerBounds = pagerBoundsInWindow() ?: run {
                source.recycle()
                failSession(currentSession, "resolve pager bounds")
                return@capturePager
            }
            val pageBounds = resolvePageBounds(sourceItem, pagerBounds)
            val sourcePage = cropToWindowBounds(source, pagerBounds, pageBounds) ?: run {
                source.recycle()
                failSession(currentSession, "crop visible source page")
                return@capturePager
            }
            currentSession.source = source
            currentSession.sourcePage = sourcePage
            currentSession.pagerBounds = pagerBounds
            currentSession.pageBounds = pageBounds
            logcat(LogPriority.DEBUG) {
                "Comic page flip source captured generation=$currentGeneration " +
                    "bitmap=${source.width}x${source.height} page=${sourcePage.width}x${sourcePage.height} " +
                    "bounds=$pageBounds ${describeBitmap(sourcePage)}"
            }
            val blockerShown = showSourceBlocker(
                current = currentSession,
                source = source,
                onReady = blockerReady@{
                    if (!isActive(currentSession)) return@blockerReady
                    // The blocker is a separate WindowManager window, so PixelCopy of the
                    // activity sees the destination below it instead of copying the blocker back
                    // into the destination texture.
                    pager.setCurrentItem(target, false)
                    awaitTarget(currentSession, origin)
                },
            )
            if (!blockerShown) {
                failSession(currentSession, "show source protection window")
            }
        }
        return true
    }

    fun cancel() {
        pendingRequests.clear()
        pendingDrainScheduled = false
        generation++
        session?.let { finishSession(it, animatePending = false) }
            ?: pager.setTouchNavigationEnabled(true)
    }

    private fun awaitTarget(current: Session, origin: PageTurnOrigin, attempt: Int = 0) {
        if (!ensureCurrentTarget(current, "wait for destination")) return
        val elapsed = SystemClock.elapsedRealtime() - current.startedAt
        if (isTargetReady(current.targetItem)) {
            // Two choreographer frames let the newly visible SSIV holder submit its tiles. Drawing
            // an attached, visible holder is reliable; drawing the old off-screen holder was the
            // source of the black textures in the previous implementation.
            pager.postOnAnimation {
                pager.postOnAnimation destinationFrame@{
                    if (!ensureCurrentTarget(current, "prepare destination frame")) return@destinationFrame
                    capturePager { destination ->
                        if (!ensureCurrentTarget(current, "capture destination")) {
                            destination?.recycle()
                            return@capturePager
                        }
                        if (destination == null) {
                            failSession(current, "copy visible destination from window")
                        } else {
                            val pagerBounds = current.pagerBounds ?: pagerBoundsInWindow()
                            if (pagerBounds == null) {
                                destination.recycle()
                                failSession(current, "resolve destination pager bounds")
                                return@capturePager
                            }
                            val destinationBounds = resolvePageBounds(current.targetItem, pagerBounds)
                            val destinationPage = cropToWindowBounds(destination, pagerBounds, destinationBounds)
                            if (destinationPage == null) {
                                destination.recycle()
                                failSession(current, "crop visible destination page")
                                return@capturePager
                            }
                            if (destinationPage !== destination) destination.recycle()
                            current.destination = destinationPage
                            logcat(LogPriority.DEBUG) {
                                "Comic page flip destination captured generation=${current.generation} " +
                                    "bitmap=${destinationPage.width}x${destinationPage.height} " +
                                    "bounds=$destinationBounds ${describeBitmap(destinationPage)}"
                            }
                            createFlipSurface(current, origin, destinationPage)
                        }
                    }
                }
            }
            return
        }
        if (elapsed >= TARGET_TIMEOUT_MS) {
            failSession(current, "wait for destination after ${elapsed}ms")
            return
        }
        pager.postDelayed({ awaitTarget(current, origin, attempt + 1) }, TARGET_POLL_MS)
        if (attempt == TARGET_LOG_ATTEMPT) {
            logcat(LogPriority.DEBUG) {
                "Comic page flip is waiting for visible destination generation=${current.generation} " +
                    "target=${current.targetItem}"
            }
        }
    }

    private fun createFlipSurface(current: Session, origin: PageTurnOrigin, destination: Bitmap) {
        if (!ensureCurrentTarget(current, "create flip surface")) return
        val source = current.sourcePage ?: run {
            failSession(current, "resolve source texture")
            return
        }
        val pageBounds = current.pageBounds ?: run {
            failSession(current, "resolve source page bounds")
            return
        }
        val flipView = ComicPageFlipView(
            context = container.context,
            source = source,
            destination = destination,
            forward = current.targetItem > current.sourceItem,
            origin = origin,
            onFirstFrame = firstFrame@{
                if (!ensureCurrentTarget(current, "show first GL frame")) return@firstFrame
                // onDrawFrame is called before EGL swaps the buffer. Waiting for the following UI
                // frame guarantees that the GL surface contains the source page before revealing it.
                pager.postOnAnimation firstSurfaceFrame@{
                    if (!ensureCurrentTarget(current, "commit first GL frame")) return@firstSurfaceFrame
                    val activeFlipView = current.flipView ?: return@firstSurfaceFrame
                    activeFlipView.alpha = 1f
                    // SurfaceView alpha and PopupWindow dismissal are committed through different
                    // surfaces. Let the visible, static GL source frame reach SurfaceFlinger before
                    // dismissing the blocker and starting the curl, so there is no uncovered frame.
                    pager.postOnAnimation surfaceVisibleFrame@{
                        if (!ensureCurrentTarget(current, "start GL animation")) return@surfaceVisibleFrame
                        removeBlocker(current)
                        current.flipView?.startFlip()
                        logcat(LogPriority.DEBUG) {
                            "Comic page flip first GL frame visible generation=${current.generation} " +
                                "prepareMs=${SystemClock.elapsedRealtime() - current.startedAt}"
                        }
                    }
                }
            },
            onFinished = { completed ->
                if (!completed) {
                    logcat(LogPriority.WARN) {
                        "Comic page flip used safe destination fallback generation=${current.generation}"
                    }
                }
                hideFlipSurfaceThenFinish(current, animatePending = true)
            },
        )
        current.flipView = flipView
        val containerLocation = IntArray(2)
        container.getLocationInWindow(containerLocation)
        container.addView(
            flipView,
            FrameLayout.LayoutParams(pageBounds.width(), pageBounds.height()).apply {
                leftMargin = pageBounds.left - containerLocation[0]
                topMargin = pageBounds.top - containerLocation[1]
            },
        )
    }

    private fun hideFlipSurfaceThenFinish(current: Session, animatePending: Boolean) {
        if (!isActive(current)) return
        val flipView = current.flipView ?: run {
            finishSession(current, animatePending)
            return
        }
        // GLSurfaceView.onPause() disconnects its BufferQueue before the SurfaceView hole is
        // removed from the parent. On some devices that hole is composed as black for one frame.
        // First make the SurfaceControl transparent and let the already-visible destination pager
        // take over, then stop GL and detach the view after the alpha transaction has settled.
        flipView.alpha = 0f
        logcat(LogPriority.DEBUG) {
            "Comic page flip GL surface hidden before cleanup generation=${current.generation}"
        }
        pager.postOnAnimation firstHiddenFrame@{
            if (!isActive(current)) return@firstHiddenFrame
            pager.postOnAnimation secondHiddenFrame@{
                if (!isActive(current)) return@secondHiddenFrame
                logcat(LogPriority.DEBUG) {
                    "Comic page flip GL surface safe to detach generation=${current.generation}"
                }
                finishSession(current, animatePending)
            }
        }
    }

    private fun capturePager(onResult: (Bitmap?) -> Unit) {
        val activity = container.context as? Activity
        if (activity == null || pager.width <= 0 || pager.height <= 0 || !pager.isAttachedToWindow) {
            onResult(null)
            return
        }
        val sourceRect = pagerBoundsInWindow() ?: run {
            onResult(null)
            return
        }
        val sourceBytes = sourceRect.width().toLong() * sourceRect.height().toLong() * ARGB_BYTES
        val scale = if (sourceBytes > MAX_SNAPSHOT_BYTES) {
            sqrt(MAX_SNAPSHOT_BYTES.toDouble() / sourceBytes).toFloat()
        } else {
            1f
        }
        val bitmap = runCatching {
            Bitmap.createBitmap(
                (sourceRect.width() * scale).toInt().coerceAtLeast(1),
                (sourceRect.height() * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        }.getOrNull()
        if (bitmap == null) {
            onResult(null)
            return
        }
        PixelCopy.request(
            activity.window,
            sourceRect,
            bitmap,
            { result ->
                if (result == PixelCopy.SUCCESS) {
                    onResult(bitmap)
                } else {
                    logcat(LogPriority.ERROR) {
                        "Comic page flip PixelCopy failed result=$result rect=$sourceRect " +
                            "bitmap=${bitmap.width}x${bitmap.height}"
                    }
                    bitmap.recycle()
                    onResult(null)
                }
            },
            mainHandler,
        )
    }

    private fun showSourceBlocker(
        current: Session,
        source: Bitmap,
        onReady: () -> Unit,
    ): Boolean {
        val activity = container.context as? Activity ?: return false
        val location = IntArray(2)
        pager.getLocationOnScreen(location)
        val blockerView = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(source)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return runCatching {
            PopupWindow(blockerView, pager.width, pager.height, false).apply {
                animationStyle = 0
                elevation = 0f
                isClippingEnabled = false
                isTouchable = false
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                showAtLocation(activity.window.decorView, Gravity.TOP or Gravity.START, location[0], location[1])
                current.blocker = this
            }
            val shown = current.blocker?.isShowing == true
            if (shown) {
                awaitSourceBlockerCommit(current, blockerView, onReady)
            }
            shown
        }.getOrElse {
            logcat(LogPriority.ERROR, it) { "Comic page flip failed to show source protection window" }
            false
        }
    }

    private fun awaitSourceBlockerCommit(
        current: Session,
        blockerView: View,
        onReady: () -> Unit,
    ) {
        var commitRequested = false
        lateinit var drawListener: ViewTreeObserver.OnDrawListener
        drawListener = ViewTreeObserver.OnDrawListener {
            if (commitRequested) return@OnDrawListener
            commitRequested = true
            blockerView.post {
                val observer = blockerView.viewTreeObserver
                if (observer.isAlive) {
                    observer.removeOnDrawListener(drawListener)
                }
            }

            val afterFrameCommit = Runnable {
                // The popup content has reached its buffer, but its separate WindowManager surface
                // can become visible one compositor transaction later. Keep the pager unchanged
                // until that transaction has had enough time to settle.
                blockerView.postOnAnimationDelayed(
                    blockerVisible@{
                        if (!isActive(current) || current.blocker?.isShowing != true) {
                            return@blockerVisible
                        }
                        current.blockerReady = true
                        logcat(LogPriority.DEBUG) {
                            "Comic page flip source blocker committed generation=${current.generation} " +
                                "elapsedMs=${SystemClock.elapsedRealtime() - current.startedAt}"
                        }
                        onReady()
                    },
                    BLOCKER_COMPOSITOR_SETTLE_MS,
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && blockerView.isHardwareAccelerated) {
                blockerView.viewTreeObserver.registerFrameCommitCallback(afterFrameCommit)
            } else {
                blockerView.postOnAnimation(afterFrameCommit)
            }
        }
        blockerView.viewTreeObserver.addOnDrawListener(drawListener)
        blockerView.invalidate()

        mainHandler.postDelayed(
            {
                if (isActive(current) && !current.blockerReady) {
                    failSession(current, "wait for source protection window commit")
                }
            },
            BLOCKER_READY_TIMEOUT_MS,
        )
    }

    private fun failSession(current: Session, stage: String) {
        if (!isActive(current)) return
        if (pager.currentItem != current.targetItem) {
            pager.setCurrentItem(current.targetItem, false)
        }
        logcat(LogPriority.ERROR) {
            "Comic page flip failed to $stage generation=${current.generation}; revealing destination"
        }
        finishSession(current, animatePending = true)
    }

    private fun finishSession(current: Session, animatePending: Boolean) {
        if (session !== current) return
        session = null
        current.flipView?.let { flipView ->
            flipView.release()
            container.removeView(flipView)
        }
        removeBlocker(current)
        current.source?.takeUnless(Bitmap::isRecycled)?.recycle()
        current.sourcePage
            ?.takeIf { it !== current.source }
            ?.takeUnless(Bitmap::isRecycled)
            ?.recycle()
        current.destination?.takeUnless(Bitmap::isRecycled)?.recycle()
        logcat(LogPriority.DEBUG) {
            "Comic page flip session finished generation=${current.generation} " +
                "elapsedMs=${SystemClock.elapsedRealtime() - current.startedAt}"
        }
        if (animatePending && pendingRequests.isNotEmpty()) {
            pendingDrainScheduled = true
            pager.post(::drainPendingRequest)
        } else {
            if (!animatePending) {
                pendingRequests.clear()
            }
            pendingDrainScheduled = false
            pager.setTouchNavigationEnabled(true)
        }
    }

    private fun enqueue(request: Request) {
        if (pendingRequests.size >= MAX_PENDING_REQUESTS) {
            logcat(LogPriority.DEBUG) {
                "Comic page flip queue is full direction=${request.delta} depth=${pendingRequests.size}"
            }
            return
        }
        pendingRequests.addLast(request)
        logcat(LogPriority.DEBUG) {
            "Comic page flip queued direction=${request.delta} depth=${pendingRequests.size}"
        }
    }

    private fun drainPendingRequest() {
        if (!pendingDrainScheduled || session != null) return
        pendingDrainScheduled = false
        val request = pendingRequests.removeFirstOrNull()
        if (request == null) {
            pager.setTouchNavigationEnabled(true)
            return
        }
        val target = pager.currentItem + request.delta
        val targetIsValid = target in 0 until (pager.adapter?.count ?: 0)
        if (!targetIsValid || !canAnimateTarget(target)) {
            pendingRequests.clear()
            pager.setTouchNavigationEnabled(true)
            if (targetIsValid) {
                pager.setCurrentItem(target, false)
            }
            return
        }
        if (!start(target, request.origin)) {
            pendingRequests.clear()
            pager.setTouchNavigationEnabled(true)
        }
    }

    private fun removeBlocker(current: Session) {
        current.blocker?.let { blocker ->
            runCatching { blocker.dismiss() }
            (blocker.contentView as? ImageView)?.setImageDrawable(null)
        }
        current.blocker = null
    }

    private fun isActive(current: Session): Boolean = session === current && generation == current.generation

    private fun isCurrentTarget(current: Session): Boolean =
        isActive(current) && pager.currentItem == current.targetItem

    private fun ensureCurrentTarget(current: Session, stage: String): Boolean {
        if (!isActive(current)) return false
        if (isCurrentTarget(current)) return true
        logcat(LogPriority.WARN) {
            "Comic page flip target invalidated while attempting to $stage " +
                "generation=${current.generation} expected=${current.targetItem} actual=${pager.currentItem}"
        }
        finishSession(current, animatePending = false)
        return false
    }

    private fun pagerBoundsInWindow(): Rect? {
        if (pager.width <= 0 || pager.height <= 0 || !pager.isAttachedToWindow) return null
        val location = IntArray(2)
        pager.getLocationInWindow(location)
        return Rect(location[0], location[1], location[0] + pager.width, location[1] + pager.height)
    }

    private fun resolvePageBounds(item: Int, pagerBounds: Rect): Rect {
        val pageBounds = contentBoundsInWindow(item)?.let(::Rect) ?: return Rect(pagerBounds)
        if (!pageBounds.intersect(pagerBounds) || pageBounds.width() < 2 || pageBounds.height() < 2) {
            return Rect(pagerBounds)
        }
        return pageBounds
    }

    private fun cropToWindowBounds(bitmap: Bitmap, bitmapBounds: Rect, cropBounds: Rect): Bitmap? = runCatching {
        val scaleX = bitmap.width.toFloat() / bitmapBounds.width()
        val scaleY = bitmap.height.toFloat() / bitmapBounds.height()
        val left = ((cropBounds.left - bitmapBounds.left) * scaleX).toInt().coerceIn(0, bitmap.width - 1)
        val top = ((cropBounds.top - bitmapBounds.top) * scaleY).toInt().coerceIn(0, bitmap.height - 1)
        val right = ((cropBounds.right - bitmapBounds.left) * scaleX).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ((cropBounds.bottom - bitmapBounds.top) * scaleY).toInt().coerceIn(top + 1, bitmap.height)
        Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }.onFailure {
        logcat(LogPriority.ERROR, it) { "Comic page flip failed to crop page bounds=$cropBounds" }
    }.getOrNull()

    private fun describeBitmap(bitmap: Bitmap): String {
        var sum = 0L
        var minimum = 255
        var maximum = 0
        var samples = 0
        repeat(BITMAP_SAMPLE_GRID) { yIndex ->
            repeat(BITMAP_SAMPLE_GRID) { xIndex ->
                val x = ((xIndex + 0.5f) * bitmap.width / BITMAP_SAMPLE_GRID).toInt().coerceAtMost(bitmap.width - 1)
                val y = ((yIndex + 0.5f) * bitmap.height / BITMAP_SAMPLE_GRID).toInt().coerceAtMost(bitmap.height - 1)
                val color = bitmap.getPixel(x, y)
                val brightness = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
                sum += brightness
                minimum = minOf(minimum, brightness)
                maximum = maxOf(maximum, brightness)
                samples++
            }
        }
        return "brightness(avg=${sum / samples},min=$minimum,max=$maximum)"
    }

    private companion object {
        const val ARGB_BYTES = 4L
        const val MAX_SNAPSHOT_BYTES = 24L * 1024L * 1024L
        const val TARGET_TIMEOUT_MS = 2_000L
        const val TARGET_POLL_MS = 32L
        const val TARGET_LOG_ATTEMPT = 12
        const val BLOCKER_COMPOSITOR_SETTLE_MS = 34L
        const val BLOCKER_READY_TIMEOUT_MS = 1_000L
        const val MAX_PENDING_REQUESTS = 3
        const val BITMAP_SAMPLE_GRID = 9
    }
}
