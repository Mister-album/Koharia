package koharia.epub

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.ui.reader.transition.PageCoverShadowDrawable
import eu.kanade.tachiyomi.ui.reader.transition.PageCurlRenderer
import eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnCause
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnOrigin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class EpubPageTransitionController(
    private val fragment: Fragment,
    private val root: FrameLayout,
    private val content: View,
    private val overlay: EpubPageTransitionOverlayView,
    private val effectProvider: () -> PageTransitionEffect,
    private val rightToLeftProvider: () -> Boolean,
    private val currentLocationProvider: () -> Pair<String, Int>,
    private val navigate: (forward: Boolean, animated: Boolean) -> Boolean,
) {
    private data class ActiveTurn(
        val generation: Long,
        val forward: Boolean,
        val effect: PageTransitionEffect,
        val origin: PageTurnOrigin,
        val oldHref: String,
        val oldPageIndex: Int,
        var bitmap: Bitmap? = null,
        var pageChanged: Boolean = false,
        var resourceLoaded: Boolean = false,
        var crossedResource: Boolean = false,
        var navigationSubmitted: Boolean = false,
        var visualTimedOut: Boolean = false,
    )

    private data class PendingTurn(
        val forward: Boolean,
        val origin: PageTurnOrigin,
    )

    private var generation = 0L
    private var active: ActiveTurn? = null
    private var animator: ValueAnimator? = null
    private var timeoutJob: Job? = null
    private val pendingTurns = ArrayDeque<PendingTurn>(MAX_PENDING_TURNS)
    private var skipPendingAnimations = false
    private val coverShadow = PageCoverShadowDrawable()
    private var coverShadowAttached = false

    fun turnPage(
        forward: Boolean,
        currentHref: String,
        currentPageIndex: Int,
        origin: PageTurnOrigin = PageTurnOrigin.center(PageTurnCause.PROGRAMMATIC),
    ): Boolean {
        val effect = effectProvider()
        if (effect == PageTransitionEffect.NONE || !ValueAnimator.areAnimatorsEnabled()) {
            return navigate(forward, false)
        }
        if (active != null) {
            if (pendingTurns.size < MAX_PENDING_TURNS) {
                pendingTurns.addLast(PendingTurn(forward, origin.normalized()))
                // A second input while a transition is running is a strong signal that the
                // reader is fast-scrolling. Keep the first visual turn, then catch up queued
                // turns without starting another serial animation for each tap.
                skipPendingAnimations = true
            }
            return true
        }
        if (!fragment.isAdded || fragment.view == null || content.width <= 0 || content.height <= 0) {
            return navigate(forward, true)
        }

        val turn = ActiveTurn(++generation, forward, effect, origin.normalized(), currentHref, currentPageIndex)
        active = turn
        captureContent { bitmap ->
            if (active?.generation != turn.generation) {
                bitmap?.recycle()
                return@captureContent
            }
            if (bitmap == null) {
                turn.visualTimedOut = true
                turn.navigationSubmitted = true
                if (!navigate(forward, true)) {
                    finishTurn(turn.generation)
                } else {
                    scheduleNavigationSettleTimeout(turn.generation)
                }
                return@captureContent
            }
            val direction = visualDirection(forward)
            val preparedBitmap = if (effect == PageTransitionEffect.CURL) {
                PageCurlRenderer.prepareBitmapForDirection(
                    bitmap = bitmap,
                    visualDirection = direction,
                    horizontal = true,
                )
            } else {
                bitmap
            }
            val sourceMirrored = preparedBitmap != null && preparedBitmap !== bitmap
            if (sourceMirrored) bitmap.recycle()
            val renderingBitmap = preparedBitmap ?: bitmap
            turn.bitmap = renderingBitmap
            overlay.setPage(renderingBitmap, effect, direction, turn.origin, sourceMirrored)
            overlay.visibility = View.VISIBLE
            overlay.bringToFront()
            turn.navigationSubmitted = true
            if (!navigate(forward, false)) {
                finishTurn(turn.generation)
                return@captureContent
            }
            timeoutJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
                delay(PAGE_READY_TIMEOUT_MS)
                if (active?.generation == turn.generation) {
                    logcat(LogPriority.WARN) { "EPUB page transition timed out; revealing target page" }
                    revealTimedOutTurn(turn.generation)
                }
            }
        }
        return true
    }

    fun onPageChanged(pageIndex: Int, href: String) {
        val turn = active?.takeIf { it.navigationSubmitted } ?: return
        if (pageIndex == turn.oldPageIndex && href.resourceKey() == turn.oldHref.resourceKey()) return
        turn.pageChanged = true
        turn.crossedResource = href.resourceKey() != turn.oldHref.resourceKey()
        if (!turn.crossedResource || turn.resourceLoaded) {
            handlePageReady(turn)
        }
    }

    fun onPageLoaded() {
        val turn = active?.takeIf { it.navigationSubmitted } ?: return
        turn.resourceLoaded = true
        if (turn.pageChanged) {
            handlePageReady(turn)
        }
    }

    fun cancel() {
        generation++
        pendingTurns.clear()
        skipPendingAnimations = false
        timeoutJob?.cancel()
        timeoutJob = null
        animator?.cancel()
        animator = null
        active?.bitmap?.recycle()
        active = null
        resetViews()
    }

    private fun captureContent(onResult: (Bitmap?) -> Unit) {
        val position = IntArray(2)
        content.getLocationInWindow(position)
        val source = Rect(position[0], position[1], position[0] + content.width, position[1] + content.height)
        val sourceBytes = content.width.toLong() * content.height * ARGB_BYTES
        val scale = if (sourceBytes > MAX_SNAPSHOT_BYTES) {
            sqrt(MAX_SNAPSHOT_BYTES.toDouble() / sourceBytes).toFloat()
        } else {
            1f
        }
        val width = (content.width * scale).toInt().coerceAtLeast(1)
        val height = (content.height * scale).toInt().coerceAtLeast(1)
        val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
            .getOrNull()
            ?: return onResult(null)
        runCatching {
            PixelCopy.request(
                fragment.requireActivity().window,
                source,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        onResult(bitmap)
                    } else {
                        bitmap.recycle()
                        onResult(null)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }.onFailure {
            bitmap.recycle()
            onResult(null)
        }
    }

    private fun startAnimation(expectedGeneration: Long) {
        val turn = active?.takeIf { it.generation == expectedGeneration } ?: return
        if (animator != null) return
        timeoutJob?.cancel()
        timeoutJob = null
        val duration = when (turn.effect) {
            PageTransitionEffect.FADE -> 220L
            PageTransitionEffect.CURL -> 380L
            else -> 300L
        }
        val valueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyProgress(turn, it.animatedValue as Float) }
            addListener(
                onEnd = { finishTurn(expectedGeneration) },
                onCancel = { finishTurn(expectedGeneration) },
            )
        }
        animator = valueAnimator
        valueAnimator.start()
    }

    private fun handlePageReady(turn: ActiveTurn) {
        if (turn.visualTimedOut) {
            finishTurn(turn.generation)
            return
        }
        content.doOnPreDraw { startAnimation(turn.generation) }
        content.invalidate()
    }

    private fun revealTimedOutTurn(expectedGeneration: Long) {
        val turn = active?.takeIf { it.generation == expectedGeneration } ?: return
        turn.visualTimedOut = true
        turn.bitmap?.recycle()
        turn.bitmap = null
        resetViews()
        scheduleNavigationSettleTimeout(expectedGeneration)
    }

    private fun scheduleNavigationSettleTimeout(expectedGeneration: Long) {
        timeoutJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            delay(NAVIGATION_SETTLE_TIMEOUT_MS)
            if (active?.generation == expectedGeneration) {
                logcat(LogPriority.WARN) { "EPUB page navigation did not settle; dropping queued turns" }
                pendingTurns.clear()
                finishTurn(expectedGeneration)
            }
        }
    }

    private fun applyProgress(turn: ActiveTurn, progress: Float) {
        val sign = visualDirection(turn.forward)
        val width = root.width.toFloat()
        val height = root.height.toFloat()
        overlay.progress = progress
        when (turn.effect) {
            PageTransitionEffect.SLIDE -> {
                overlay.translationX = -sign * width * progress
                content.translationX = sign * width * (1f - progress)
            }
            PageTransitionEffect.COVER -> {
                overlay.translationZ = 0f
                content.translationZ = 2f
                content.translationX = sign * width * (1f - progress)
                updateCoverShadow(sign, progress)
            }
            PageTransitionEffect.CURL -> Unit
            PageTransitionEffect.VERTICAL -> {
                val verticalDirection = if (turn.forward) 1f else -1f
                overlay.translationY = -verticalDirection * height * progress
                content.translationY = verticalDirection * height * (1f - progress)
            }
            PageTransitionEffect.FADE -> {
                overlay.alpha = 1f - progress
                content.alpha = 1f
            }
            PageTransitionEffect.DEPTH -> {
                overlay.alpha = 1f - progress
                overlay.scaleX = 1f - progress * 0.06f
                overlay.scaleY = 1f - progress * 0.06f
                content.alpha = 1f
                content.scaleX = 1f
                content.scaleY = 1f
            }
            PageTransitionEffect.NONE -> Unit
        }
    }

    private fun finishTurn(expectedGeneration: Long) {
        val turn = active?.takeIf { it.generation == expectedGeneration } ?: return
        animator?.removeAllListeners()
        animator = null
        timeoutJob?.cancel()
        timeoutJob = null
        turn.bitmap?.recycle()
        active = null
        resetViews()
        drainPending()
    }

    private fun drainPending() {
        if (pendingTurns.isEmpty()) return
        if (skipPendingAnimations) {
            val pending = pendingTurns.toList()
            pendingTurns.clear()
            skipPendingAnimations = false
            val postedGeneration = generation
            root.post {
                if (generation != postedGeneration || !fragment.isAdded || fragment.view == null) return@post
                pending.forEach { turn -> navigate(turn.forward, false) }
            }
            return
        }
        val pending = pendingTurns.removeFirstOrNull() ?: return
        val postedGeneration = generation
        root.post {
            if (generation != postedGeneration || active != null || !fragment.isAdded || fragment.view == null) {
                return@post
            }
            val (href, pageIndex) = currentLocationProvider()
            turnPage(pending.forward, href, pageIndex, pending.origin)
        }
    }

    private fun resetViews() {
        clearCoverShadow()
        overlay.clearPage()
        overlay.visibility = View.GONE
        overlay.alpha = 1f
        overlay.translationX = 0f
        overlay.translationY = 0f
        overlay.translationZ = 0f
        overlay.scaleX = 1f
        overlay.scaleY = 1f
        content.alpha = 1f
        content.translationX = 0f
        content.translationY = 0f
        content.translationZ = 0f
        content.scaleX = 1f
        content.scaleY = 1f
    }

    private fun updateCoverShadow(direction: Float, progress: Float) {
        if (content.width <= 0 || content.height <= 0) return
        if (!coverShadowAttached) {
            content.overlay.add(coverShadow)
            coverShadowAttached = true
        }
        val thickness = (content.resources.displayMetrics.density * COVER_SHADOW_WIDTH_DP)
            .roundToInt()
            .coerceAtLeast(1)
        if (direction > 0f) {
            coverShadow.edge = PageCoverShadowDrawable.Edge.LEFT
            coverShadow.setBounds(0, 0, thickness.coerceAtMost(content.width), content.height)
        } else {
            coverShadow.edge = PageCoverShadowDrawable.Edge.RIGHT
            coverShadow.setBounds(
                (content.width - thickness).coerceAtLeast(0),
                0,
                content.width,
                content.height,
            )
        }
        val remainingDistance = abs(1f - progress)
        coverShadow.alpha = (remainingDistance * COVER_SHADOW_FADE_MULTIPLIER)
            .coerceIn(0f, 1f)
            .times(255f)
            .roundToInt()
    }

    private fun clearCoverShadow() {
        if (!coverShadowAttached) return
        content.overlay.remove(coverShadow)
        coverShadowAttached = false
    }

    private fun visualDirection(forward: Boolean): Float {
        val logical = if (forward) 1f else -1f
        return if (rightToLeftProvider()) -logical else logical
    }

    private fun String.resourceKey(): String = substringBefore('#').substringBefore('?').trimStart('/')

    private inline fun ValueAnimator.addListener(
        crossinline onEnd: () -> Unit,
        crossinline onCancel: () -> Unit,
    ) {
        addListener(
            object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()

                override fun onAnimationCancel(animation: android.animation.Animator) = onCancel()
            },
        )
    }

    companion object {
        private const val MAX_PENDING_TURNS = 24
        private const val PAGE_READY_TIMEOUT_MS = 1_200L
        private const val NAVIGATION_SETTLE_TIMEOUT_MS = 3_800L
        private const val ARGB_BYTES = 4L
        private const val MAX_SNAPSHOT_BYTES = 24L * 1024L * 1024L
        private const val COVER_SHADOW_WIDTH_DP = 18f
        private const val COVER_SHADOW_FADE_MULTIPLIER = 4f
    }
}

