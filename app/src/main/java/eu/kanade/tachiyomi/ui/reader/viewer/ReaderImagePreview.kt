package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Rect
import okio.BufferedSource
import tachiyomi.decoder.ImageDecoder

/** Owned, bounded first-frame image. The full decoder retains final colour and zoom quality. */
class ReaderImagePreview private constructor(
    private var bitmap: Bitmap?,
    val width: Int,
    val height: Int,
) {
    fun take(): Bitmap = checkNotNull(bitmap).also { bitmap = null }

    fun recycle() {
        bitmap?.recycle()
        bitmap = null
    }

    companion object {
        /** Must run off the UI thread. A failed preview falls back to normal decoding. */
        fun decode(
            source: BufferedSource,
            cropBorders: Boolean,
            displayProfile: ByteArray? = null,
        ): ReaderImagePreview? = try {
            // Match the region decoder's source coordinates before publishing the first frame.
            // An uncropped preview forces a visible scale/center reset when cropped tiles arrive.
            val decoder = source.peek().inputStream().use {
                ImageDecoder.newInstance(it, cropBorders, displayProfile)
            }
            decoder?.let {
                try {
                    var sample = 1
                    while (((it.width.toLong() + sample - 1) / sample) *
                        ((it.height.toLong() + sample - 1) / sample) > 1_048_576L
                    ) {
                        sample *= 2
                    }
                    it.decode(Rect(0, 0, it.width, it.height), sample)
                        ?.let { bitmap -> ReaderImagePreview(bitmap, decoder.width, decoder.height) }
                } finally {
                    it.recycle()
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
