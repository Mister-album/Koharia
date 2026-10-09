package koharia.reader.resampling

import android.graphics.Bitmap
import androidx.annotation.Keep

@Keep
internal object ImageResampler {
    val available: Boolean by lazy {
        try {
            System.loadLibrary("koharia_resampling")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    @JvmStatic
    external fun premultiply(bitmap: Bitmap): Boolean

    @JvmStatic
    fun resize(
        input: Bitmap,
        output: Bitmap,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        options: ResamplingOptions = ResamplingOptions(),
    ): Boolean = resizeNative(
        input, output, left, top, right, bottom, x, y, width, height, options.kernel.nativeId, options.softening,
    )

    @JvmStatic
    private external fun resizeNative(
        input: Bitmap,
        output: Bitmap,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        kernel: Int,
        softening: Double,
    ): Boolean
}
