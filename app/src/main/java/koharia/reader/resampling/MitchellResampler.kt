package koharia.reader.resampling

import android.graphics.Bitmap
import androidx.annotation.Keep

@Keep
internal object MitchellResampler {
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
    external fun resize(
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
    ): Boolean
}