internal class EpubPageTransitionOverlayView(context: android.content.Context) : View(context) {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val curlRenderer = PageCurlRenderer()
    private val destination = RectF()
    private var bitmap: Bitmap? = null
    private var effect = PageTransitionEffect.NONE
    private var direction = 1f
    private var origin = PageTurnOrigin.center(PageTurnCause.PROGRAMMATIC)
    private var backsideColor = android.graphics.Color.WHITE
    private var sourceMirrored = false

    var progress: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setPage(
        bitmap: Bitmap,
        effect: PageTransitionEffect,
        direction: Float,
        origin: PageTurnOrigin,
        sourceMirrored: Boolean,
    ) {
        this.bitmap = bitmap
        this.effect = effect
        this.direction = direction
        this.origin = origin.normalized()
        backsideColor = PageCurlRenderer.estimateBackgroundColor(bitmap)
        this.sourceMirrored = sourceMirrored
        progress = 0f
    }

    fun clearPage() {
        bitmap = null
        effect = PageTransitionEffect.NONE
        sourceMirrored = false
        progress = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val page = bitmap?.takeUnless { it.isRecycled } ?: return
        destination.set(0f, 0f, width.toFloat(), height.toFloat())
        if (effect != PageTransitionEffect.CURL) {
            canvas.drawBitmap(page, null, destination, bitmapPaint)
            return
        }
        curlRenderer.draw(
            canvas = canvas,
            bitmap = page,
            destination = destination,
            progress = progress,
            visualDirection = direction,
            origin = origin,
            horizontal = true,
            backsideColor = backsideColor,
            sourceMirrored = sourceMirrored,
        )
    }
}
