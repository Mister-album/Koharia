package eu.kanade.tachiyomi.ui.reader.transition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Lightweight cylindrical page curl shared by the comic and EPUB readers. */
class PageCurlRenderer(
    meshColumns: Int = DEFAULT_MESH_COLUMNS,
    meshRows: Int = DEFAULT_MESH_ROWS,
) {
    private val meshColumns = meshColumns.coerceAtLeast(4)
    private val meshRows = meshRows.coerceAtLeast(6)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backsidePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val foldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pagePath = Path()
    private val vertices = FloatArray((this.meshColumns + 1) * (this.meshRows + 1) * 2)
    private val frontColors = IntArray((this.meshColumns + 1) * (this.meshRows + 1))
    private val bounds = RectF()

    fun draw(
        canvas: Canvas,
        bitmap: Bitmap,
        destination: RectF,
        progress: Float,
        visualDirection: Float,
        origin: PageTurnOrigin,
        horizontal: Boolean,
        backsideColor: Int,
        sourceMirrored: Boolean = false,
        drawBackside: Boolean = true,
    ) {
        if (bitmap.isRecycled || destination.isEmpty) return
        if (visualDirection < 0f && sourceMirrored) {
            val checkpoint = canvas.save()
            if (horizontal) {
                canvas.scale(-1f, 1f, destination.centerX(), destination.centerY())
            } else {
                canvas.scale(1f, -1f, destination.centerX(), destination.centerY())
            }
            draw(
                canvas = canvas,
                bitmap = bitmap,
                destination = destination,
                progress = progress,
                visualDirection = 1f,
                origin = if (horizontal) {
                    origin.copy(xFraction = 1f - origin.xFraction)
                } else {
                    origin.copy(yFraction = 1f - origin.yFraction)
                },
                horizontal = horizontal,
                backsideColor = backsideColor,
                sourceMirrored = false,
                drawBackside = drawBackside,
            )
            canvas.restoreToCount(checkpoint)
            return
        }
        bounds.set(destination)
        val turnProgress = progress.coerceIn(0f, 1f)
        if (turnProgress <= 0f) {
            bitmapPaint.alpha = 255
            canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
            return
        }

        val normalizedOrigin = origin.normalized()
        val mainSize = if (horizontal) destination.width() else destination.height()
        val crossFraction = if (horizontal) normalizedOrigin.yFraction else normalizedOrigin.xFraction
        val tilt = (crossFraction - 0.5f) * MAX_TILT_RADIANS
        val direction = if (visualDirection >= 0f) 1f else -1f
        val normalX = if (horizontal) direction * cos(tilt) else sin(tilt)
        val normalY = if (horizontal) sin(tilt) else direction * cos(tilt)
        val tangentX = -normalY
        val tangentY = normalX

        val corners = floatArrayOf(
            destination.left,
            destination.top,
            destination.right,
            destination.top,
            destination.right,
            destination.bottom,
            destination.left,
            destination.bottom,
        )
        var minimumQ = Float.POSITIVE_INFINITY
        var maximumQ = Float.NEGATIVE_INFINITY
        for (index in corners.indices step 2) {
            val q = corners[index] * normalX + corners[index + 1] * normalY
            minimumQ = minOf(minimumQ, q)
            maximumQ = maxOf(maximumQ, q)
        }
        val pageSpan = (maximumQ - minimumQ).coerceAtLeast(mainSize * 0.75f)
        val curlRadius = pageSpan * (BASE_RADIUS_FRACTION + (1f - abs(crossFraction - 0.5f) * 2f) * 0.018f)
        val foldQ = lerp(
            maximumQ + curlRadius * 0.08f,
            minimumQ - pageSpan * 0.24f,
            turnProgress,
        )
        val originX = destination.left + destination.width() * normalizedOrigin.xFraction
        val originY = destination.top + destination.height() * normalizedOrigin.yFraction
        val originV = originX * tangentX + originY * tangentY
        val halfTurnLength = PI.toFloat() * curlRadius

        var vertexIndex = 0
        var colorIndex = 0
        for (row in 0..meshRows) {
            val y = destination.top + destination.height() * row / meshRows
            for (column in 0..meshColumns) {
                val x = destination.left + destination.width() * column / meshColumns
                val q = x * normalX + y * normalY
                val v = x * tangentX + y * tangentY
                val distancePastFold = q - foldQ
                val mappedQ: Float
                val depth: Float
                val frontColor: Int
                when {
                    distancePastFold <= 0f -> {
                        mappedQ = q
                        depth = 0f
                        frontColor = Color.WHITE
                    }
                    distancePastFold < halfTurnLength -> {
                        val angle = distancePastFold / curlRadius
                        mappedQ = foldQ + curlRadius * sin(angle)
                        depth = curlRadius * (1f - cos(angle))
                        val curveShade = (184 + 71 * abs(cos(angle))).toInt().coerceIn(0, 255)
                        val frontsideAmount = ((HALF_PI + BACKSIDE_BLEND_RADIANS - angle) / BACKSIDE_BLEND_RADIANS)
                            .coerceIn(0f, 1f)
                        frontColor = Color.argb(
                            (248 * frontsideAmount).toInt(),
                            curveShade,
                            curveShade,
                            curveShade,
                        )
                    }
                    else -> {
                        mappedQ = foldQ - (distancePastFold - halfTurnLength)
                        depth = curlRadius * 2f
                        frontColor = Color.TRANSPARENT
                    }
                }
                val perspective = (1f - depth / (pageSpan * PERSPECTIVE_DIVISOR)).coerceAtLeast(0.9f)
                val mappedV = originV + (v - originV) * perspective
                vertices[vertexIndex++] = mappedQ * normalX + mappedV * tangentX
                vertices[vertexIndex++] = mappedQ * normalY + mappedV * tangentY
                frontColors[colorIndex] = frontColor
                colorIndex++
            }
        }

        val pageAlpha = if (turnProgress > FADE_START) {
            (((1f - turnProgress) / (1f - FADE_START)) * 255).toInt().coerceIn(0, 255)
        } else {
            255
        }
        bitmapPaint.alpha = pageAlpha
        if (drawBackside) {
            backsidePaint.alpha = pageAlpha
            backsidePaint.color = backsideColor
            buildPagePath()
            canvas.drawPath(pagePath, backsidePaint)
        }
        canvas.drawBitmapMesh(
            bitmap,
            meshColumns,
            meshRows,
            vertices,
            0,
            frontColors,
            0,
            bitmapPaint,
        )
        drawFoldLighting(canvas, foldQ, curlRadius, normalX, normalY, turnProgress)
    }

    private fun buildPagePath() {
        pagePath.reset()
        fun addVertex(row: Int, column: Int, move: Boolean = false) {
            val index = (row * (meshColumns + 1) + column) * 2
            if (move) {
                pagePath.moveTo(vertices[index], vertices[index + 1])
            } else {
                pagePath.lineTo(vertices[index], vertices[index + 1])
            }
        }
        addVertex(0, 0, move = true)
        for (column in 1..meshColumns) addVertex(0, column)
        for (row in 1..meshRows) addVertex(row, meshColumns)
        for (column in meshColumns - 1 downTo 0) addVertex(meshRows, column)
        for (row in meshRows - 1 downTo 1) addVertex(row, 0)
        pagePath.close()
    }

    private fun drawFoldLighting(
        canvas: Canvas,
        foldQ: Float,
        radius: Float,
        normalX: Float,
        normalY: Float,
        progress: Float,
    ) {
        val strength = sin(PI.toFloat() * progress.coerceIn(0f, 1f)).coerceAtLeast(0f)
        if (strength <= 0.001f) return
        val band = radius * 2.25f
        foldPaint.shader = LinearGradient(
            normalX * (foldQ - band),
            normalY * (foldQ - band),
            normalX * (foldQ + band),
            normalY * (foldQ + band),
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb((42 * strength).toInt(), 0, 0, 0),
                Color.argb((18 * strength).toInt(), 0, 0, 0),
                Color.argb((38 * strength).toInt(), 255, 255, 255),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.33f, 0.48f, 0.67f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(bounds, foldPaint)
        foldPaint.shader = null
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float = start + (end - start) * progress

    companion object {
        fun prepareBitmapForDirection(
            bitmap: Bitmap,
            visualDirection: Float,
            horizontal: Boolean,
        ): Bitmap? {
            if (visualDirection >= 0f) return bitmap
            val prepared = runCatching {
                Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: return null
            return runCatching {
                val canvas = Canvas(prepared)
                if (horizontal) {
                    canvas.translate(bitmap.width.toFloat(), 0f)
                    canvas.scale(-1f, 1f)
                } else {
                    canvas.translate(0f, bitmap.height.toFloat())
                    canvas.scale(1f, -1f)
                }
                canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                prepared
            }.getOrElse {
                prepared.recycle()
                null
            }
        }

        fun estimateBackgroundColor(bitmap: Bitmap): Int {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return Color.WHITE
            val maximumX = bitmap.width - 1
            val maximumY = bitmap.height - 1
            val points = arrayOf(
                0 to 0,
                maximumX to 0,
                maximumX to maximumY,
                0 to maximumY,
                maximumX / 2 to 0,
                maximumX / 2 to maximumY,
                0 to maximumY / 2,
                maximumX to maximumY / 2,
            )
            var red = 0L
            var green = 0L
            var blue = 0L
            var weight = 0L
            points.forEach { (x, y) ->
                val color = bitmap.getPixel(x, y)
                val alpha = Color.alpha(color).toLong()
                red += Color.red(color) * alpha
                green += Color.green(color) * alpha
                blue += Color.blue(color) * alpha
                weight += alpha
            }
            if (weight == 0L) return Color.WHITE
            return Color.rgb((red / weight).toInt(), (green / weight).toInt(), (blue / weight).toInt())
        }

        private const val DEFAULT_MESH_COLUMNS = 16
        private const val DEFAULT_MESH_ROWS = 24
        private const val MAX_TILT_RADIANS = 1.05f
        private const val BASE_RADIUS_FRACTION = 0.075f
        private const val PERSPECTIVE_DIVISOR = 3.6f
        private const val FADE_START = 0.93f
        private val HALF_PI = PI.toFloat() / 2f
        private const val BACKSIDE_BLEND_RADIANS = 0.22f
    }
}
