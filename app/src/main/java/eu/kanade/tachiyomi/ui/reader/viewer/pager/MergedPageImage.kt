package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import eu.kanade.tachiyomi.ui.reader.setting.MergedPageExportOptions
import eu.kanade.tachiyomi.ui.reader.setting.MergedPageFormat
import eu.kanade.tachiyomi.ui.reader.setting.MergedPageLayout
import java.io.File
import java.io.InputStream

internal object MergedPageImage {
    class InsufficientMemoryException : IllegalStateException()

    data class Export(val file: File, val mimeType: String, val extension: String)

    fun write(
        left: () -> InputStream,
        right: () -> InputStream,
        destination: File,
        options: MergedPageExportOptions,
    ): Export {
        fun dimensions(stream: () -> InputStream): DoublePageCompositionPolicy.Image {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            stream().use { BitmapFactory.decodeStream(it, null, bounds) }
            return DoublePageCompositionPolicy.Image(bounds.outWidth, bounds.outHeight, 0)
        }
        val first = dimensions(left)
        val second = dimensions(right)
        val layout = MergedPageExportPolicy.layout(first, second, options.layout)
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        if (!MergedPageExportPolicy.fitsMemory(layout, first, second, available, runtime.maxMemory())) {
            throw InsufficientMemoryException()
        }
        val output = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            if (options.layout == MergedPageLayout.ORIGINAL_PIXELS || options.format == MergedPageFormat.JPEG) {
                canvas.drawColor(Color.WHITE)
            }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            fun draw(stream: () -> InputStream, x: Int, placement: MergedPageExportPolicy.Placement) {
                val bitmap =
                    requireNotNull(stream().use { BitmapFactory.decodeStream(it) }) { "Unable to decode page image" }
                try {
                    val target = Rect(x, placement.top, x + placement.width, placement.top + placement.height)
                    val filter = if (bitmap.width == placement.width &&
                        bitmap.height == placement.height
                    ) {
                        null
                    } else {
                        paint
                    }
                    canvas.drawBitmap(bitmap, null, target, filter)
                } finally {
                    bitmap.recycle()
                }
            }
            draw(left, 0, layout.first)
            draw(right, layout.first.width, layout.second)
            if (options.format == MergedPageFormat.JPEG) {
                destination.outputStream().use { check(output.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                return Export(destination, "image/jpeg", "jpg")
            }
            destination.outputStream().use { check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            if (options.format == MergedPageFormat.LOSSLESS_AUTO &&
                MergedPageExportPolicy.supportsWebp(layout.width, layout.height, Build.VERSION.SDK_INT)
            ) {
                val candidate = File.createTempFile("merged-candidate-", ".webp", destination.parentFile)
                try {
                    val success = try {
                        candidate.outputStream().use { output.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 75, it) }
                    } catch (_: Exception) {
                        false
                    }
                    if (success && candidate.length() in 1 until destination.length()) {
                        candidate.copyTo(destination, overwrite = true)
                        return Export(destination, "image/webp", "webp")
                    }
                } finally {
                    candidate.delete()
                }
            }
            return Export(destination, "image/png", "png")
        } catch (e: Throwable) {
            destination.delete()
            throw e
        } finally {
            output.recycle()
        }
    }
}
