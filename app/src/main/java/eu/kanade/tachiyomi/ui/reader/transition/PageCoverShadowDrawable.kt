package eu.kanade.tachiyomi.ui.reader.transition

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

internal class PageCoverShadowDrawable : Drawable() {

    enum class Edge {
        LEFT,
        TOP,
        RIGHT,
        BOTTOM,
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawableAlpha = 255

    var edge: Edge = Edge.LEFT
        set(value) {
            if (field == value) return
            field = value
            updateShader(bounds)
            invalidateSelf()
        }

    override fun onBoundsChange(bounds: Rect) {
        updateShader(bounds)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || drawableAlpha == 0) return
        paint.alpha = drawableAlpha
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        val normalized = alpha.coerceIn(0, 255)
        if (drawableAlpha == normalized) return
        drawableAlpha = normalized
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in the Android SDK")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun updateShader(bounds: Rect) {
        if (bounds.isEmpty) {
            paint.shader = null
            return
        }
        val (startX, startY, endX, endY) = when (edge) {
            Edge.LEFT -> floatArrayOf(bounds.left.toFloat(), 0f, bounds.right.toFloat(), 0f)
            Edge.TOP -> floatArrayOf(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat())
            Edge.RIGHT -> floatArrayOf(bounds.right.toFloat(), 0f, bounds.left.toFloat(), 0f)
            Edge.BOTTOM -> floatArrayOf(0f, bounds.bottom.toFloat(), 0f, bounds.top.toFloat())
        }
        paint.shader = LinearGradient(
            startX,
            startY,
            endX,
            endY,
            intArrayOf(
                Color.argb(76, 0, 0, 0),
                Color.argb(24, 0, 0, 0),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.32f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
}
