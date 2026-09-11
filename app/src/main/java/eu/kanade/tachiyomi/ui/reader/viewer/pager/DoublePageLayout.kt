package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView

internal class DoublePageLayout(
    context: Context,
    private val firstPage: DoublePageCompositionPolicy.Image,
    private val secondPage: DoublePageCompositionPolicy.Image,
    private val onSplitFractionChanged: (Float) -> Unit,
    private val onZoom: () -> Unit,
) : ViewGroup(context) {
    private var spreadLayout: DoublePageCompositionPolicy.ViewportLayout? = null
    internal var zoom = 1f
        private set
    private var offsetX = 0f
    private var offsetY = 0f
    private val bounds = arrayOf(RectF(), RectF())
    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    zoomAt(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                    return true
                }
            },
        )
    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomAt(if (zoom > 1.01f) 1f else 2f, e.x, e.y)
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (scaleDetector.isInProgress || zoom <= 1f) return false
                offsetX -= distanceX
                offsetY -= distanceY
                updateViewport()
                return true
            }
        },
    ).apply { setIsLongpressEnabled(false) }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent.requestDisallowInterceptTouchEvent(zoom > 1f)
        }
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    internal fun zoomAt(value: Float, x: Float, y: Float) {
        val next = value.coerceIn(1f, 5f)
        val ratio = next / zoom
        offsetX = x - (x - offsetX) * ratio
        offsetY = y - (y - offsetY) * ratio
        zoom = next
        onZoom()
        updateViewport()
    }

    fun canPanLeft(): Boolean = bounds[0].left < -1f
    fun canPanRight(): Boolean = bounds[1].right > width + 1f
    fun panLeft() {
        offsetX += width
        updateViewport()
    }
    fun panRight() {
        offsetX -= width
        updateViewport()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        spreadLayout = DoublePageCompositionPolicy.fitInViewport(firstPage, secondPage, width, height)
        for (index in 0 until childCount) {
            getChildAt(index).measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (index in 0 until childCount) getChildAt(index).layout(0, 0, width, height)
        updateViewport()
    }

    fun updateViewport() {
        val sizes = (0 until childCount).map { (getChildAt(it) as? ReaderPageImageView)?.spreadImageSize() }
        if (sizes.size == 2 && sizes.all { it != null }) {
            spreadLayout = DoublePageCompositionPolicy.fitInViewport(
                DoublePageCompositionPolicy.Image(sizes[0]!!.first, sizes[0]!!.second, 0),
                DoublePageCompositionPolicy.Image(sizes[1]!!.first, sizes[1]!!.second, 0),
                width,
                height,
            )
        }
        val layout = spreadLayout ?: return
        fun constrain(offset: Float, start: Int, size: Int, viewport: Int): Float {
            val extent = size * zoom
            return if (extent <= viewport) {
                (viewport - extent) / 2f - start * zoom
            } else {
                offset.coerceIn(viewport - (start + size) * zoom, -start * zoom)
            }
        }
        offsetX = constrain(offsetX, layout.left, layout.outputWidth, width)
        offsetY = constrain(offsetY, layout.top, layout.height, height)
        val left = layout.left * zoom + offsetX
        val top = layout.top * zoom + offsetY
        val split = left + layout.firstWidth * zoom
        bounds[0].set(left, top, split, top + layout.height * zoom)
        bounds[1].set(split, top, left + layout.outputWidth * zoom, top + layout.height * zoom)
        for (index in 0 until childCount) {
            (getChildAt(index) as? ReaderPageImageView)?.setSpreadViewport(bounds[index], layout.height.toFloat())
        }
        onSplitFractionChanged(split / width)
        invalidate()
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val save = canvas.save()
        canvas.clipRect(bounds[indexOfChild(child)])
        return try {
            super.drawChild(canvas, child, drawingTime)
        } finally {
            canvas.restoreToCount(save)
        }
    }
}
