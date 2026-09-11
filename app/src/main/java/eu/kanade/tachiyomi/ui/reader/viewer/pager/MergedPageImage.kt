package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import java.io.InputStream

internal object MergedPageImage {
    class InsufficientMemoryException : IllegalStateException()

    fun write(left: () -> InputStream, right: () -> InputStream, destination: File) {
        fun dimensions(stream: () -> InputStream): DoublePageCompositionPolicy.Image {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            stream().use { BitmapFactory.decodeStream(it, null, options) }
            return DoublePageCompositionPolicy.Image(options.outWidth, options.outHeight, 0)
        }
        val first = dimensions(left)
        val second = dimensions(right)
        val layout = requireNotNull(DoublePageCompositionPolicy.compositionLayout(first, second)) {
            "Unable to decode page dimensions"
        }
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        if (!DoublePageCompositionPolicy.shouldCompose(first, second, available, runtime.maxMemory())) {
            throw InsufficientMemoryException()
        }
        val output = Bitmap.createBitmap(layout.outputWidth, layout.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            fun draw(stream: () -> InputStream, start: Int, end: Int) {
                val bitmap = requireNotNull(stream().use { BitmapFactory.decodeStream(it) }) {
                    "Unable to decode page image"
                }
                try {
                    canvas.drawBitmap(bitmap, null, Rect(start, 0, end, layout.height), paint)
                } finally {
                    bitmap.recycle()
                }
            }
            draw(left, 0, layout.firstWidth)
            draw(right, layout.firstWidth, layout.outputWidth)
            destination.outputStream().use { check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            output.recycle()
        }
    }
}
